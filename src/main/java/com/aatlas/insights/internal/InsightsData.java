package com.aatlas.insights.internal;

import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.BulkModelReader.BulkModel;
import com.aatlas.history.Catalogue;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.Reference;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesStats;
import com.aatlas.history.Window;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything an insights engine reads for one request, loaded once by
 * {@link InsightsDataLoader}: the tenant-wide bulk model turned into {@link PairFacts}, the
 * catalogue rows behind it, and the reference/decision facts every engine shares. Replaces
 * the old {@code CatalogSnapshot} (hashed catalogue only) - every figure here traces back to
 * the tenant's own rows through {@code history}, or is labelled reference data.
 */
record InsightsData(
        LocalDate today,
        Window w12,
        BulkModel bulk,
        List<Catalogue.StoreRef> stores,
        List<Catalogue.RegionRef> regions,
        Map<String, Catalogue.ProductRef> productsByItem,
        Map<String, Catalogue.StoreRef> storesByCode,
        Map<String, PairFacts> pairsByKey,
        Map<String, List<PairFacts>> pairsByStore,
        Map<String, List<PairFacts>> pairsByItem,
        Reference.Guardrails guardrails,
        Map<String, DealSummaries.Adoption> adoptionByStore,
        DealSummaries.Adoption networkAdoption,
        DealSummaries.Adoption networkAdoptionPrior,
        SalesStats tenantW12,
        SalesStats tenantW12Prior,
        List<SalesHistory.GroupStats> byRegion,
        List<SalesHistory.GroupStats> byCategory,
        List<SalesHistory.GroupStats> byStore,
        List<SalesHistory.GroupStats> byCustomerSegment,
        List<PurchaseHistory.PoGroup> byOrigin,
        BigDecimal savedTotalW12,
        BigDecimal savedTotalW12Prior,
        BigDecimal overpaidTotalW12) {

    static String key(String item, String storeCodeOrNoBranch) {
        return item + "|" + storeCodeOrNoBranch;
    }

    Optional<PairFacts> pair(String item, String storeCodeOrNoBranch) {
        return Optional.ofNullable(pairsByKey.get(key(item, storeCodeOrNoBranch)));
    }

    /** Every priced pair at a branch (or {@code no-branch}), item-number order. */
    List<PairFacts> atStore(String storeCodeOrNoBranch) {
        return pairsByStore.getOrDefault(storeCodeOrNoBranch, List.of());
    }

    /** Every branch (incl. {@code no-branch}) this item prices at. */
    List<PairFacts> forItem(String item) {
        return pairsByItem.getOrDefault(item, List.of());
    }

    Optional<Catalogue.ProductRef> product(String item) {
        return Optional.ofNullable(productsByItem.get(item));
    }

    Optional<Catalogue.StoreRef> store(String code) {
        return Optional.ofNullable(storesByCode.get(code));
    }

    /** Follow-rate for a branch (or {@code no-branch}/network-wide with a null code); never seeded. */
    DealSummaries.Adoption adoptionFor(String storeCodeOrNull) {
        if (storeCodeOrNull == null) {
            return networkAdoption;
        }
        return adoptionByStore.getOrDefault(storeCodeOrNull, new DealSummaries.Adoption(0, 0));
    }

    static Optional<SalesHistory.GroupStats> group(List<SalesHistory.GroupStats> groups, String key) {
        return groups.stream().filter(g -> g.key().equals(key)).findFirst();
    }
}
