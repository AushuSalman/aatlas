package com.aatlas.buy.internal;

import com.aatlas.buy.BuyIntel;
import com.aatlas.buy.BuyIntelReader;
import com.aatlas.buy.BuyNowVsWait;
import com.aatlas.buy.BuyRecommendation;
import com.aatlas.buy.ChainStep;
import com.aatlas.buy.CommercialTerms;
import com.aatlas.buy.Negotiation;
import com.aatlas.buy.SupplierEval;
import com.aatlas.buy.SupplierQuote;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.seed.Seeded;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Buy-side intelligence: what to pay, who to buy from, whether to buy now, and what to say
 * to the supplier. A port of {@code getBuyIntel} (and {@code primaryStoreForRegion}, {@code
 * regionLabel}, {@code annualVolumeFor}) in the frontend's {@code intel/buy.ts}.
 */
@Component
public class BuyIntelEngine implements BuyIntelReader {

    private final CatalogGateway catalog;
    private final BuyRecommendationEngine recommendationEngine;
    private final PricingStandIn pricing;
    private final SupplierGateway suppliers;

    BuyIntelEngine(CatalogGateway catalog, BuyRecommendationEngine recommendationEngine, PricingStandIn pricing,
            SupplierGateway suppliers) {
        this.catalog = catalog;
        this.recommendationEngine = recommendationEngine;
        this.pricing = pricing;
        this.suppliers = suppliers;
    }

    /**
     * The stored reject rate for a supplier, or the seeded one when this supplier is not on
     * the item's panel.
     *
     * <p>A linear scan of at most a handful of rows rather than a map: {@code
     * ArchitectureRulesTest.noFloatingPointMoney} forbids this package from depending on
     * {@code java.lang.Double}, which any {@code Map<String, Double>} would box into.
     */
    private static double defectPctFor(String supplierId, List<SupplierGateway.SupplierRow> panel) {
        for (SupplierGateway.SupplierRow s : panel) {
            if (s.id().equals(supplierId)) {
                return s.defectPct();
            }
        }
        return RatingEngine.seededDefect(supplierId);
    }

    /** The busiest branch in a market region - where a regional buy is landed for costing. */
    String primaryStoreForRegion(String country, String regionKey) {
        return catalog.primaryStoreForRegion(regionKey).map(CatalogGateway.StoreRow::storeCode)
                .orElseGet(() -> catalog.firstStoreCodeInSeedOrder(country));
    }

    String regionLabel(String country, String regionKey) {
        return catalog.marketRegion(country, regionKey).map(CatalogGateway.MarketRegion::shortLabel)
                .orElse(regionKey);
    }

    /** The volume a region buys of an item in a year - the base for every bulk and annual figure. */
    static int annualVolumeFor(String itemNumber, String regionKey, double cost) {
        String key = "avol:" + itemNumber + ":" + regionKey;
        if (cost < 15) {
            return Seeded.randInt(key, "v", 9000, 62000);
        }
        if (cost < 200) {
            return Seeded.randInt(key, "v", 1200, 11000);
        }
        return Seeded.randInt(key, "v", 60, 520);
    }

    static String confidenceLabel(int n) {
        return n >= 85 ? "High" : n >= 70 ? "Medium" : "Low";
    }

