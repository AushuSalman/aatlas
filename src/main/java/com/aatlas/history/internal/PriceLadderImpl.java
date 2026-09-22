package com.aatlas.history.internal;

import com.aatlas.history.Anchor;
import com.aatlas.history.Catalogue;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.Inventory;
import com.aatlas.history.PriceLadder;
import com.aatlas.history.PriceList;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.Reference;
import com.aatlas.history.Resolved;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesStats;
import com.aatlas.history.Suppliers;
import com.aatlas.history.Window;
import com.aatlas.history.PricingMath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The truth ladder, rung by rung, in the order the contract documents. The single-pair
 * methods read through the public history interfaces; the bulk methods take a
 * {@link Snapshot} built from one statement per rung and answer every pair from it. Both
 * apply the same rules, so a Sell screen and a bulk plan never disagree.
 */
@Component
class PriceLadderImpl implements PriceLadder {

    private final PriceList priceList;
    private final SalesHistory sales;
    private final PurchaseHistory purchases;
    private final Suppliers suppliers;
    private final Catalogue catalogue;
    private final Reference reference;
    private final CompetitorPrices competitors;
    private final LadderSql bulk;

    PriceLadderImpl(PriceList priceList, SalesHistory sales, PurchaseHistory purchases, Suppliers suppliers,
            Catalogue catalogue, Reference reference, CompetitorPrices competitors, LadderSql bulk) {
        this.priceList = priceList;
        this.sales = sales;
        this.purchases = purchases;
        this.suppliers = suppliers;
        this.catalogue = catalogue;
        this.reference = reference;
        this.competitors = competitors;
        this.bulk = bulk;
    }

    // ---- single pair ---------------------------------------------------------------------

    @Override
    public Optional<Resolved> currentPrice(UUID productId, UUID storeIdOrNull, LocalDate today) {
        Optional<Resolved> listed = priceList.current(productId, storeIdOrNull, today)
                .flatMap(PriceLadderImpl::listPrice);
        if (listed.isPresent()) {
            return listed;
        }
        Window w12 = Window.trailingMonths(today, 12);
        SalesStats stats = storeIdOrNull != null ? sales.itemStore(productId, storeIdOrNull, w12)
                : sales.item(productId, w12);
        Optional<Resolved> own = salesPrice(stats, false);
        if (own.isPresent() || storeIdOrNull == null) {
            return own;
        }
        return salesPrice(sales.item(productId, w12), true);
    }

    @Override
    public Optional<Resolved> cost(UUID productId, UUID storeIdOrNull, LocalDate today) {
        Window w90 = Window.trailingDays(today, 90);
        Window w12 = Window.trailingMonths(today, 12);
        Optional<String> itemNumber = catalogue.productById(productId).map(Catalogue.ProductRef::itemNumber);
        if (itemNumber.isPresent()) {
            PurchaseHistory.PoStats stats = storeIdOrNull != null
                    ? purchases.itemAtStore(itemNumber.get(), storeIdOrNull, w90) : PurchaseHistory.PoStats.empty();
            if (!stats.any()) {
                stats = purchases.item(itemNumber.get(), w90).all();
            }
            Optional<Resolved> bought = purchased(stats);
            if (bought.isPresent()) {
                return bought;
            }
        }
        Optional<Resolved> listed = priceList.current(productId, storeIdOrNull, today).flatMap(PriceLadderImpl::listCost);
        if (listed.isPresent()) {
            return listed;
        }
        SalesStats stats = storeIdOrNull != null ? sales.itemStore(productId, storeIdOrNull, w12)
                : sales.item(productId, w12);
        Optional<Resolved> sold = salesCost(stats);
        if (sold.isEmpty() && storeIdOrNull != null) {
            sold = salesCost(sales.item(productId, w12));
        }
        if (sold.isPresent()) {
            return sold;
        }
        return supplierList(suppliers.panelFor(productId));
    }

