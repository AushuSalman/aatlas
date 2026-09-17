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
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.Catalogue;
import com.aatlas.history.PricingMath;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.Reference;
import com.aatlas.history.Suppliers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Buy-side intelligence: what to pay, who to buy from, whether to buy now, and what to say to
 * the supplier. Spec-A S2 "buy" (BuyIntelEngine/TermsEngine/RiskEngine/RatingEngine rows) and
 * S3.5/S3.6. Every supplier in {@link BuyIntel#suppliers()} is listed even without a quote
 * (scored on lead time and OTIF alone), but {@link SupplierEval#hasQuote()} says whether it
 * can ever be awarded.
 */
@Component
public class BuyIntelEngine implements BuyIntelReader {

    private final Catalogue catalogue;
    private final CatalogGateway catalog;
    private final BuyRecommendationEngine recommendationEngine;
    private final SupplierGateway supplierGateway;
    private final PurchaseHistory purchases;
    private final Reference reference;
    private final AatlasClock clock;

    BuyIntelEngine(Catalogue catalogue, CatalogGateway catalog, BuyRecommendationEngine recommendationEngine,
            SupplierGateway supplierGateway, PurchaseHistory purchases, Reference reference, AatlasClock clock) {
        this.catalogue = catalogue;
        this.catalog = catalog;
        this.recommendationEngine = recommendationEngine;
        this.supplierGateway = supplierGateway;
        this.purchases = purchases;
        this.reference = reference;
        this.clock = clock;
    }

    /** The busiest branch in a market region - where a regional buy is landed for costing. */
    String primaryStoreForRegion(String regionKey) {
        return catalog.primaryStoreForRegion(regionKey).map(CatalogGateway.StoreRow::storeCode)
                .orElseGet(() -> catalog.stores().stream().findFirst().map(CatalogGateway.StoreRow::storeCode)
                        .orElseThrow(() -> ApiException.notFound("Store", "(none for tenant)")));
    }

    static String confidenceLabel(int n) {
        return n >= 85 ? "High" : n >= 70 ? "Medium" : "Low";
    }

    private SupplierEval evaluate(SupplierQuote q, BigDecimal qty, LocalDate today, List<SupplierGateway.Quote> links) {
        SupplierGateway.SupplierRow row = supplierGateway.supplier(q.supplierId()).orElse(null);
        boolean hasQuote = q.unitCost() != null;

        Optional<Suppliers.Terms> termsOpt = supplierGateway.terms(q.supplierId());
        CommercialTerms commercial = TermsEngine.commercialTerms(termsOpt.orElse(null));

        PurchaseHistory.SupplierPurchases supplierHistory = purchases.supplier(q.supplierId(), today);
        BigDecimal fulfilmentPct = supplierHistory.w12().received() >= 5 ? supplierHistory.w12().inFullPct() : null;

        Integer moq = links.stream().filter(l -> l.supplier().id().equals(q.supplierId())).findFirst()
                .map(SupplierGateway.Quote::moq).orElse(null);
        if (moq == null) {
            moq = termsOpt.map(Suppliers.Terms::moq).orElse(null);
        }
        Boolean meetsMoq = moq == null ? null : qty.compareTo(BigDecimal.valueOf(moq)) >= 0;

        Integer relationshipYears = null;
        LocalDate since = supplierHistory.allTime().firstOrder() != null ? supplierHistory.allTime().firstOrder()
                : (row != null ? row.since() : null);
        if (since != null) {
            relationshipYears = (int) (ChronoUnit.DAYS.between(since, today) / 365.25);
        }

        BigDecimal reliability = null;
        BigDecimal leadTimeAdj = null;
        BigDecimal quality = null;
        BigDecimal fulfilmentAdj = null;
        BigDecimal moqAdj = null;
        BigDecimal credit = null;
        BigDecimal earlyPay = null;
        BigDecimal penalty = null;
        BigDecimal penaltyRecoveryPerUnit = TermsEngine.penaltyRecoveryCapPerUnit(q.unitCost(), commercial);
        List<SupplierEval.Adjustment> adjustments = new ArrayList<>();
        BigDecimal effective = null;

        if (hasQuote) {
            BigDecimal landed = q.unitCost();
            if (q.otifPct() != null) {
                reliability = landed.multiply(HUNDRED.subtract(q.otifPct())).divide(HUNDRED, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("0.55")).setScale(4, RoundingMode.HALF_UP);
                adjustments.add(new SupplierEval.Adjustment(
                        "On-time delivery " + Js.toFixed(q.otifPct().doubleValue(), 0) + "%", reliability));
            }
            if (q.totalLeadDays() != null) {
                leadTimeAdj = landed.multiply(BigDecimal.valueOf(q.totalLeadDays())).multiply(new BigDecimal("0.0006"))
                        .setScale(4, RoundingMode.HALF_UP);
                adjustments.add(new SupplierEval.Adjustment(q.totalLeadDays() + "-day lead time", leadTimeAdj));
            }
            if (row != null && row.defectPct() != null) {
                quality = landed.multiply(row.defectPct()).divide(HUNDRED, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("1.5")).setScale(4, RoundingMode.HALF_UP);
                adjustments.add(new SupplierEval.Adjustment(Js.toFixed(row.defectPct().doubleValue(), 1) + "% defects", quality));
            }
            if (fulfilmentPct != null) {
                fulfilmentAdj = landed.multiply(HUNDRED.subtract(fulfilmentPct)).divide(HUNDRED, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("0.3")).setScale(4, RoundingMode.HALF_UP);
                adjustments.add(new SupplierEval.Adjustment(
                        "Fulfilment " + Js.toFixed(fulfilmentPct.doubleValue(), 0) + "%", fulfilmentAdj));
            }
            if (Boolean.FALSE.equals(meetsMoq)) {
                moqAdj = landed.multiply(new BigDecimal("0.04")).setScale(4, RoundingMode.HALF_UP);
                adjustments.add(new SupplierEval.Adjustment("Below MOQ of " + Js.localeInt(moq), moqAdj));
            }
            BigDecimal creditValue = TermsEngine.creditValuePerUnit(landed, commercial.creditDays());
            if (creditValue != null && creditValue.signum() != 0) {
                credit = creditValue.negate();
                adjustments.add(new SupplierEval.Adjustment(commercial.creditDays() + " days credit", credit));
            }
            BigDecimal earlyPayValue = TermsEngine.earlyPayNetPerUnit(landed, commercial);
            if (earlyPayValue != null && earlyPayValue.signum() != 0) {
                earlyPay = earlyPayValue.negate();
                adjustments.add(new SupplierEval.Adjustment(
                        Js.toFixed(commercial.earlyPayDiscountPct() == null ? 0 : commercial.earlyPayDiscountPct().doubleValue(), 1)
                                + "% early-pay discount, net", earlyPay));
            }
            if (q.otifPct() != null && penaltyRecoveryPerUnit.signum() != 0) {
                BigDecimal missPct = HUNDRED.subtract(q.otifPct()).divide(HUNDRED, 6, RoundingMode.HALF_UP);
                BigDecimal capped = penaltyRecoveryPerUnit.min(landed.multiply(new BigDecimal("0.55")));
                penalty = missPct.multiply(capped).negate().setScale(4, RoundingMode.HALF_UP);
                if (penalty.signum() != 0) {
                    adjustments.add(new SupplierEval.Adjustment(
                            "Late clause, capped at " + Js.toFixed(commercial.latePenaltyCapPct() == null ? 0
                                    : commercial.latePenaltyCapPct().doubleValue(), 1) + "%", penalty));
                }
            }
            effective = landed;
            for (SupplierEval.Adjustment a : adjustments) {
                effective = effective.add(a.amount());
            }
            effective = effective.setScale(4, RoundingMode.HALF_UP);
        }

        String riskLabel = null;
        String riskNote = "Not assessed";
        if (q.otifPct() != null || (row != null && row.defectPct() != null)) {
            double otif = q.otifPct() != null ? q.otifPct().doubleValue() : 100;
            double defect = row != null && row.defectPct() != null ? row.defectPct().doubleValue() : 0;
            Integer totalLead = q.totalLeadDays();
            if (otif < 82 || defect > 2.5) {
                riskLabel = "High";
                riskNote = otif < 82 ? "Misses " + Js.toFixed(100 - otif, 0) + "% of delivery dates"
                        : Js.toFixed(defect, 1) + "% defect rate";
            } else if (otif < 90 || (totalLead != null && totalLead > 40)) {
                riskLabel = "Medium";
                riskNote = totalLead != null && totalLead > 40 ? totalLead + " days order to dock"
                        : Js.toFixed(otif, 0) + "% on time";
            } else {
                riskLabel = "Low";
                riskNote = "Reliable";
            }
        }

        return new SupplierEval(q.supplierId(), q.name(), q.country(), q.exWorksCost(), q.unitCost(), effective,
                addNullable(q.freightCost(), q.dutyCost()), q.totalLeadDays(), q.otifPct(),
                row != null ? row.defectPct() : null, fulfilmentPct, commercial.termsLabel(), commercial,
                penaltyRecoveryPerUnit, moq, meetsMoq, relationshipYears, adjustments, q.isIncumbent(), false,
                riskLabel, riskNote, hasQuote, row != null ? row.holdsStock() : null);
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static BigDecimal addNullable(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? BigDecimal.ZERO : a).add(b == null ? BigDecimal.ZERO : b);
    }

    @Override
    public BuyIntel getBuyIntel(String itemNumber, String regionKey, int qty, String destinationId) {
        Catalogue.ProductRef product = catalogue.product(itemNumber)
                .orElseThrow(() -> ApiException.notFound("Product", itemNumber));

        String destination = destinationId != null && !destinationId.isBlank()
                ? destinationId : primaryStoreForRegion(regionKey);
        Catalogue.StoreRef destinationStore = catalogue.store(destination)
                .orElseThrow(() -> ApiException.notFound("Store", destination));
        LocalDate today = clock.today();

        BuyRecommendation rec = recommendationEngine.build(itemNumber, destination, null);
        String regionLabelResolved = catalog.marketRegion(destinationStore.country(), regionKey)
                .or(() -> catalog.marketRegion(destinationStore.country(), destinationStore.regionKey()))
                .map(CatalogGateway.MarketRegion::shortLabel)
                .orElse(destinationStore.regionKey());

        BigDecimal effectiveQty = BigDecimal.valueOf(Math.max(1, qty));
        List<SupplierGateway.Quote> links = supplierGateway.quotesFor(product.id());

        List<SupplierEval> evaluated = rec.quotes().stream()
                .map(q -> evaluate(q, effectiveQty, today, links))
                .toList();

        List<String> locked = new ArrayList<>(rec.locked());
        List<SupplierEval> suppliers;
        SupplierEval incumbent = null;
        SupplierEval recommendedSupplier = null;
        SupplierEval cheapestQuoted = null;
        String reason = null;

        List<SupplierEval> quotable = evaluated.stream().filter(SupplierEval::hasQuote).toList();
        if (!quotable.isEmpty()) {
            List<SupplierEval> acceptable = quotable.stream()
                    .filter(s -> s.otifPct() == null || s.otifPct().doubleValue() >= 80).toList();
            List<SupplierEval> pool = acceptable.isEmpty() ? quotable : acceptable;
            SupplierEval best = pool.stream().min(Comparator.comparing(SupplierEval::effective)).orElseThrow();
            List<SupplierEval> ranked = new ArrayList<>();
            for (SupplierEval s : evaluated) {
                ranked.add(s.hasQuote() && s.supplierId().equals(best.supplierId()) ? withRecommended(s, true) : s);
            }
            ranked = ranked.stream()
                    .sorted(Comparator.comparing((SupplierEval s) -> s.hasQuote() ? 0 : 1)
                            .thenComparing(s -> s.hasQuote() ? s.effective() : BigDecimal.ZERO))
                    .toList();
            suppliers = ranked;
            incumbent = suppliers.stream().filter(SupplierEval::isIncumbent).findFirst()
                    .orElse(quotable.get(0));
            recommendedSupplier = suppliers.stream().filter(SupplierEval::recommended).findFirst().orElse(incumbent);
            cheapestQuoted = quotable.stream().min(Comparator.comparing(SupplierEval::landed)).orElse(incumbent);
            reason = cheapestQuoted.supplierId().equals(recommendedSupplier.supplierId())
                    ? recommendedSupplier.name()
                            + " is both the lowest landed cost and the lowest all-in cost. Nothing hidden behind the quote."
                    : cheapestQuoted.name() + " is cheaper on paper at " + Js.fmtMoney(cheapestQuoted.landed().doubleValue())
                            + " landed, but " + (cheapestQuoted.riskNote() == null ? "carries more risk" : cheapestQuoted.riskNote().toLowerCase())
                            + " and " + (cheapestQuoted.leadDays() == null ? "an unknown lead time" : cheapestQuoted.leadDays() + " days order to dock")
                            + " make " + recommendedSupplier.name() + " the better call at "
                            + Js.fmtMoney(recommendedSupplier.landed().doubleValue()) + " all-in.";
        } else {
            suppliers = evaluated;
            if (!locked.contains("purchases")) {
                locked.add("purchases");
            }
        }

        BigDecimal currentCost = incumbent != null ? incumbent.landed() : rec.currentCost();
        BigDecimal targetCost = rec.targetCost();
        BigDecimal sellPrice = rec.sellPrice();
        BigDecimal marginNowPct = PricingMath.marginPct(sellPrice, currentCost);
        BigDecimal savingPerUnit = currentCost != null && targetCost != null && currentCost.compareTo(targetCost) > 0
                ? currentCost.subtract(targetCost).setScale(4, RoundingMode.HALF_UP) : (currentCost != null && targetCost != null
                        ? BigDecimal.ZERO : null);
        BigDecimal savingPct = PricingMath.pct(savingPerUnit, currentCost);

        BigDecimal supplierAverage = null;
        if (!quotable.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (SupplierEval s : quotable) {
                sum = sum.add(s.landed());
            }
            supplierAverage = sum.divide(BigDecimal.valueOf(quotable.size()), 4, RoundingMode.HALF_UP);
        }

        // annualVolume mirrors BuyRecommendation's own annualUnits for this (item, destination) -
        // one number, everywhere it appears (see AnnualUnits' javadoc).
        Integer annualVolume = rec.annualUnits();

        BigDecimal potentialSavings = savingPerUnit != null && annualVolume != null
                ? savingPerUnit.multiply(BigDecimal.valueOf(annualVolume)).setScale(2, RoundingMode.HALF_UP) : null;

        boolean incumbentFromPurchases = rec.sources().getOrDefault("incumbentCost", "").startsWith("observed");
        boolean threeObservedPos = incumbent != null && "observed-90d".equals(rec.sources().get("incumbentCost"))
                || (incumbent != null && "observed-12m".equals(rec.sources().get("incumbentCost")));
        boolean unitsFromPurchases = "purchases".equals(rec.sources().get("annualUnits"));
        double confidenceRaw = 60 + (incumbentFromPurchases ? 10 : 0) + Math.min(15, quotable.size() * 5.0)
                + (threeObservedPos ? 10 : 0) + (unitsFromPurchases ? 5 : 0);
        int confidence = (int) Math.round(Js.clamp(confidenceRaw, 40, 95));

        List<ChainStep> chain = new ArrayList<>();
        if (incumbent != null && incumbent.quoted() != null) {
            chain.add(new ChainStep("quote", incumbent.name() + " quote", Js.fmtMoney(incumbent.quoted().doubleValue()),
                    null, "Ex-works " + incumbent.country() + ". What you are quoted today.",
                    incumbent.quoted().doubleValue()));
            if (incumbent.landed() != null) {
                chain.add(new ChainStep("lane", "Freight and duty into " + regionLabelResolved,
                        "+" + Js.fmtMoney(incumbent.freightAndDuty() == null ? 0 : incumbent.freightAndDuty().doubleValue()),
                        Js.signedMoney(incumbent.landed().subtract(incumbent.quoted()).doubleValue()), "Lane estimate",
                        incumbent.landed().doubleValue()));
            }
        }
        if (rec.marketLow() != null && rec.marketHigh() != null) {
            chain.add(new ChainStep("panel", "Supplier panel, landed here",
                    Js.fmtMoney(rec.marketLow().doubleValue()) + Js.EN_DASH + Js.fmtMoney(rec.marketHigh().doubleValue()),
                    null, quotable.size() + " suppliers can serve this item into " + BuyRecommendationEngine.storeLabel(destinationStore) + ".",
                    incumbent != null && incumbent.landed() != null ? incumbent.landed().doubleValue() : rec.marketLow().doubleValue()));
        }
        if (rec.marketMedian() != null) {
            chain.add(new ChainStep("benchmark", "Market benchmark", Js.fmtMoney(rec.marketMedian().doubleValue()),
                    incumbent != null && incumbent.landed() != null
                            ? Js.signedMoney(rec.marketMedian().subtract(incumbent.landed()).doubleValue()) : null,
                    "The middle of the panel. Where a fair price sits.", rec.marketMedian().doubleValue()));
        }
        if (rec.marketLow() != null) {
            chain.add(new ChainStep("best", "Best observed", Js.fmtMoney(rec.marketLow().doubleValue()), null,
                    "The lowest anyone actually lands this item here for. The target is never set below it.",
                    rec.marketLow().doubleValue()));
        }
        ChainStep finalStep = null;
        if (targetCost != null) {
            chain.add(new ChainStep("target", "Target cost", Js.fmtMoney(targetCost.doubleValue()),
                    rec.marketMedian() != null ? Js.signedMoney(targetCost.subtract(rec.marketMedian()).doubleValue()) : null,
                    "Best observed plus a third of the gap to the benchmark: achievable, not theoretical.",
                    targetCost.doubleValue()));
            finalStep = new ChainStep("final", "Target cost", Js.fmtMoney(targetCost.doubleValue()),
                    currentCost != null ? Js.signedMoney(targetCost.subtract(currentCost).doubleValue()) : null,
                    currentCost != null ? "Against the " + Js.fmtMoney(currentCost.doubleValue()) + " you land today." : "",
                    targetCost.doubleValue());
        }

        BuyNowVsWait nowVsWait = null;
        if (currentCost != null) {
            Catalogue.ProductRef p = product;
            Reference.Commodity commodity = reference.commodity(p.commodity());
            double drift30 = Js.round1(commodity.pct90() == null ? 0 : commodity.pct90().doubleValue() * 0.38);
            double cost30 = Js.round2(currentCost.doubleValue() * (1 + drift30 / 100));
            double deltaPerUnit = Js.round2(cost30 - currentCost.doubleValue());
            boolean rising = drift30 > 0.8;
            boolean falling = drift30 < -0.8;
            int incumbentLead = incumbent != null && incumbent.leadDays() != null ? incumbent.leadDays() : 30;
            String waitRisk = rising ? (drift30 > 2 ? "High" : "Medium") : falling ? (incumbentLead > 30 ? "Medium" : "Low") : "Low";
            String driverLabel = commodity.label() != null ? commodity.label() + " (reference, as of "
                    + (commodity.asOf() != null ? commodity.asOf() : "n/a") + ")" : "No commodity exposure";
            String nowVsWaitReason = rising
                    ? driverLabel + ": the same order is expected to cost " + Js.fmtMoney(Math.abs(deltaPerUnit))
                            + " more a unit in 30 days. Lock the price now."
                    : falling
                            ? driverLabel + ": waiting 30 days is expected to save " + Js.fmtMoney(Math.abs(deltaPerUnit))
                                    + " a unit, with " + incumbentLead + " days of lead time to cover from stock."
                            : "No meaningful move expected in the next 30 days. Buy on need, negotiate on price.";
            nowVsWait = new BuyNowVsWait(falling ? "wait" : "now", nowVsWaitReason,
                    new BuyNowVsWait.Now(currentCost.doubleValue(), Js.round2(currentCost.doubleValue() * effectiveQty.doubleValue())),
                    new BuyNowVsWait.Wait(30, cost30, deltaPerUnit, Js.round2(deltaPerUnit * effectiveQty.doubleValue()), waitRisk),
                    driverLabel);
        }

        Negotiation negotiation = null;
        if (incumbent != null && incumbent.landed() != null && targetCost != null && rec.marketMedian() != null
                && annualVolume != null) {
            double gapPct = incumbent.landed().signum() != 0
                    ? Js.round1(incumbent.landed().subtract(targetCost).doubleValue() / incumbent.landed().doubleValue() * 100) : 0;
            double annualSavings = Js.round2(Math.max(0, incumbent.landed().subtract(targetCost).doubleValue()) * annualVolume);
            int relYears = incumbent.relationshipYears() == null ? 0 : incumbent.relationshipYears();
            int leadDays = incumbent.leadDays() == null ? 0 : incumbent.leadDays();
            double otif = incumbent.otifPct() == null ? 0 : incumbent.otifPct().doubleValue();
            List<String> levers = List.of(
                    Js.localeInt(annualVolume) + " units a year into " + regionLabelResolved,
                    "Panel benchmark " + Js.fmtMoney(rec.marketMedian().doubleValue()) + " landed, best "
                            + Js.fmtMoney(rec.marketLow().doubleValue()),
                    relYears >= 3 ? relYears + "-year relationship, " + Js.toFixed(otif, 0) + "% on time"
                            : "Newer relationship - volume commitment is the lever",
                    "Net 30".equals(incumbent.terms()) ? "Offer Net 45 in exchange for price"
                            : "Currently on " + (incumbent.terms() == null ? "unknown terms" : incumbent.terms()));
            String message = String.join("\n",
                    "Subject: " + product.shortName() + " — pricing for the next 12 months", "",
                    "Hi " + incumbent.name() + " team,", "",
                    "We've reviewed our " + product.shortName() + " spend into " + regionLabelResolved
                            + ". Over the last twelve months we bought " + Js.localeInt(annualVolume)
                            + " units from you at " + Js.fmtMoney(incumbent.quoted() == null ? incumbent.landed().doubleValue()
                                    : incumbent.quoted().doubleValue())
                            + " ex-works (" + Js.fmtMoney(incumbent.landed().doubleValue()) + " landed), while comparable "
                            + "supply into the same branches now lands at " + Js.fmtMoney(rec.marketMedian().doubleValue())
                            + ", and the best offer we hold is " + Js.fmtMoney(rec.marketLow().doubleValue()) + ".",
                    "",
                    "We'd rather keep this volume with you" + (relYears >= 3
                            ? " — " + relYears + " years and " + Js.toFixed(otif, 0) + "% on-time delivery count for a lot"
                            : "") + " — but we need to bring the landed cost to " + Js.fmtMoney(targetCost.doubleValue())
                            + ". At current volume that is a " + Js.fmtMoney(annualSavings, 0) + " a year gap we can't carry.",
                    "",
                    "Can you confirm " + Js.fmtMoney(targetCost.doubleValue()) + " on the next release? If it helps get "
                            + "there, we're open to a twelve-month volume commitment"
                            + ("Net 30".equals(incumbent.terms()) ? " or moving to Net 45" : "") + ".",
                    "", "Thanks,");
            negotiation = new Negotiation(incumbent.name(), incumbent.quoted() == null ? incumbent.landed().doubleValue()
                    : incumbent.quoted().doubleValue(), rec.marketMedian().doubleValue(), annualVolume,
                    targetCost.doubleValue(), annualSavings, gapPct, relYears, leadDays, otif, message, levers);
        }

        Map<String, String> sources = new LinkedHashMap<>(rec.sources());

        return new BuyIntel(
                itemNumber, product.shortName(), rec.description(), rec.priceable(), regionKey, regionLabelResolved,
                destination, BuyRecommendationEngine.storeLabel(destinationStore), (int) effectiveQty.doubleValue(),
                currentCost, targetCost, supplierAverage, rec.marketMedian(), rec.marketLow(), sellPrice,
                marginNowPct, savingPerUnit, savingPct, potentialSavings, confidence, confidenceLabel(confidence),
                suppliers, incumbent, recommendedSupplier, cheapestQuoted, reason,
                chain, finalStep, nowVsWait, negotiation, annualVolume, sources, locked);
    }

    private static SupplierEval withRecommended(SupplierEval s, boolean recommended) {
        return new SupplierEval(s.supplierId(), s.name(), s.country(), s.quoted(), s.landed(), s.effective(),
                s.freightAndDuty(), s.leadDays(), s.otifPct(), s.defectPct(), s.fulfilmentPct(), s.terms(),
                s.commercial(), s.penaltyRecoveryPerUnit(), s.moq(), s.meetsMoq(), s.relationshipYears(),
                s.adjustments(), s.isIncumbent(), recommended, s.risk(), s.riskNote(), s.hasQuote(), s.holdsStock());
    }
}
