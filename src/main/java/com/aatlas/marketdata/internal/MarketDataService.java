package com.aatlas.marketdata.internal;

import com.aatlas.common.cache.CacheNames;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps {@code commodities} and {@code fx_rate} current from FRED and Frankfurter.
 *
 * <p>Commodity move: the index's latest month against the month three months earlier - the
 * 90-day move every engine already reads as {@code pct90}. A series that fails or has too little
 * history leaves its commodity as it was; the report says which and why. After a refresh the
 * reference cache and the tenant caches built on it are cleared, so the next recommendation,
 * buy-now-vs-wait and wizard suggestion read the new figure.
 */
@Service
class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    /** A manual refresh inside this window returns the last report instead of calling out again. */
    static final long MIN_SECONDS_BETWEEN_RUNS = 600;

    private final FredClient fred;
    private final FrankfurterClient fx;
    private final JdbcTemplate jdbc;
    private final Environment env;
    private final CacheManager caches;
    private final CacheManager referenceCaches;
    private final boolean enabled;
    /** Wall-clock bookkeeping for the throttle, not a business "now". */
    private final Clock clock = Clock.systemUTC();
    private final AtomicReference<RunReport> last = new AtomicReference<>();

    MarketDataService(FredClient fred, FrankfurterClient fx, JdbcTemplate jdbc, Environment env,
            CacheManager caches, @Qualifier("referenceCacheManager") CacheManager referenceCaches,
            @Value("${aatlas.market-data.enabled:true}") boolean enabled) {
        this.fred = fred;
        this.fx = fx;
        this.jdbc = jdbc;
        this.env = env;
        this.caches = caches;
        this.referenceCaches = referenceCaches;
        this.enabled = enabled;
    }

    record SeriesResult(String commodity, String seriesId, String status, BigDecimal pct90, LocalDate asOf,
            String message) {
    }

    record RunReport(Instant ranAt, boolean fredConfigured, List<SeriesResult> commodities, LocalDate fxDate,
            int fxRatesWritten, String fxMessage) {
    }

    record CommodityRow(String key, String label, BigDecimal pct90, LocalDate asOf, String source, String seriesId) {
    }

    record FxRow(String quote, BigDecimal perUsd, LocalDate asOf, String source) {
    }

    record Status(boolean enabled, boolean fredConfigured, List<CommodityRow> commodities, List<FxRow> fx,
            RunReport lastRun) {
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onStartup() {
        if (enabled) {
            refreshAll();
        }
    }

    @Scheduled(cron = "${aatlas.market-data.cron:0 30 6 * * *}", zone = "UTC")
    @SchedulerLock(name = "marketdata-refresh", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void scheduled() {
        if (enabled) {
            refreshAll();
        }
    }

    /** A person asked: runs unless the last run was under ten minutes ago. */
    RunReport refreshNow() {
        RunReport prev = last.get();
        if (prev != null && prev.ranAt().plusSeconds(MIN_SECONDS_BETWEEN_RUNS).isAfter(clock.instant())) {
            return prev;
        }
        return refreshAll();
    }

    synchronized RunReport refreshAll() {
        List<SeriesResult> commodities = refreshCommodities();
        LocalDate fxDate = null;
        int fxWritten = 0;
        String fxMessage = null;
        try {
            var res = refreshFx();
            fxDate = res.date();
            fxWritten = res.perUsd().size();
        } catch (RuntimeException ex) {
            fxMessage = ex.getMessage();
            log.warn("FX refresh failed: {}", ex.getMessage());
        }
        clearCaches();
        RunReport report = new RunReport(clock.instant(), fred.available(), commodities, fxDate, fxWritten, fxMessage);
        last.set(report);
        log.info("Market data refreshed: {} commodities updated, {} FX rates ({})",
                commodities.stream().filter(c -> "updated".equals(c.status())).count(), fxWritten, fxDate);
        return report;
    }

    Status status() {
        List<CommodityRow> commodities = jdbc.query(
                "select commodity_key, label, pct90, as_of, source from commodities order by commodity_key",
                (rs, i) -> new CommodityRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getObject(4, LocalDate.class), rs.getString(5), series(rs.getString(1))));
        List<FxRow> rates = jdbc.query("""
                select distinct on (quote) quote, rate, as_of, source
                  from fx_rate where base = 'USD' and quote <> 'USD'
                 order by quote, as_of desc
                """, (rs, i) -> new FxRow(rs.getString(1), rs.getBigDecimal(2), rs.getObject(3, LocalDate.class),
                        rs.getString(4)));
        return new Status(enabled, fred.available(), commodities, rates, last.get());
    }

    // ---- commodities ---------------------------------------------------------------------

    private String series(String commodityKey) {
        String id = env.getProperty("aatlas.market-data.fred.series." + commodityKey);
        return id == null || id.isBlank() ? null : id.strip();
    }

    private List<SeriesResult> refreshCommodities() {
        List<String> keys = jdbc.queryForList(
                "select commodity_key from commodities where commodity_key <> 'none' order by commodity_key", String.class);
        List<SeriesResult> out = new ArrayList<>();
        for (String key : keys) {
            String id = series(key);
            if (id == null) {
                out.add(new SeriesResult(key, null, "no-series", null, null, "No FRED series configured"));
            } else if (!fred.available()) {
                out.add(new SeriesResult(key, id, "skipped", null, null, "FRED_API_KEY not set"));
            } else {
                out.add(refreshCommodity(key, id));
            }
        }
        return out;
    }

    private SeriesResult refreshCommodity(String key, String seriesId) {
        try {
            Move move = move(fred.latest(seriesId, 12));
            if (move == null) {
                return new SeriesResult(key, seriesId, "failed", null, null, "Fewer than 3 months of data");
            }
            String label = name(key) + " producer prices " + (move.pct().signum() >= 0 ? "up " : "down ")
                    + move.pct().abs().setScale(1, RoundingMode.HALF_UP).toPlainString() + "% in 3 months";
            jdbc.update("""
                    update commodities set pct90 = ?, label = ?, as_of = ?, source = ?, updated_at = now()
                     where commodity_key = ?
                    """, move.pct(), label, move.asOf(), "FRED " + seriesId, key);
            return new SeriesResult(key, seriesId, "updated", move.pct(), move.asOf(), label);
        } catch (RuntimeException ex) {
            log.warn("FRED refresh of {} ({}) failed: {}", key, seriesId, ex.getMessage());
            return new SeriesResult(key, seriesId, "failed", null, null, ex.getMessage());
        }
    }

    record Move(BigDecimal pct, LocalDate asOf) {
    }

    /**
     * The latest value against the one three months before it (or the nearest earlier month), as
     * a percentage at two places, clamped to what {@code numeric(6,2)} holds. Newest-first input.
     */
    static Move move(List<FredClient.Observation> newestFirst) {
        if (newestFirst.isEmpty()) {
            return null;
        }
        FredClient.Observation latest = newestFirst.get(0);
        LocalDate target = latest.date().minusMonths(3);
        FredClient.Observation base = newestFirst.stream()
                .filter(o -> !o.date().isAfter(target))
                .findFirst().orElse(null);
        if (base == null || base.value().signum() == 0) {
            return null;
        }
        BigDecimal pct = latest.value().divide(base.value(), 8, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cap = new BigDecimal("9999.99");
        pct = pct.min(cap).max(cap.negate());
        return new Move(pct, latest.date());
    }

    private static String name(String key) {
        return switch (key) {
            case "pvc" -> "PVC";
            case "pex" -> "PEX";
            default -> key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
        };
    }

    // ---- FX -----------------------------------------------------------------------------

    private FrankfurterClient.Rates refreshFx() {
        List<String> quotes = jdbc.queryForList(
                "select distinct quote from fx_rate where base = 'USD' and quote <> 'USD' order by quote", String.class);
        if (quotes.isEmpty()) {
            return new FrankfurterClient.Rates(null, Map.of());
        }
        FrankfurterClient.Rates rates = fx.latestPerUsd(quotes);
        List<Object[]> rows = new ArrayList<>();
        rates.perUsd().forEach((quote, rate) -> rows.add(new Object[] {quote, rates.date(), rate}));
        jdbc.batchUpdate("""
                insert into fx_rate (base, quote, as_of, rate, source)
                values ('USD', ?, ?, ?, 'frankfurter (ECB)')
                on conflict (base, quote, as_of) do update set rate = excluded.rate, source = excluded.source
                """, rows);
        return rates;
    }

    private void clearCaches() {
        Cache reference = referenceCaches.getCache(CacheNames.REFERENCE);
        if (reference != null) {
            reference.clear();
        }
        for (String name : CacheNames.TENANT_SCOPED) {
            Cache cache = caches.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }
}
