package com.aatlas.bulk.internal;

import com.aatlas.bulk.BuyLine;
import com.aatlas.bulk.BuyLineReader;
import com.aatlas.bulk.CommercialTerms;
import com.aatlas.bulk.SupplierEval;
import com.aatlas.bulk.internal.BulkSeedCatalog.SeedStore;
import com.aatlas.bulk.internal.BulkSeedCatalog.SeedSupplier;
import com.aatlas.common.seed.Seeded;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code TODO(merge): replace with the buy module's public BuyIntel reader.}
 *
 * <p>What is a real port here: given a supplier's landed cost, {@link #evaluate} reproduces
 * {@code src/lib/intel/buy.ts}'s {@code evaluate()} exactly - the reliability, lead-time,
 * quality, fulfilment, MOQ and commercial-terms adjustments that turn a landed cost into
 * an effective one, and the risk call - reading each supplier's real seeded facts
 * (country, lead time, OTIF, defect rate, commercial terms) from {@code seed/suppliers.json}.
 *
 * <p>What is simplified: the real {@code getBuyIntel} derives a supplier's landed cost
 * (ex-works, freight, duty) from {@code buildBuyRecommendation}'s full freight-lane model
 * (mode, gateway, inbound/duty percent by country, inland percent and days by region -
 * {@code src/lib/platform/api.ts} plus {@code logistics.ts}). That model is the buy
 * module's, not reproduced here; {@link #syntheticQuote} instead derives an ex-works cost
 * from the supplier's seeded price index and a flat, honestly-labelled freight/duty
 * estimate (domestic vs. import). The target cost the real engine reaches through a
 * negotiation-benchmark chain is approximated the same way the chain's own note describes
 * it - "best observed plus a third of the gap to the benchmark" - computed directly from
 * this reader's own supplier panel.
 */
@Component
public class BuyLineReaderImpl implements BuyLineReader {

    private final BulkPricingEngine pricing;
    private final BulkSeedCatalog catalog;

    public BuyLineReaderImpl(BulkPricingEngine pricing, BulkSeedCatalog catalog) {
        this.pricing = pricing;
        this.catalog = catalog;
    }

    @Override
    public BuyLine read(String itemNumber, String regionKey, int qty) {
        BulkSeedCatalog.SeedProduct product = catalog.product(itemNumber).orElse(null);
        String destination = primaryStoreForRegion(regionKey);
        // Buy-side priceability is not gated by the sell-side tenantsSellingItem check: a
        // regional buy does not require that this specific landing branch already has
        // retail sales history for the item, only that the product itself is a real,
        // purchasable SKU (hasSales !== false, i.e. not PIM-only). Confirmed against the
        // golden basket: 3 of golden/bulk.json's 7 bulkBuyPlan items sell-side-fail
        // tenantsSellingItem at store 100959 yet are still priceable buys in the TypeScript.
        boolean priceable = product != null && product.sellable();
        String name = product != null ? product.shortName() : itemNumber;

        if (!priceable) {
            return new BuyLine(itemNumber, name, false, regionKey, BuyMath.regionLabel(regionKey), destination,
                    StoreLabels.of(catalog, destination), Math.max(1, qty), 0, 0, 0, 0, 0, List.of(), null, null,
                    null);
        }

        double cost = pricing.baseCost(itemNumber);
        int effectiveQty = Math.max(1, qty);
        double annualVolume = BuyMath.annualVolumeFor(itemNumber, regionKey, cost);

        List<SupplierEval> evaluated = new ArrayList<>();
        List<SeedSupplier> suppliers = catalog.suppliers();
        List<String> supplierIds = suppliers.stream().map(s -> s.record().id()).toList();
        String incumbentId = Seeded.pick(itemNumber, "incumbent", supplierIds);
        for (SeedSupplier s : suppliers) {
            evaluated.add(evaluate(s, itemNumber, cost, effectiveQty, s.record().id().equals(incumbentId)));
        }

        List<SupplierEval> acceptable = evaluated.stream().filter(s -> s.otifPct() >= 80).toList();
        SupplierEval best = (acceptable.isEmpty() ? evaluated : acceptable).stream()
                .min(Comparator.comparingDouble(SupplierEval::effective)).orElseThrow();

        List<SupplierEval> ranked = evaluated.stream()
                .map(s -> withRecommended(s, s.supplierId().equals(best.supplierId())))
                .sorted(Comparator.comparingDouble(SupplierEval::effective))
                .toList();

        SupplierEval incumbent = ranked.stream().filter(SupplierEval::isIncumbent).findFirst().orElse(ranked.get(0));
        SupplierEval recommendedSupplier = ranked.stream().filter(SupplierEval::recommended).findFirst()
                .orElse(ranked.get(0));
        SupplierEval cheapestQuoted = ranked.stream().min(Comparator.comparingDouble(SupplierEval::landed))
                .orElseThrow();

        List<Double> landedSorted = ranked.stream().map(SupplierEval::landed).sorted().toList();
        double marketLow = landedSorted.get(0);
        double marketMedian = landedSorted.get(landedSorted.size() / 2);
        double targetCost = BulkPricingEngine.round2(marketLow + (marketMedian - marketLow) / 3);
        double currentCost = incumbent.landed();
        double savingPerUnit = BulkPricingEngine.round2(Math.max(0, currentCost - targetCost));
        double savingPct = BulkPricingEngine.round1(savingPerUnit / Math.max(0.01, currentCost) * 100);

        return new BuyLine(itemNumber, name, true, regionKey, BuyMath.regionLabel(regionKey), destination,
                StoreLabels.of(catalog, destination), effectiveQty, currentCost, targetCost, savingPerUnit,
                savingPct, annualVolume, ranked, incumbent, recommendedSupplier, cheapestQuoted);
    }

