package com.aatlas.insights.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.Anchor;
import com.aatlas.history.BulkModelReader;
import com.aatlas.history.BulkModelReader.BulkModel;
import com.aatlas.history.BulkModelReader.PairModel;
import com.aatlas.history.Catalogue;
import com.aatlas.history.PricingMath;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.Reference;
import com.aatlas.history.Resolved;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.Window;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Builds {@link InsightsData} for the current tenant and request: one call to
 * {@code BulkModelReader.bulkModel(null, today)} (the whole tenant, every store, cached),
 * turned into {@link PairFacts} by resolving each pair's market anchor down the same
 * competitor -&gt; peer -&gt; benchmark -&gt; history chain {@code history.PriceLadder} uses, and
 * its demand signal through {@code SalesHistory.velocity}. Replaces {@code
 * CatalogSnapshotReader}, which read the wave-1 hashed catalogue tables directly.
 */
@Component
class InsightsDataLoader {

    private final BulkModelReader bulkModelReader;
    private final Catalogue catalogue;
    private final Reference reference;
    private final SalesHistory salesHistory;
    private final PurchaseHistory purchaseHistory;
    private final DealSummaries dealSummaries;
    private final AatlasClock clock;

    InsightsDataLoader(BulkModelReader bulkModelReader, Catalogue catalogue, Reference reference,
            SalesHistory salesHistory, PurchaseHistory purchaseHistory, DealSummaries dealSummaries,
            AatlasClock clock) {
        this.bulkModelReader = bulkModelReader;
        this.catalogue = catalogue;
        this.reference = reference;
        this.salesHistory = salesHistory;
        this.purchaseHistory = purchaseHistory;
        this.dealSummaries = dealSummaries;
        this.clock = clock;
    }

    InsightsData load() {
        LocalDate today = clock.today();
        List<Catalogue.ProductRef> products = catalogue.products();
        if (products.isEmpty()) {
            throw noCatalogue();
        }
        List<Catalogue.StoreRef> stores = catalogue.stores();
        List<Catalogue.RegionRef> regions = catalogue.regions();
        BulkModel bulk = bulkModelReader.bulkModel(null, today);
        Reference.Guardrails guardrails = reference.guardrails();
        Window w12 = Window.trailingMonths(today, 12);

        Map<String, Catalogue.ProductRef> productsByItem = products.stream()
                .collect(Collectors.toMap(Catalogue.ProductRef::itemNumber, Function.identity(), (a, b) -> a));
        Map<String, Catalogue.StoreRef> storesByCode = stores.stream()
                .collect(Collectors.toMap(Catalogue.StoreRef::storeCode, Function.identity(), (a, b) -> a));

        Map<String, PairFacts> pairsByKey = new LinkedHashMap<>();
        Map<String, List<PairFacts>> pairsByStore = new LinkedHashMap<>();
        Map<String, List<PairFacts>> pairsByItem = new LinkedHashMap<>();

        for (PairModel p : bulk.pairs()) {
            PairFacts facts = buildFacts(p, guardrails, today);
            String key = InsightsData.key(p.itemNumber(), facts.storeKey());
            pairsByKey.put(key, facts);
            pairsByStore.computeIfAbsent(facts.storeKey(), k -> new ArrayList<>()).add(facts);
            pairsByItem.computeIfAbsent(p.itemNumber(), k -> new ArrayList<>()).add(facts);
        }

        Map<String, DealSummaries.Adoption> adoptionByStore = new LinkedHashMap<>();
        for (Catalogue.StoreRef store : stores) {
            adoptionByStore.put(store.storeCode(),
                    dealSummaries.adoption("sell", w12.from(), w12.to(), store.storeCode()));
        }
        DealSummaries.Adoption networkAdoption = dealSummaries.adoption("sell", w12.from(), w12.to(), null);
        Window w12prior = w12.prior();
        DealSummaries.Adoption networkAdoptionPrior =
                dealSummaries.adoption("sell", w12prior.from(), w12prior.to(), null);

        var tenantW12 = salesHistory.tenant(w12);
        var tenantW12Prior = salesHistory.tenant(w12.prior());
        var byRegion = salesHistory.byRegion(w12);
        var byCategory = salesHistory.byCategory(w12);
        var byStore = salesHistory.byStore(w12);
        var byCustomerSegment = salesHistory.byCustomerSegment(w12);
        var byOrigin = purchaseHistory.byOrigin(w12);
        var savedTotalW12 = purchaseHistory.savedTotal(w12);
        var savedTotalW12Prior = purchaseHistory.savedTotal(w12.prior());
        var overpaidTotalW12 = purchaseHistory.overpaidTotal(w12);

        return new InsightsData(today, w12, bulk, stores, regions, productsByItem, storesByCode,
                pairsByKey, pairsByStore, pairsByItem, guardrails, adoptionByStore, networkAdoption,
                networkAdoptionPrior, tenantW12, tenantW12Prior, byRegion, byCategory, byStore, byCustomerSegment,
                byOrigin, savedTotalW12, savedTotalW12Prior, overpaidTotalW12);
    }

