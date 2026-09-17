package com.aatlas.ingest.internal;

import com.aatlas.ingest.internal.csv.CompetitorPriceRow;
import com.aatlas.ingest.internal.csv.ImportKind;
import com.aatlas.ingest.internal.csv.ValidationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes competitor-price observations into {@code competitor_prices}.
 *
 * <p>The region column is resolved three ways: a branch code gives the branch and its region,
 * one of the four region keys gives the region, a subdivision code (a US state, a UK region)
 * gives the region it belongs to. Anything else keeps the observation with no region, which
 * the validator has already said. A branch with no region yet yields a null region and the
 * branch id: the observation is still that branch's once someone places it.
 */
@Component
class CompetitorPriceLoader implements KindLoader<CompetitorPriceRow> {

    private static final Logger log = LoggerFactory.getLogger(CompetitorPriceLoader.class);

    static final int BATCH_SIZE = 1_000;

    private static final Set<String> REGION_KEYS = Set.of("south", "west", "north", "east");

    private static final String INSERT_SQL = """
            insert into competitor_prices (
                tenant_id, product_id, competitor, price, currency, region_key, store_id, observed_at, source_url,
                source, import_batch_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (tenant_id, product_id, lower(competitor),
                         coalesce(store_id, '00000000-0000-0000-0000-000000000000'::uuid),
                         coalesce(region_key, ''), observed_at)
            do update set price = excluded.price, currency = excluded.currency, source_url = excluded.source_url,
                          source = excluded.source, import_batch_id = excluded.import_batch_id, updated_at = now()
            """;

    private final JdbcTemplate jdbc;

    CompetitorPriceLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ImportKind kind() {
        return ImportKind.COMPETITOR_PRICES;
    }

    @Override
    public Load<CompetitorPriceRow> begin(UUID tenantId, UUID batchId, String source, ValidationContext ctx) {
        return new CompetitorLoad(tenantId, batchId, source);
    }

    final class CompetitorLoad implements Load<CompetitorPriceRow> {

        private final UUID tenantId;
        private final UUID batchId;
        private final String rowSource;
        private final CatalogueIndex index;
        private final Map<String, String> subdivisions;
        private final Set<String> competitors = new LinkedHashSet<>();
        private final Set<String> regionsUnresolved = new LinkedHashSet<>();
        /** Deduplicated by the unique key, so one statement never touches a row twice. */
        private final Map<String, Object[]> pending = new LinkedHashMap<>();
        private int loaded;

        private CompetitorLoad(UUID tenantId, UUID batchId, String source) {
            this.tenantId = tenantId;
            this.batchId = batchId;
            this.rowSource = CatalogueIndex.rowSource(source);
            this.index = new CatalogueIndex(jdbc, tenantId, batchId, source);
            this.subdivisions = index.subdivisions();
        }

        @Override
        public void add(CompetitorPriceRow row) {
            UUID productId = index.product(row.item(), row.item(), false).id();
            String regionKey = null;
            UUID storeId = null;
            String region = row.region() == null ? "" : row.region().strip();
            if (!region.isEmpty()) {
                String key = region.toLowerCase(Locale.ROOT);
                CatalogueIndex.StoreRef store = index.lookupStore(region);
                if (store != null) {
                    storeId = store.id();
                    regionKey = REGION_KEYS.contains(store.regionKey()) ? store.regionKey() : null;
                } else if (REGION_KEYS.contains(key)) {
                    regionKey = key;
                } else if (subdivisions.containsKey(key)) {
                    regionKey = subdivisions.get(key);
                } else {
                    regionsUnresolved.add(region);
                }
            }
            competitors.add(row.competitor().toLowerCase(Locale.ROOT));

            String dedupe = productId + "|" + row.competitor().toLowerCase(Locale.ROOT) + "|" + storeId + "|"
                    + regionKey + "|" + row.observedAt();
            pending.put(dedupe, new Object[] {
                tenantId, productId, row.competitor(), row.price(), row.currency(), regionKey, storeId,
                row.observedAt(), row.sourceUrl(), rowSource, batchId
            });
            loaded++;
            if (pending.size() >= BATCH_SIZE) {
                flush();
            }
        }

        @Override
        public LoadResult finish() {
            flush();
            log.info("Competitor prices import {}: {} observations, {} competitors, {} regions unresolved",
                    batchId, loaded, competitors.size(), regionsUnresolved.size());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("observationsWritten", loaded);
            summary.put("distinctCompetitors", competitors.size());
            summary.put("regionsUnresolved", CatalogueIndex.capped(regionsUnresolved));
            return new LoadResult(loaded, index.productsCreated(), index.branchesCreated(),
                    index.branchesNeedingRegion(), 0, summary);
        }

        private void flush() {
            if (pending.isEmpty()) {
                return;
            }
            List<Object[]> rows = new ArrayList<>(pending.values());
            jdbc.batchUpdate(INSERT_SQL, rows, rows.size(), SalesTransactionLoader::bind);
            pending.clear();
        }
    }
}
