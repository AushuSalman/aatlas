package com.aatlas.buy.internal;

import com.aatlas.buy.CommercialTerms;
import com.aatlas.history.Suppliers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * What a supplier's paperwork says, and what it is worth per unit. {@code supplier_terms},
 * verbatim (via {@link SupplierGateway#terms(String)}) - never seeded; a missing row means
 * every figure is null, "Not provided". A port of the frontend's {@code intel/terms.ts}'s
 * value formulas, made null-safe over an input that may genuinely be absent.
 */
final class TermsEngine {

    private TermsEngine() {
    }

    /** Annual cost of capital used to value credit, percent. Stated, not modelled. */
    static final BigDecimal COST_OF_CAPITAL_PCT = BigDecimal.valueOf(8);
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    static CommercialTerms commercialTerms(Suppliers.Terms t) {
        if (t == null) {
            return CommercialTerms.notProvided();
        }
        return new CommercialTerms(t.creditDays(), t.termsLabel(), t.earlyPayDiscountPct(), t.earlyPayDays(),
                t.latePenaltyPctPerWeek(), t.latePenaltyCapPct(), t.warrantyMonths(), t.quoteValidityDays(),
                t.incoterm(), t.invoiceAccuracyPct(), t.capacityUnitsMonth());
    }

    // -- What the terms are worth, per unit -----------------------------------------------------

    /** Credit is money: the cost of capital on the price for the days you hold it. Null without a credit term. */
    static BigDecimal creditValuePerUnit(BigDecimal unitCost, Integer creditDays) {
        if (unitCost == null || creditDays == null) {
            return null;
        }
        return unitCost.multiply(BigDecimal.valueOf(creditDays)).divide(DAYS_PER_YEAR, 6, RoundingMode.HALF_UP)
                .multiply(COST_OF_CAPITAL_PCT).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /**
     * An early-settlement discount, net of the credit you give up to take it. Null when the
     * terms are unknown; zero when no discount is offered or it is not worth it.
     */
    static BigDecimal earlyPayNetPerUnit(BigDecimal unitCost, CommercialTerms t) {
        if (unitCost == null || t.earlyPayDiscountPct() == null || t.earlyPayDiscountPct().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount = unitCost.multiply(t.earlyPayDiscountPct()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        int creditDays = t.creditDays() == null ? 0 : t.creditDays();
        int earlyPayDays = t.earlyPayDays() == null ? 0 : t.earlyPayDays();
        BigDecimal creditGivenUp = creditValuePerUnit(unitCost, Math.max(0, creditDays - earlyPayDays));
        if (creditGivenUp == null) {
            creditGivenUp = BigDecimal.ZERO;
        }
        BigDecimal net = discount.subtract(creditGivenUp);
        return net.signum() < 0 ? BigDecimal.ZERO : net.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The most a late clause recovers per unit: the weekly rate over a typical slip of about
     * ten days, capped where the contract caps it. Zero without a clause or an unknown unit cost.
     */
    static BigDecimal penaltyRecoveryCapPerUnit(BigDecimal unitCost, CommercialTerms t) {
        if (unitCost == null || t.latePenaltyPctPerWeek() == null || t.latePenaltyPctPerWeek().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal typicalSlipWeeks = new BigDecimal("1.5");
        BigDecimal cap = t.latePenaltyCapPct() == null ? t.latePenaltyPctPerWeek() : t.latePenaltyCapPct();
        BigDecimal rate = t.latePenaltyPctPerWeek().multiply(typicalSlipWeeks);
        BigDecimal pct = rate.min(cap);
        return unitCost.multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    static String latePenaltyLabel(CommercialTerms t) {
        if (t.latePenaltyPctPerWeek() == null || t.latePenaltyPctPerWeek().signum() <= 0) {
            return "No late-delivery clause";
        }
        return plain(t.latePenaltyPctPerWeek()) + "% a week late, capped at " + plain(t.latePenaltyCapPct()) + "%";
    }

    static String creditLabel(CommercialTerms t) {
        if (t.creditDays() == null) {
            return "Not provided";
        }
        return t.creditDays() == 0 ? "None, pay up front" : t.creditDays() + " days";
    }

    static String earlyPayLabel(CommercialTerms t) {
        return t.earlyPayDiscountPct() != null && t.earlyPayDiscountPct().signum() > 0
                ? plain(t.earlyPayDiscountPct()) + "% if paid in " + t.earlyPayDays() + " days"
                : "None";
    }

    /** The things a buyer would flag before awarding, stated plainly. Empty when nothing stands out or nothing is on file. */
    static List<String> termsWatchOuts(CommercialTerms t, int orderQty) {
        List<String> out = new ArrayList<>();
        if (t.creditDays() != null && t.creditDays() == 0) {
            out.add("Wants payment up front.");
        }
        if (t.latePenaltyPctPerWeek() != null && t.latePenaltyPctPerWeek().signum() <= 0) {
            out.add("No late-delivery clause: a missed date costs them nothing.");
        }
        if (t.capacityUnitsMonth() != null && orderQty > t.capacityUnitsMonth()) {
            out.add("This order is above their " + Js.localeInt(t.capacityUnitsMonth()) + " units a month capacity.");
        }
        if (t.invoiceAccuracyPct() != null && t.invoiceAccuracyPct().doubleValue() < 90) {
            out.add("Invoices are right " + plain(t.invoiceAccuracyPct()) + "% of the time.");
        }
        return out;
    }

    private static String plain(BigDecimal v) {
        if (v == null) {
            return "0";
        }
        BigDecimal stripped = v.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
    }
}