    @Override
    public Optional<Anchor> anchor(UUID productId, UUID storeIdOrNull, LocalDate today) {
        String region = null;
        if (storeIdOrNull != null) {
            region = catalogue.storeById(storeIdOrNull).map(Catalogue.StoreRef::regionKey)
                    .filter(k -> !SalesHistory.GroupStats.UNASSIGNED.equals(k)).orElse(null);
        }
        Optional<Anchor> competitor = competitors.anchor(productId, region, storeIdOrNull, today);
        if (competitor.isPresent()) {
            return competitor;
        }
        Window w12 = Window.trailingMonths(today, 12);
        if (storeIdOrNull != null) {
            Optional<Anchor> peer = sales.peerBand(productId, storeIdOrNull, w12)
                    .map(p -> new Anchor(p.q2(), Anchor.PEER, p.stores()));
            if (peer.isPresent()) {
                return peer;
            }
        }
        Optional<Resolved> cost = cost(productId, storeIdOrNull, today);
        if (cost.isPresent()) {
            Optional<Anchor> benchmark = catalogue.productById(productId)
                    .map(p -> benchmarkAnchor(cost.get().value(), reference.benchmark(p.category(), p.subcategory())));
            if (benchmark.isPresent() && benchmark.get() != null) {
                return benchmark;
            }
        }
        SalesStats stats = storeIdOrNull != null ? sales.itemStore(productId, storeIdOrNull, w12)
                : sales.item(productId, w12);
        if (stats.lastPrice() != null && stats.lastPrice().signum() > 0) {
            return Optional.of(new Anchor(stats.lastPrice(), Anchor.HISTORY, 0));
        }
        return Optional.empty();
    }

    /** The bare benchmark anchor, {@code cost / (1 − target/100)}; region and commodity are applied once, in the chain. */
    static Anchor benchmarkAnchor(BigDecimal cost, Reference.Benchmark benchmark) {
        BigDecimal price = PricingMath.priceAtMargin(cost, benchmark.targetMarginPct());
        return price == null ? null : new Anchor(price, Anchor.BENCHMARK, 0);
    }

    // ---- rung readers shared by both paths --------------------------------------------------

    static Optional<Resolved> listPrice(PriceList.CurrentPrice current) {
        if (current == null || current.listPrice() == null || current.listPrice().signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(current.listPrice(), Resolved.PRICE_LIST, current.listPriceFrom()));
    }

    static Optional<Resolved> listCost(PriceList.CurrentPrice current) {
        if (current == null || current.cost() == null || current.cost().signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(current.cost(), Resolved.PRICE_LIST, current.costFrom()));
    }

    static Optional<Resolved> salesPrice(SalesStats stats, boolean itemLevelFallback) {
        if (stats == null || stats.lastPrice() == null || stats.lastPrice().signum() <= 0) {
            return Optional.empty();
        }
        String source = itemLevelFallback ? Resolved.SALES_ITEM_12M
                : "sales-90d".equals(stats.lastPriceSource()) ? Resolved.SALES_90D : Resolved.SALES_12M;
        return Optional.of(new Resolved(stats.lastPrice(), source, stats.lastSale()));
    }

    static Optional<Resolved> salesCost(SalesStats stats) {
        if (stats == null || stats.lastCost() == null || stats.lastCost().signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(stats.lastCost(), Resolved.SALES_COST, stats.lastSale()));
    }

    static Optional<Resolved> purchased(PurchaseHistory.PoStats stats) {
        if (stats == null || !stats.any() || stats.avgLanded() == null || stats.avgLanded().signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(stats.avgLanded(), Resolved.PURCHASES_90D, stats.lastOrder()));
    }

    /** Lowest ex-works on file plus that supplier's inbound freight and duty. */
    Optional<Resolved> supplierList(List<Suppliers.SupplierLink> links) {
        Resolved best = null;
        for (Suppliers.SupplierLink link : links) {
            if (link.exWorks() == null || link.exWorks().signum() <= 0) {
                continue;
            }
            BigDecimal landed = landed(link.exWorks(), reference.origin(link.supplier().country()));
            if (best == null || landed.compareTo(best.value()) < 0) {
                best = new Resolved(landed, Resolved.SUPPLIER_LIST, link.exWorksAsOf());
            }
        }
        return Optional.ofNullable(best);
    }

    static BigDecimal landed(BigDecimal exWorks, Reference.Origin origin) {
        BigDecimal factor = BigDecimal.ONE
                .add(origin.inboundPct().divide(PricingMath.HUNDRED, PricingMath.RATIO_SCALE, PricingMath.ROUNDING))
                .add(origin.dutyPct().divide(PricingMath.HUNDRED, PricingMath.RATIO_SCALE, PricingMath.ROUNDING));
        return PricingMath.money(exWorks.multiply(factor));
    }