    private SupplierEval evaluate(SupplierQuote q, String itemNumber, double cost, double qty,
            List<SupplierGateway.SupplierRow> panel) {
        String key = "sup:" + q.supplierId() + ":" + itemNumber;
        // The reject rate the row carries, not a hash of the id: for a CSV-imported supplier
        // that is the rate the file gave. Identical for a seeded one, whose stored figure
        // came from this same hash.
        double defectPct = defectPctFor(q.supplierId(), panel);
        double fulfilmentPct = Js.round1(Seeded.randRange("sup:" + q.supplierId(), "fulfil", 87, 99.4));
        CommercialTerms commercial = TermsEngine.commercialTerms(q.supplierId(), q.country());
        String terms = commercial.termsLabel();
        // Keyed off the item's own base cost (same for every supplier), not the supplier's own
        // landed unit cost - matches the frontend's `evaluate(q, itemNumber, cost, qty)`.
        int moqBase = cost < 15
                ? Seeded.randInt(key, "moq", 200, 2500)
                : cost < 200 ? Seeded.randInt(key, "moq", 25, 400) : Seeded.randInt(key, "moq", 2, 12);
        boolean meetsMoq = qty >= moqBase;
        int relationshipYears = q.isIncumbent()
                ? Seeded.randInt("sup:" + q.supplierId(), "years", 3, 11)
                : Seeded.randInt("sup:" + q.supplierId(), "years", 0, 2);

        double reliability = Js.round2(q.unitCost() * ((100 - q.otifPct()) / 100.0) * 0.55);
        double leadTime = Js.round2(q.unitCost() * q.totalLeadDays() * 0.0006);
        double quality = Js.round2(q.unitCost() * (defectPct / 100.0) * 1.5);
        double fulfilment = Js.round2(q.unitCost() * ((100 - fulfilmentPct) / 100.0) * 0.3);
        double moq = meetsMoq ? 0 : Js.round2(q.unitCost() * 0.04);
        double credit = Js.round2(-TermsEngine.creditValuePerUnit(q.unitCost(), commercial.creditDays()));
        double earlyPay = Js.round2(-TermsEngine.earlyPayNetPerUnit(q.unitCost(), commercial));
        double penaltyRecoveryPerUnit = TermsEngine.penaltyRecoveryCapPerUnit(q.unitCost(), commercial);
        double penalty = Js.round2(
                -((100 - q.otifPct()) / 100.0) * Math.min(penaltyRecoveryPerUnit, q.unitCost() * 0.55));

        List<SupplierEval.Adjustment> adjustments = new ArrayList<>();
        adjustments.add(new SupplierEval.Adjustment("On-time delivery " + Js.toFixed(q.otifPct(), 0) + "%", reliability));
        adjustments.add(new SupplierEval.Adjustment(q.totalLeadDays() + "-day lead time", leadTime));
        adjustments.add(new SupplierEval.Adjustment(Js.toFixed(defectPct, 1) + "% defects", quality));
        adjustments.add(new SupplierEval.Adjustment("Fulfilment " + Js.toFixed(fulfilmentPct, 0) + "%", fulfilment));
        if (!meetsMoq) {
            adjustments.add(new SupplierEval.Adjustment("Below MOQ of " + Js.localeInt(moqBase), moq));
        }
        if (credit != 0) {
            adjustments.add(new SupplierEval.Adjustment(commercial.creditDays() + " days credit", credit));
        }
        if (earlyPay != 0) {
            adjustments.add(new SupplierEval.Adjustment(
                    Js.num(commercial.earlyPayDiscountPct()) + "% early-pay discount, net", earlyPay));
        }
        if (penalty != 0) {
            adjustments.add(new SupplierEval.Adjustment(
                    "Late clause, capped at " + Js.num(commercial.latePenaltyCapPct()) + "%", penalty));
        }
        double effective = Js.round2(q.unitCost() + reliability + leadTime + quality + fulfilment + moq + credit
                + earlyPay + penalty);

        String risk = q.otifPct() < 82 || defectPct > 2.5 ? "High"
                : q.otifPct() < 90 || q.totalLeadDays() > 40 ? "Medium" : "Low";
        String riskNote = "High".equals(risk)
                ? (q.otifPct() < 82
                        ? "Misses " + Js.toFixed(100 - q.otifPct(), 0) + "% of delivery dates"
                        : Js.toFixed(defectPct, 1) + "% defect rate")
                : "Medium".equals(risk)
                        ? (q.totalLeadDays() > 40
                                ? q.totalLeadDays() + " days order to dock"
                                : Js.toFixed(q.otifPct(), 0) + "% on time")
                        : "Reliable";

        return new SupplierEval(
                q.supplierId(), q.name(), q.country(), q.exWorksCost(), q.unitCost(), effective,
                Js.round2(q.freightCost() + q.dutyCost()), q.totalLeadDays(), q.otifPct(), defectPct, fulfilmentPct,
                terms, commercial, penaltyRecoveryPerUnit, moqBase, meetsMoq, relationshipYears, adjustments,
                q.isIncumbent(), false, risk, riskNote);
    }