    /** The busiest branch in a region: where a regional buy is landed for costing. */
    private String primaryStoreForRegion(String regionKey) {
        List<SeedStore> inRegion = catalog.storesInRegion(regionKey);
        return inRegion.stream()
                .max(Comparator.comparingInt(s -> s.txns() == null ? 0 : s.txns()))
                .map(SeedStore::storeId)
                .or(() -> catalog.stores().stream().findFirst().map(SeedStore::storeId))
                .orElse(regionKey);
    }

    /** Ex-works cost, freight and duty for one supplier quoting one item. Simplified - see class doc. */
    private record SyntheticQuote(double exWorksCost, double landed, double freightAndDuty, int totalLeadDays) {
    }

    private SyntheticQuote syntheticQuote(SeedSupplier s, String itemNumber, double cost) {
        String key = "sup:" + s.record().id() + ":" + itemNumber;
        boolean domestic = isDomestic(s.record().country());
        double exWorksCost = BulkPricingEngine.round2(cost * (s.record().priceIndex() / 100));
        double freightPct = domestic ? Seeded.randRange(key, "freight", 1, 4) : Seeded.randRange(key, "freight", 3, 9);
        double dutyPct = domestic ? 0 : Seeded.randRange(key, "duty", 0, 12);
        double freightCost = BulkPricingEngine.round2(exWorksCost * freightPct / 100);
        double dutyCost = BulkPricingEngine.round2(exWorksCost * dutyPct / 100);
        double landed = BulkPricingEngine.round2(exWorksCost + freightCost + dutyCost);
        return new SyntheticQuote(exWorksCost, landed, BulkPricingEngine.round2(freightCost + dutyCost),
                s.record().leadTimeDays());
    }

    private static boolean isDomestic(String country) {
        String c = country == null ? "" : country.trim().toLowerCase(java.util.Locale.ROOT);
        return c.equals("usa") || c.equals("us") || c.equals("united states");
    }