    // ---- bulk ----------------------------------------------------------------------------

    @Override
    public Map<UUID, Resolved> currentPrices(UUID storeIdOrNull, LocalDate today) {
        Snapshot snapshot = snapshot(today, sales.pairStats(Window.trailingMonths(today, 12)));
        Map<UUID, Resolved> out = new HashMap<>();
        for (UUID productId : snapshot.candidates(storeIdOrNull)) {
            snapshot.currentPrice(productId, storeIdOrNull).ifPresent(r -> out.put(productId, r));
        }
        return out;
    }

    @Override
    public Map<UUID, Resolved> costs(UUID storeIdOrNull, LocalDate today) {
        Snapshot snapshot = snapshot(today, sales.pairStats(Window.trailingMonths(today, 12)));
        Map<UUID, Resolved> out = new HashMap<>();
        for (UUID productId : snapshot.candidates(storeIdOrNull)) {
            snapshot.cost(productId, storeIdOrNull).ifPresent(r -> out.put(productId, r));
        }
        return out;
    }

    /**
     * One statement per rung, then every pair answered from memory. The caller passes the
     * trailing-twelve-month pair stats it already has so they are not read twice.
     */
    Snapshot snapshot(LocalDate today, List<SalesHistory.PairStats> pairStatsW12) {
        Window w12 = Window.trailingMonths(today, 12);
        Window w90 = Window.trailingDays(today, 90);
        Map<Inventory.PairKey, PriceList.CurrentPrice> storeRows = priceList instanceof PriceListJdbc jdbc
                ? jdbc.currentStoreSpecific(today) : Map.of();
        Map<UUID, PriceList.CurrentPrice> tenantRows = priceList.currentForStore(null, today);
        Map<Inventory.PairKey, SalesStats> pairSales = new HashMap<>();
        for (SalesHistory.PairStats pair : pairStatsW12) {
            pairSales.put(new Inventory.PairKey(pair.productId(), pair.storeId()), pair.w12());
        }
        Map<UUID, SalesStats> itemSales = bulk.itemStats(w12);

        Map<Inventory.PairKey, Resolved> pairPurchases = new HashMap<>();
        Map<UUID, BigDecimal[]> itemSums = new HashMap<>();
        Map<UUID, LocalDate> itemLast = new HashMap<>();
        for (LadderSql.PoGroup group : bulk.purchaseGroups(w90)) {
            BigDecimal avg = PricingMath.div(group.landedTimesQty(), group.qty());
            if (avg != null && avg.signum() > 0) {
                pairPurchases.put(new Inventory.PairKey(group.productId(), group.storeId()),
                        new Resolved(PricingMath.money(avg), Resolved.PURCHASES_90D, group.lastOrder()));
            }
            BigDecimal[] sums = itemSums.computeIfAbsent(group.productId(),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(group.landedTimesQty());
            sums[1] = sums[1].add(group.qty());
            itemLast.merge(group.productId(), group.lastOrder(), (a, b) -> a.isAfter(b) ? a : b);
        }
        Map<UUID, Resolved> itemPurchases = new HashMap<>();
        itemSums.forEach((productId, sums) -> {
            BigDecimal avg = PricingMath.div(sums[0], sums[1]);
            if (avg != null && avg.signum() > 0) {
                itemPurchases.put(productId,
                        new Resolved(PricingMath.money(avg), Resolved.PURCHASES_90D, itemLast.get(productId)));
            }
        });

        Map<UUID, Resolved> supplierList = new HashMap<>();
        for (LadderSql.Quote quote : bulk.quotes()) {
            BigDecimal landed = landed(quote.exWorks(), reference.origin(quote.country()));
            Resolved current = supplierList.get(quote.productId());
            if (current == null || landed.compareTo(current.value()) < 0) {
                supplierList.put(quote.productId(), new Resolved(landed, Resolved.SUPPLIER_LIST, quote.asOf()));
            }
        }
        return new Snapshot(storeRows, tenantRows, pairSales, itemSales, pairPurchases, itemPurchases, supplierList);
    }

    /** The bulk rungs in memory; answers any (product, store) pair with the single-pair rules. */
    record Snapshot(
            Map<Inventory.PairKey, PriceList.CurrentPrice> storeRows,
            Map<UUID, PriceList.CurrentPrice> tenantRows,
            Map<Inventory.PairKey, SalesStats> pairSales,
            Map<UUID, SalesStats> itemSales,
            Map<Inventory.PairKey, Resolved> pairPurchases,
            Map<UUID, Resolved> itemPurchases,
            Map<UUID, Resolved> supplierList) {

        Optional<Resolved> currentPrice(UUID productId, UUID storeIdOrNull) {
            Optional<Resolved> listed = listed(productId, storeIdOrNull, true);
            if (listed.isPresent()) {
                return listed;
            }
            if (storeIdOrNull == null) {
                return salesPrice(itemSales.get(productId), false);
            }
            Optional<Resolved> own = salesPrice(pairSales.get(new Inventory.PairKey(productId, storeIdOrNull)), false);
            return own.isPresent() ? own : salesPrice(itemSales.get(productId), true);
        }

        Optional<Resolved> cost(UUID productId, UUID storeIdOrNull) {
            Resolved bought = storeIdOrNull == null ? null
                    : pairPurchases.get(new Inventory.PairKey(productId, storeIdOrNull));
            if (bought == null) {
                bought = itemPurchases.get(productId);
            }
            if (bought != null) {
                return Optional.of(bought);
            }
            Optional<Resolved> listed = listed(productId, storeIdOrNull, false);
            if (listed.isPresent()) {
                return listed;
            }
            Optional<Resolved> sold = storeIdOrNull == null ? salesCost(itemSales.get(productId))
                    : salesCost(pairSales.get(new Inventory.PairKey(productId, storeIdOrNull)));
            if (sold.isEmpty() && storeIdOrNull != null) {
                sold = salesCost(itemSales.get(productId));
            }
            if (sold.isPresent()) {
                return sold;
            }
            return Optional.ofNullable(supplierList.get(productId));
        }

        private Optional<Resolved> listed(UUID productId, UUID storeIdOrNull, boolean price) {
            java.util.function.Function<PriceList.CurrentPrice, Optional<Resolved>> component =
                    price ? PriceLadderImpl::listPrice : PriceLadderImpl::listCost;
            PriceList.CurrentPrice tenant = tenantRows.get(productId);
            if (storeIdOrNull != null) {
                Optional<Resolved> specific = component.apply(storeRows.get(new Inventory.PairKey(productId, storeIdOrNull)));
                if (specific.isPresent()) {
                    return specific;
                }
                // A tenant-wide entry may be another branch's row standing in (PriceListJdbc.scope);
                // that stand-in answers tenant-wide reads only, never a different branch.
                if (tenant != null && (price ? tenant.listPriceStoreSpecific() : tenant.costStoreSpecific())) {
                    return Optional.empty();
                }
            }
            return component.apply(tenant);
        }

        /** Store-specific price-list pairs: part of the priceable universe. */
        Set<Inventory.PairKey> storeSpecificPairs() {
            return storeRows.keySet();
        }

        /** Products with a true tenant-wide row for either component - the ones priced at every branch. */
        Set<UUID> tenantWideProducts() {
            Set<UUID> out = new LinkedHashSet<>();
            tenantRows.forEach((productId, row) -> {
                if ((row.listPrice() != null && !row.listPriceStoreSpecific())
                        || (row.cost() != null && !row.costStoreSpecific())) {
                    out.add(productId);
                }
            });
            return out;
        }

        /** Every product any rung could answer for, at a store or tenant-wide. */
        Set<UUID> candidates(UUID storeIdOrNull) {
            Set<UUID> out = new LinkedHashSet<>(tenantRows.keySet());
            out.addAll(itemSales.keySet());
            out.addAll(itemPurchases.keySet());
            out.addAll(supplierList.keySet());
            for (Inventory.PairKey key : storeRows.keySet()) {
                if (storeIdOrNull == null || storeIdOrNull.equals(key.storeId())) {
                    out.add(key.productId());
                }
            }
            for (Inventory.PairKey key : pairSales.keySet()) {
                if (storeIdOrNull == null || storeIdOrNull.equals(key.storeId())) {
                    out.add(key.productId());
                }
            }
            return out;
        }
    }
}