    @Override
    public BuyIntel getBuyIntel(String itemNumber, String regionKey, int qty, String destinationId) {
        CatalogGateway.ProductRow product = catalog.findProduct(itemNumber)
                .orElseThrow(() -> ApiException.notFound("Product", itemNumber));

        String destination = destinationId != null && !destinationId.isBlank()
                ? destinationId
                : primaryStoreForRegion(catalog.tenantCountry().orElse("US"), regionKey);
        CatalogGateway.StoreRow destinationStore = catalog.findStore(destination)
                .orElseThrow(() -> ApiException.notFound("Store", destination));

        BuyRecommendation rec = recommendationEngine.build(itemNumber, destination, null);
        // `MARKET_REGIONS.find((r) => r.key === regionKey) ?? marketRegionForStore(destination)`:
        // an unrecognised regionKey falls back to the destination's own market region, not to
        // the raw key string (that raw-key fallback is `regionLabel()`'s own contract, used
        // elsewhere - see its Javadoc).
        String regionLabelResolved = catalog.marketRegion(destinationStore.country(), regionKey)
                .or(() -> catalog.marketRegion(destinationStore.country(), destinationStore.regionKey()))
                .map(CatalogGateway.MarketRegion::shortLabel)
                .orElse(destinationStore.regionKey());

        PricingStandIn.Model m = pricing.modelFor(itemNumber, destination);
        double cost = m.cost();
        int annualVolume = annualVolumeFor(itemNumber, regionKey, cost);
        double effectiveQty = Math.max(1, qty);

        List<SupplierGateway.SupplierRow> panel = suppliers.panelFor(itemNumber);
        List<SupplierEval> evaluated =
                rec.quotes().stream().map(q -> evaluate(q, itemNumber, cost, effectiveQty, panel)).toList();
        List<SupplierEval> acceptable = evaluated.stream().filter(s -> s.otifPct() >= 80).toList();
        List<SupplierEval> pool = acceptable.isEmpty() ? evaluated : acceptable;
        SupplierEval best = pool.stream().min(Comparator.comparingDouble(SupplierEval::effective)).orElseThrow();

        List<SupplierEval> suppliers = evaluated.stream()
                .map(s -> withRecommended(s, s.supplierId().equals(best.supplierId())))
                .sorted(Comparator.comparingDouble(SupplierEval::effective))
                .toList();
        SupplierEval incumbent = suppliers.stream().filter(SupplierEval::isIncumbent).findFirst()
                .orElse(suppliers.get(0));
        SupplierEval recommendedSupplier = suppliers.stream().filter(SupplierEval::recommended).findFirst()
                .orElse(suppliers.get(0));
        SupplierEval cheapestQuoted = suppliers.stream().min(Comparator.comparingDouble(SupplierEval::landed))
                .orElse(suppliers.get(0));

        // A plain left-to-right sum, not DoubleStream.average() (which compensates for
        // floating-point error internally, JDK 8+) - the frontend's `reduce((a, b) => a + b,
        // 0)` does not, and the golden files pin the exact double this naive order produces.
        double landedSum = 0;
        for (SupplierEval s : suppliers) {
            landedSum += s.landed();
        }
        double supplierAverage = Js.round2(landedSum / Math.max(1, suppliers.size()));
        double currentCost = incumbent.landed();
        double targetCost = rec.targetCost();
        double sellPrice = Js.round2(Math.max(0, m.currentPrice()));
        double marginNowPct = sellPrice > 0 ? Js.round1(((sellPrice - currentCost) / sellPrice) * 100) : 0;
        double savingPerUnit = Js.round2(Math.max(0, currentCost - targetCost));

        String reason = cheapestQuoted.supplierId().equals(recommendedSupplier.supplierId())
                ? recommendedSupplier.name()
                        + " is both the lowest landed cost and the lowest all-in cost. Nothing hidden behind the quote."
                : cheapestQuoted.name() + " is cheaper on paper at " + Js.fmtMoney(cheapestQuoted.landed())
                        + " landed, but " + cheapestQuoted.riskNote().toLowerCase() + " and "
                        + cheapestQuoted.leadDays() + " days order to dock make it "
                        + Js.fmtMoney(cheapestQuoted.effective()) + " all-in. " + recommendedSupplier.name()
                        + " lands at " + Js.fmtMoney(recommendedSupplier.landed()) + " and stays there.";

        int confidence = (int) Math.round(Math.min(96,
                68 + Math.min(16, suppliers.size() * 2) + (recommendedSupplier.otifPct() >= 90 ? 6 : 0)
                        + Seeded.rand("bconf:" + itemNumber + ":" + regionKey) * 6));

        List<ChainStep> chain = new ArrayList<>();
        chain.add(new ChainStep("quote", incumbent.name() + " quote", Js.fmtMoney(incumbent.quoted()), null,
                "Ex-works " + incumbent.country() + ". What you are quoted today.", incumbent.quoted()));
        chain.add(new ChainStep("lane", "Freight and duty into " + regionLabelResolved,
                "+" + Js.fmtMoney(incumbent.freightAndDuty()), Js.signedMoney(incumbent.landed() - incumbent.quoted()),
                rec.lane().routeNote(), incumbent.landed()));
        chain.add(new ChainStep("panel", "Supplier panel, landed here",
                Js.fmtMoney(rec.marketLow()) + Js.EN_DASH + Js.fmtMoney(rec.marketHigh()), null,
                suppliers.size() + " suppliers can serve this item into " + BuyRecommendationEngine.storeLabel(destinationStore) + ".",
                incumbent.landed()));
        chain.add(new ChainStep("benchmark", "Market benchmark", Js.fmtMoney(rec.marketMedian()),
                Js.signedMoney(rec.marketMedian() - incumbent.landed()), "The middle of the panel. Where a fair price sits.",
                rec.marketMedian()));
        chain.add(new ChainStep("best", "Best observed", Js.fmtMoney(rec.marketLow()), null,
                "The lowest anyone actually lands this item here for. The target is never set below it.",
                rec.marketLow()));
        chain.add(new ChainStep("target", "Target cost", Js.fmtMoney(targetCost),
                Js.signedMoney(targetCost - rec.marketMedian()),
                "Best observed plus a third of the gap to the benchmark: achievable, not theoretical.", targetCost));
        ChainStep finalStep = new ChainStep("final", "Target cost", Js.fmtMoney(targetCost),
                Js.signedMoney(targetCost - currentCost), "Against the " + Js.fmtMoney(currentCost) + " you land today.",
                targetCost);

        // -- Buy now or wait? -----------------------------------------------------------------
        CatalogGateway.CommodityTrend commodity = catalog.commodityTrend(product.commodity())
                .orElse(new CatalogGateway.CommodityTrend(0, "No commodity exposure"));
        double drift30 = Js.round1(commodity.pct90() * 0.38
                + Seeded.randRange("bfc:" + itemNumber + ":" + incumbent.supplierId(), "d", -0.9, 1.4));
        double cost30 = Js.round2(currentCost * (1 + drift30 / 100));
        double deltaPerUnit = Js.round2(cost30 - currentCost);
        boolean rising = drift30 > 0.8;
        boolean falling = drift30 < -0.8;
        String waitRisk = rising ? (drift30 > 2 ? "High" : "Medium")
                : falling ? (incumbent.leadDays() > 30 ? "Medium" : "Low") : "Low";
        String nowVsWaitReason = rising
                ? commodity.label() + ": the same order is expected to cost " + Js.fmtMoney(Math.abs(deltaPerUnit))
                        + " more a unit in 30 days. Lock the price now."
                : falling
                        ? commodity.label() + ": waiting 30 days is expected to save "
                                + Js.fmtMoney(Math.abs(deltaPerUnit)) + " a unit, with " + incumbent.leadDays()
                                + " days of lead time to cover from stock."
                        : "No meaningful move expected in the next 30 days. Buy on need, negotiate on price.";
        BuyNowVsWait nowVsWait = new BuyNowVsWait(falling ? "wait" : "now", nowVsWaitReason,
                new BuyNowVsWait.Now(currentCost, Js.round2(currentCost * effectiveQty)),
                new BuyNowVsWait.Wait(30, cost30, deltaPerUnit, Js.round2(deltaPerUnit * effectiveQty), waitRisk),
                commodity.label());

        // -- Negotiation ------------------------------------------------------------------------
        double gapPct = currentCost != 0 ? Js.round1(((currentCost - targetCost) / currentCost) * 100) : 0;
        double annualSavings = Js.round2(savingPerUnit * annualVolume);
        List<String> levers = List.of(
                Js.localeInt(annualVolume) + " units a year into " + regionLabelResolved,
                "Panel benchmark " + Js.fmtMoney(rec.marketMedian()) + " landed, best " + Js.fmtMoney(rec.marketLow()),
                incumbent.relationshipYears() >= 3
                        ? incumbent.relationshipYears() + "-year relationship, " + Js.toFixed(incumbent.otifPct(), 0)
                                + "% on time"
                        : "Newer relationship - volume commitment is the lever",
                "Net 30".equals(incumbent.terms()) ? "Offer Net 45 in exchange for price"
                        : "Currently on " + incumbent.terms());
        String message = String.join("\n",
                "Subject: " + product.shortName() + " — pricing for the next 12 months",
                "",
                "Hi " + incumbent.name() + " team,",
                "",
                "We've reviewed our " + product.shortName() + " spend into " + regionLabelResolved
                        + ". Over the last twelve months we bought " + Js.localeInt(annualVolume)
                        + " units from you at " + Js.fmtMoney(incumbent.quoted()) + " ex-works ("
                        + Js.fmtMoney(currentCost) + " landed), while comparable supply into the same branches now "
                        + "lands at " + Js.fmtMoney(rec.marketMedian()) + ", and the best offer we hold is "
                        + Js.fmtMoney(rec.marketLow()) + ".",
                "",
                "We'd rather keep this volume with you"
                        + (incumbent.relationshipYears() >= 3
                                ? " — " + incumbent.relationshipYears() + " years and "
                                        + Js.toFixed(incumbent.otifPct(), 0) + "% on-time delivery count for a lot"
                                : "")
                        + " — but we need to bring the landed cost to " + Js.fmtMoney(targetCost)
                        + ". At current volume that is a " + Js.fmtMoney(annualSavings, 0) + " a year gap we can't carry.",
                "",
                "Can you confirm " + Js.fmtMoney(targetCost) + " on the next release? If it helps get there, we're "
                        + "open to a twelve-month volume commitment"
                        + ("Net 30".equals(incumbent.terms()) ? " or moving to Net 45" : "") + ".",
                "",
                "Thanks,");
        Negotiation negotiation = new Negotiation(incumbent.name(), currentCost, rec.marketMedian(), annualVolume,
                targetCost, annualSavings, gapPct, incumbent.relationshipYears(), incumbent.leadDays(),
                incumbent.otifPct(), message, levers);

        return new BuyIntel(
                itemNumber, product.shortName(), rec.description(), rec.priceable(), regionKey, regionLabelResolved,
                destination, BuyRecommendationEngine.storeLabel(destinationStore), (int) effectiveQty,
                currentCost, targetCost, supplierAverage, rec.marketMedian(), rec.marketLow(), sellPrice,
                marginNowPct, savingPerUnit, Js.round1((savingPerUnit / Math.max(0.01, currentCost)) * 100),
                Js.round2(savingPerUnit * effectiveQty), confidence, confidenceLabel(confidence),
                suppliers, incumbent, recommendedSupplier, cheapestQuoted, reason,
                chain, finalStep, nowVsWait, negotiation, annualVolume);
    }

    private static SupplierEval withRecommended(SupplierEval s, boolean recommended) {
        return new SupplierEval(s.supplierId(), s.name(), s.country(), s.quoted(), s.landed(), s.effective(),
                s.freightAndDuty(), s.leadDays(), s.otifPct(), s.defectPct(), s.fulfilmentPct(), s.terms(),
                s.commercial(), s.penaltyRecoveryPerUnit(), s.moq(), s.meetsMoq(), s.relationshipYears(),
                s.adjustments(), s.isIncumbent(), recommended, s.risk(), s.riskNote());
    }
}