    /** Exact port of {@code buy.ts}'s {@code evaluate()}, given a landed quote. */
    private SupplierEval evaluate(SeedSupplier s, String itemNumber, double cost, int qty, boolean isIncumbent) {
        String supplierKey = "sup:" + s.record().id();
        String key = supplierKey + ":" + itemNumber;
        SyntheticQuote quote = syntheticQuote(s, itemNumber, cost);
        double landed = quote.landed();
        double otifPct = s.record().otifPct();
        double defectPct = s.profile() != null ? s.profile().defectPct() : 1.0;
        double fulfilmentPct = BulkPricingEngine.round1(Seeded.randRange(supplierKey, "fulfil", 87, 99.4));
        CommercialTerms commercial = new CommercialTerms(
                s.terms().creditDays(), s.terms().termsLabel(), s.terms().earlyPayDiscountPct(),
                s.terms().earlyPayDays(), s.terms().latePenaltyPctPerWeek(), s.terms().latePenaltyCapPct(),
                s.terms().warrantyMonths(), s.terms().quoteValidityDays(), s.terms().incoterm(),
                s.terms().invoiceAccuracyPct(), s.terms().capacityUnitsMonth());

        int moqBase = s.record().moq();
        boolean meetsMoq = qty >= moqBase;
        int relationshipYears = isIncumbent
                ? Seeded.randInt(supplierKey, "years", 3, 11)
                : Seeded.randInt(supplierKey, "years", 0, 2);

        double reliability = BulkPricingEngine.round2(landed * ((100 - otifPct) / 100) * 0.55);
        double leadTime = BulkPricingEngine.round2(landed * quote.totalLeadDays() * 0.0006);
        double quality = BulkPricingEngine.round2(landed * (defectPct / 100) * 1.5);
        double fulfilment = BulkPricingEngine.round2(landed * ((100 - fulfilmentPct) / 100) * 0.3);
        double moqAdj = meetsMoq ? 0 : BulkPricingEngine.round2(landed * 0.04);
        double credit = BulkPricingEngine.round2(-TermsMath.creditValuePerUnit(landed, commercial.creditDays()));
        double earlyPay = BulkPricingEngine.round2(-TermsMath.earlyPayNetPerUnit(landed, commercial));
        double penaltyRecoveryPerUnit = TermsMath.penaltyRecoveryCapPerUnit(landed, commercial);
        double penalty = BulkPricingEngine.round2(
                -((100 - otifPct) / 100) * Math.min(penaltyRecoveryPerUnit, landed * 0.55));

        List<SupplierEval.Adjustment> adjustments = new ArrayList<>();
        adjustments.add(new SupplierEval.Adjustment("On-time delivery " + Math.round(otifPct) + "%", reliability));
        adjustments.add(new SupplierEval.Adjustment(quote.totalLeadDays() + "-day lead time", leadTime));
        adjustments.add(new SupplierEval.Adjustment(String.format(java.util.Locale.ROOT, "%.1f%% defects", defectPct),
                quality));
        adjustments.add(new SupplierEval.Adjustment("Fulfilment " + Math.round(fulfilmentPct) + "%", fulfilment));
        if (!meetsMoq) {
            adjustments.add(new SupplierEval.Adjustment("Below MOQ of " + moqBase, moqAdj));
        }
        if (credit != 0) {
            adjustments.add(new SupplierEval.Adjustment(commercial.creditDays() + " days credit", credit));
        }
        if (earlyPay != 0) {
            adjustments.add(new SupplierEval.Adjustment(
                    commercial.earlyPayDiscountPct() + "% early-pay discount, net", earlyPay));
        }
        if (penalty != 0) {
            adjustments.add(new SupplierEval.Adjustment(
                    "Late clause, capped at " + commercial.latePenaltyCapPct() + "%", penalty));
        }

        double effective = BulkPricingEngine.round2(
                landed + reliability + leadTime + quality + fulfilment + moqAdj + credit + earlyPay + penalty);

        String risk = (otifPct < 82 || defectPct > 2.5) ? "High" : (otifPct < 90 || quote.totalLeadDays() > 40)
                ? "Medium" : "Low";
        String riskNote = switch (risk) {
            case "High" -> otifPct < 82
                    ? "Misses " + Math.round(100 - otifPct) + "% of delivery dates"
                    : String.format(java.util.Locale.ROOT, "%.1f%% defect rate", defectPct);
            case "Medium" -> quote.totalLeadDays() > 40
                    ? quote.totalLeadDays() + " days order to dock"
                    : Math.round(otifPct) + "% on time";
            default -> "Reliable";
        };

        return new SupplierEval(s.record().id(), s.record().name(), s.record().country(), quote.exWorksCost(),
                landed, effective, quote.freightAndDuty(), quote.totalLeadDays(), otifPct, defectPct, fulfilmentPct,
                commercial.termsLabel(), commercial, penaltyRecoveryPerUnit, moqBase, meetsMoq, relationshipYears,
                adjustments, isIncumbent, false, risk, riskNote);
    }

    private static SupplierEval withRecommended(SupplierEval s, boolean recommended) {
        return new SupplierEval(s.supplierId(), s.name(), s.country(), s.quoted(), s.landed(), s.effective(),
                s.freightAndDuty(), s.leadDays(), s.otifPct(), s.defectPct(), s.fulfilmentPct(), s.terms(),
                s.commercial(), s.penaltyRecoveryPerUnit(), s.moq(), s.meetsMoq(), s.relationshipYears(),
                s.adjustments(), s.isIncumbent(), recommended, s.risk(), s.riskNote());
    }
}