    private PairFacts buildFacts(PairModel p, Reference.Guardrails guardrails, LocalDate today) {
        BigDecimal commodityPct90 = reference.commodity(p.commodity()).pct90();
        Anchor anchor = buildAnchor(p);

        PricingMath.Demand demand = null;
        if (p.storeId() != null) {
            SalesHistory.Velocity velocity = salesHistory.velocity(p.productId(), p.storeId(), today);
            demand = PricingMath.demand(velocity);
        }

        BigDecimal cost = p.cost() == null ? null : p.cost().value();
        BigDecimal ownRef = p.w12() == null ? null : p.w12().lastPrice();
        BigDecimal bandQ1 = p.band() == null ? null : p.band().q1();
        BigDecimal peerQ3 = p.peer() == null ? null : p.peer().q3();
        var recommendation = PricingMath.recommend(cost, anchor, ownRef, demand, commodityPct90, p.rpp() == null ? 1
                : 1 - ((p.rpp().doubleValue() - 100) / 100) * 0.55, PairFacts.DEFAULT_BETA, guardrails, bandQ1, peerQ3);

        return new PairFacts(p, demand, anchor, commodityPct90, recommendation);
    }

    /**
     * The market anchor, same fallback chain as {@code history.PriceLadder.anchor}: competitor
     * -&gt; peer -&gt; benchmark -&gt; history -&gt; none. Built here from the bulk model's own
     * fields (competitor medians and peer bands are already loaded for every pair) rather than
     * an extra call per pair, so insights agrees with sell/bulk without re-querying.
     */
    private Anchor buildAnchor(PairModel p) {
        if (p.competitor() != null && p.competitor().value() != null && p.competitor().value().signum() > 0) {
            return p.competitor();
        }
        if (p.peer() != null && p.peer().q2() != null) {
            return new Anchor(p.peer().q2(), Anchor.PEER, p.peer().stores());
        }
        Resolved cost = p.cost();
        if (cost != null && cost.value() != null && cost.value().signum() > 0) {
            Reference.Benchmark benchmark = reference.benchmark(p.category(), p.subcategory());
            if (benchmark != null && benchmark.targetMarginPct() != null) {
                BigDecimal value = PricingMath.priceAtMargin(cost.value(), benchmark.targetMarginPct());
                if (value != null && value.signum() > 0) {
                    return new Anchor(value, Anchor.BENCHMARK, 0);
                }
            }
        }
        BigDecimal lastPrice = p.w12() == null ? null : p.w12().lastPrice();
        if (lastPrice != null && lastPrice.signum() > 0) {
            return new Anchor(lastPrice, Anchor.HISTORY, 0);
        }
        return null;
    }

    static ApiException noCatalogue() {
        return new ApiException(HttpStatus.NOT_FOUND, "no_catalogue",
                "This workspace has no catalogue yet. Connect a data source with "
                        + "POST /api/v1/data-sources - {\"kind\":\"sample\"} is the quickest way to see "
                        + "every screen.");
    }
}
