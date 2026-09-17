package com.aatlas.history.internal;

import com.aatlas.common.cache.CacheNames;
import com.aatlas.history.Anchor;
import com.aatlas.history.BulkModelReader;
import com.aatlas.history.Catalogue;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.Inventory;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesStats;
import com.aatlas.history.Window;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * The bulk model from a fixed handful of statements: pair stats for the window and the
 * prior one, price bands, the ladder snapshot, stock, competitor medians, purchase stats,
 * and the catalogue. Never a query per pair.
 */
@Component
class BulkModelReaderImpl implements BulkModelReader {

    private final SalesHistory sales;
    private final PriceLadderImpl ladder;
    private final Inventory inventory;
    private final CompetitorPrices competitors;
    private final PurchaseHistory purchases;
    private final Catalogue catalogue;

    BulkModelReaderImpl(SalesHistory sales, PriceLadderImpl ladder, Inventory inventory, CompetitorPrices competitors,
            PurchaseHistory purchases, Catalogue catalogue) {
        this.sales = sales;
        this.ladder = ladder;
        this.inventory = inventory;
        this.competitors = competitors;
        this.purchases = purchases;
        this.catalogue = catalogue;
    }

    @Override
    @Cacheable(cacheNames = CacheNames.BULK_MODEL, key = "T(com.aatlas.common.cache.CacheNames).key("
            + "T(com.aatlas.common.tenant.TenantContext).requireTenantId(), 'bulk',"
            + " #storeIdOrNull == null ? 'all' : #storeIdOrNull.toString(), #today)")
    public BulkModel bulkModel(UUID storeIdOrNull, LocalDate today) {
        Window w12 = Window.trailingMonths(today, 12);
        List<SalesHistory.PairStats> pairs = sales.pairStats(w12);
        List<SalesHistory.PairStats> prior = sales.pairStats(w12.prior());
        Map<UUID, SalesHistory.PriceBand> bands = sales.priceBands(w12);
        PriceLadderImpl.Snapshot snapshot = ladder.snapshot(today, pairs);
        Map<Inventory.PairKey, Inventory.OnHand> stock = inventory.all();
        Map<UUID, Anchor> medians = competitors.medians(today);
        Map<String, PurchaseHistory.PoStats> bought = purchases.itemStats(w12);
        Map<UUID, Catalogue.ProductRef> products = catalogue.products().stream()
                .collect(Collectors.toMap(Catalogue.ProductRef::id, Function.identity()));
        Map<UUID, Catalogue.StoreRef> stores = catalogue.stores().stream()
                .collect(Collectors.toMap(Catalogue.StoreRef::id, Function.identity()));

        Map<Inventory.PairKey, SalesHistory.PairStats> byPair = new HashMap<>();
        for (SalesHistory.PairStats pair : pairs) {
            byPair.put(new Inventory.PairKey(pair.productId(), pair.storeId()), pair);
        }
        Map<Inventory.PairKey, SalesStats> priorByPair = new HashMap<>();
        for (SalesHistory.PairStats pair : prior) {
            priorByPair.put(new Inventory.PairKey(pair.productId(), pair.storeId()), pair.w12());
        }

        // The pair universe: sales in the window, price-list rows (tenant-wide ones at every
        // active branch) and stock counts - filtered to the store when one was asked for.
        Set<Inventory.PairKey> universe = new LinkedHashSet<>(byPair.keySet());
        universe.addAll(snapshot.storeSpecificPairs());
        for (UUID productId : snapshot.tenantWideProducts()) {
            for (UUID storeId : stores.keySet()) {
                universe.add(new Inventory.PairKey(productId, storeId));
            }
        }
        universe.addAll(stock.keySet());

        double[] volumes = pairs.stream().mapToDouble(p -> p.units90().doubleValue()).sorted().toArray();

        List<PairModel> models = new ArrayList<>();
        for (Inventory.PairKey key : universe) {
            if (storeIdOrNull != null && !storeIdOrNull.equals(key.storeId())) {
                continue;
            }
            Catalogue.ProductRef product = products.get(key.productId());
            if (product == null) {
                continue;
            }
            SalesHistory.PairStats pair = byPair.get(key);
            Catalogue.StoreRef store = key.storeId() == null ? null : stores.get(key.storeId());
            String storeCode = store != null ? store.storeCode()
                    : pair != null ? pair.storeCode() : SalesHistory.GroupStats.NO_BRANCH;
            String storeLabel = store != null ? store.label()
                    : key.storeId() == null ? "No branch on file" : "#" + storeCode;
            SalesHistory.PriceBand band = bands.get(key.productId());
            SalesHistory.PeerBand peer = band == null || key.storeId() == null ? null
                    : SalesHistoryJdbc.peerBand(band.storeMedians(), key.storeId()).orElse(null);
            models.add(new PairModel(
                    product.id(), product.itemNumber(), product.shortName(), product.category(),
                    product.subcategory(), product.commodity(),
                    key.storeId(), storeCode, storeLabel,
                    store != null ? store.regionKey() : SalesHistory.GroupStats.UNASSIGNED,
                    store != null ? store.rpp() : null, store != null ? store.segment() : null,
                    pair != null ? pair.w12() : SalesStats.empty(),
                    priorByPair.getOrDefault(key, SalesStats.empty()),
                    pair != null ? pair.units90() : BigDecimal.ZERO,
                    pair != null ? pair.unitsPrior90() : BigDecimal.ZERO,
                    pair != null ? pair.avgPrice30() : null,
                    pair != null ? pair.avgPrice90p() : null,
                    pair != null ? percentRank(volumes, pair.units90().doubleValue()) : null,
                    band, peer,
                    snapshot.currentPrice(key.productId(), key.storeId()).orElse(null),
                    snapshot.cost(key.productId(), key.storeId()).orElse(null),
                    medians.get(key.productId()),
                    stock.get(key),
                    bought.get(product.itemNumber())));
        }
        models.sort(Comparator.comparing(PairModel::itemNumber)
                .thenComparing(PairModel::storeCode, Comparator.nullsLast(Comparator.naturalOrder())));
        return new BulkModel(today, List.copyOf(models), sales.coverage(), !stock.isEmpty(),
                inventory.latestAsOf().orElse(null));
    }

    /** PERCENT_RANK: the share of the other pairs this one out-sells, 0-1. */
    static BigDecimal percentRank(double[] sortedVolumes, double value) {
        int n = sortedVolumes.length;
        if (n <= 1) {
            return BigDecimal.ONE.setScale(4);
        }
        int below = 0;
        int index = Arrays.binarySearch(sortedVolumes, value);
        if (index >= 0) {
            while (index > 0 && sortedVolumes[index - 1] == value) {
                index--;
            }
            below = index;
        } else {
            below = -index - 1;
        }
        return BigDecimal.valueOf(below).divide(BigDecimal.valueOf(n - 1), 4, RoundingMode.HALF_UP);
    }
}
