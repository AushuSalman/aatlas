package com.aatlas.buy.internal;

import com.aatlas.buy.CalcStep;
import com.aatlas.buy.MarketEvidence.Reorder;
import com.aatlas.history.PricingModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * How many to order and by when - the question replenishment tools lead with.
 *
 * <p>The textbook reorder-point rule, kept plain enough to read on the screen:
 * <ul>
 *   <li>lead-time demand = units a week × lead time (supplier lead time plus transit);</li>
 *   <li>safety stock = a share of lead-time demand ({@value #SAFETY_SHARE_PCT}% by default), for demand
 *       or delivery running late;</li>
 *   <li>reorder point = lead-time demand + safety stock - order when stock falls to it;</li>
 *   <li>order quantity = enough to get back to the reorder point plus the cover weeks ({@value #COVER_WEEKS}
 *       by default) of sales, from the stock there will be on the order date (the reorder point, or today's
 *       stock if already below it), rounded up to whole units and to the supplier's minimum order;</li>
 *   <li>order-by date = when stock reaches the reorder point at today's sales speed.</li>
 * </ul>
 * More than the overstock weeks ({@value #OVERSTOCK_WEEKS} by default) of stock is overstock: order nothing.
 * No sales, or no stock figure, and the plan says so instead of guessing. Pure: no I/O, no clock. The
 * numbers are the tenant's buying model's reorder knobs ({@link Settings#of}), or {@link Settings#DEFAULTS}.
 */
final class ReorderEngine {

    static final int COVER_WEEKS = 8;
    static final int SAFETY_SHARE_PCT = 50;
    static final int OVERSTOCK_WEEKS = 26;
    static final int SOON_DAYS = 14;
    /** Used, and labelled "assumed", when no supplier lead time is on file. */
    static final int DEFAULT_LEAD_DAYS = 14;

    private ReorderEngine() {
    }

    /**
     * The reorder rule's numbers.
     *
     * @param coverWeeks      weeks of sales an order should cover past the reorder point
     * @param safetyPct       safety stock as a share of lead-time demand, percent
     * @param overstockWeeks  weeks of stock that count as overstocked
     * @param soonDays        days ahead of the order-by date that count as "order soon"
     * @param defaultLeadDays lead time assumed when none is on file
     */
    record Settings(int coverWeeks, int safetyPct, int overstockWeeks, int soonDays, int defaultLeadDays) {

        static final Settings DEFAULTS = new Settings(COVER_WEEKS, SAFETY_SHARE_PCT, OVERSTOCK_WEEKS, SOON_DAYS,
                DEFAULT_LEAD_DAYS);

        /** The tenant's buying model's reorder knobs. */
        static Settings of(PricingModel.Config cfg) {
            return new Settings((int) cfg.number(PricingModel.BUY_REORDER_COVER_WEEKS),
                    (int) cfg.number(PricingModel.BUY_REORDER_SAFETY_SHARE),
                    (int) cfg.number(PricingModel.BUY_REORDER_OVERSTOCK_WEEKS),
                    (int) cfg.number(PricingModel.BUY_REORDER_SOON_DAYS),
                    (int) cfg.number(PricingModel.BUY_REORDER_DEFAULT_LEAD_DAYS));
        }
    }

    /**
     * The plan as the tenant's buying model configures it: null when its reorder advice is
     * switched off, else {@link #plan(BigDecimal, LocalDate, BigDecimal, String, Integer, String, Integer,
     * LocalDate, Settings)} with the model's knobs.
     */
    static Reorder planIfOn(PricingModel.Config cfg, BigDecimal onHand, LocalDate onHandAsOf, BigDecimal perWeek,
            String perWeekBasis, Integer leadDays, String leadBasis, Integer moq, LocalDate today) {
        if (!cfg.on(PricingModel.BUY_REORDER)) {
            return null;
        }
        return plan(onHand, onHandAsOf, perWeek, perWeekBasis, leadDays, leadBasis, moq, today, Settings.of(cfg));
    }

    /** The plan with {@link Settings#DEFAULTS}. */
    static Reorder plan(BigDecimal onHand, LocalDate onHandAsOf, BigDecimal perWeek, String perWeekBasis,
            Integer leadDays, String leadBasis, Integer moq, LocalDate today) {
        return plan(onHand, onHandAsOf, perWeek, perWeekBasis, leadDays, leadBasis, moq, today, Settings.DEFAULTS);
    }

    /**
     * @param onHand     stock at the branch, or null when none is on file
     * @param perWeek    units sold a week, or null/zero when there are no sales
     * @param leadDays   supplier lead time plus transit, or null (then {@link Settings#defaultLeadDays()}, "assumed")
     * @param leadBasis  {@code supplier} or {@code panel}; ignored when {@code leadDays} is null
     * @param moq        the supplier's minimum order, or null
     * @param settings   the rule's numbers
     */
    static Reorder plan(BigDecimal onHand, LocalDate onHandAsOf, BigDecimal perWeek, String perWeekBasis,
            Integer leadDays, String leadBasis, Integer moq, LocalDate today, Settings settings) {
        Settings s = settings == null ? Settings.DEFAULTS : settings;
        int coverWeeks = Math.max(1, s.coverWeeks());
        int safetyPct = Math.max(0, s.safetyPct());
        int overstockWeeks = Math.max(1, s.overstockWeeks());
        int soonDays = Math.max(0, s.soonDays());
        int defaultLead = s.defaultLeadDays() > 0 ? s.defaultLeadDays() : DEFAULT_LEAD_DAYS;

        int lead = leadDays != null && leadDays > 0 ? leadDays : defaultLead;
        String basis = leadDays != null && leadDays > 0 ? leadBasis : "assumed";
        List<CalcStep> steps = new ArrayList<>();

        if (perWeek == null || perWeek.signum() <= 0) {
            return new Reorder("no-sales", onHand, onHandAsOf, null, perWeekBasis, null, lead, basis, null, null, null,
                    moq, coverWeeks, "No sales yet, so there is no demand to size an order from. Upload sales history "
                            + "and this becomes a quantity and a date.", steps);
        }
        BigDecimal daily = perWeek.divide(BigDecimal.valueOf(7), 6, RoundingMode.HALF_UP);
        BigDecimal leadDemand = daily.multiply(BigDecimal.valueOf(lead));
        BigDecimal safety = leadDemand.multiply(BigDecimal.valueOf(safetyPct))
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal reorderPoint = leadDemand.add(safety).setScale(0, RoundingMode.CEILING);
        BigDecimal coverStock = perWeek.multiply(BigDecimal.valueOf(coverWeeks));

        steps.add(new CalcStep("Selling", fmt(perWeek) + " a week", perWeekBasis, "step"));
        steps.add(new CalcStep("Lead time", lead + " days",
                "assumed".equals(basis) ? "No supplier lead time on file - assumed" : "Supplier lead time plus transit",
                "step"));
        steps.add(new CalcStep("Reorder point", fmt(reorderPoint) + " units",
                "Sales over the lead time (" + fmt(leadDemand) + ") plus " + safetyPct + "% safety stock", "step"));

        if (onHand == null) {
            BigDecimal qty = roundToMoq(reorderPoint.add(coverStock), moq);
            steps.add(new CalcStep("Order", fmt(qty) + " units", "Reorder point plus " + coverWeeks
                    + " weeks of sales - no stock figure on file, so this assumes none on hand", "result"));
            return new Reorder("no-stock", null, null, perWeek, perWeekBasis, null, lead, basis, reorderPoint, qty,
                    null, moq, coverWeeks, "No stock figure on file. If you hold none, order " + fmt(qty)
                            + " units; add On Hand to your product file for a date.", steps);
        }

        BigDecimal weeks = onHand.divide(perWeek, 1, RoundingMode.HALF_UP);
        steps.add(0, new CalcStep("On hand", fmt(onHand) + " units",
                onHandAsOf == null ? "Stock on file" : "Stock as of " + onHandAsOf, "step"));
        steps.add(new CalcStep("Weeks of stock", fmt(weeks), "On hand ÷ units a week", "step"));

        if (weeks.compareTo(BigDecimal.valueOf(overstockWeeks)) > 0) {
            steps.add(new CalcStep("Order", "0 units", "More than " + overstockWeeks + " weeks of stock", "result"));
            return new Reorder("overstocked", onHand, onHandAsOf, perWeek, perWeekBasis, weeks, lead, basis,
                    reorderPoint, BigDecimal.ZERO, null, moq, coverWeeks, "Overstocked: " + fmt(weeks)
                            + " weeks of stock at today's sales. Don't order - sell some down first.", steps);
        }

        // Sized for the order date: by then stock has fallen to the reorder point (or is below it now).
        BigDecimal stockWhenOrdering = onHand.min(reorderPoint);
        BigDecimal qty = roundToMoq(reorderPoint.add(coverStock).subtract(stockWhenOrdering).max(BigDecimal.ZERO), moq);
        long daysUntil = onHand.subtract(reorderPoint).divide(daily, 0, RoundingMode.FLOOR).longValue();
        LocalDate orderBy = daysUntil <= 0 ? today : today.plusDays(daysUntil);
        String status = daysUntil <= 0 ? "order-now" : daysUntil <= soonDays ? "order-soon" : "ok";
        steps.add(new CalcStep("Order", fmt(qty) + " units",
                "Back to the reorder point plus " + coverWeeks + " weeks of sales"
                        + (moq != null && moq > 1 ? ", at least the minimum order of " + moq : ""), "result"));
        steps.add(new CalcStep("Order by", daysUntil <= 0 ? "Now" : orderBy.toString(),
                "When stock reaches the reorder point at today's sales speed", "result"));

        String summary = switch (status) {
            case "order-now" -> "Order " + fmt(qty) + " units now: " + fmt(weeks) + " weeks of stock left, and the "
                    + "supplier takes " + lead + " days.";
            case "order-soon" -> "Order " + fmt(qty) + " units by " + orderBy + " - " + fmt(weeks)
                    + " weeks of stock left.";
            default -> "No order needed yet: " + fmt(weeks) + " weeks of stock. Order about " + fmt(qty)
                    + " units by " + orderBy + ".";
        };
        return new Reorder(status, onHand, onHandAsOf, perWeek, perWeekBasis, weeks, lead, basis, reorderPoint, qty,
                orderBy, moq, coverWeeks, summary, steps);
    }

    static BigDecimal roundToMoq(BigDecimal qty, Integer moq) {
        BigDecimal whole = qty.setScale(0, RoundingMode.CEILING);
        if (whole.signum() > 0 && moq != null && moq > 1 && whole.compareTo(BigDecimal.valueOf(moq)) < 0) {
            return BigDecimal.valueOf(moq);
        }
        return whole;
    }

    private static String fmt(BigDecimal v) {
        BigDecimal s = v.scale() > 1 ? v.setScale(1, RoundingMode.HALF_UP) : v;
        return s.stripTrailingZeros().toPlainString();
    }
}
