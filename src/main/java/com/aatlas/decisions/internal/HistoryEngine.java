package com.aatlas.decisions.internal;

import com.aatlas.analytics.BuyImpactSummary;
import com.aatlas.decisions.DealRecord;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pure arithmetic behind the History screen and the impact rollup: decision history is
 * every recommendation, what was applied, what actually happened. Ported field for field from
 * {@code intel/history.ts} ({@code getHistory}) and the impact half of {@code platform/api.ts}
 * ({@code summarise}, {@code summariseBuy}, {@code buildImpact}).
 */
final class HistoryEngine {

    /** Calendar-month abbreviation, Jan=index 0. Ports {@code catalog.ts}'s implicit month lookup via {@code monthOf}. */
    private static final String[] MONTH_ABBR =
            {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private HistoryEngine() {
    }

    /**
     * The order {@code summarise}'s {@code byMonth} iterates in: the twelve calendar months
     * ending this month, oldest first - real, from {@code AatlasClock.today()}, not a fixture
     * "now". Twelve consecutive months always cover twelve distinct calendar months, so every
     * abbreviation appears exactly once.
     */
    private static String[] sellMonthOrder(LocalDate today) {
        String[] order = new String[12];
        LocalDate month = today.minusMonths(11);
        for (int i = 0; i < 12; i++) {
            order[i] = MONTH_ABBR[month.getMonthValue() - 1];
            month = month.plusMonths(1);
        }
        return order;
    }

    private static double round2(double n) {
        return Math.round(n * 100.0) / 100.0;
    }

    private static String monthOf(LocalDate date) {
        return MONTH_ABBR[date.getMonthValue() - 1];
    }

    /** Ports {@code mock/pricing.ts}'s {@code marginPercent}; {@code null} when price is zero. */
    private static Double marginPercent(double price, double cost) {
        if (price == 0) {
            return null;
        }
        return round2(((price - cost) / price) * 100);
    }

    // -- summarise ------------------------------------------------------------------------

    /**
     * What the tool has been worth for one side, reduced from every deal on that side. Ports
     * {@code platform/api.ts}'s {@code summarise}.
     */
    static ImpactSummary summarise(String side, List<DealRecord> allDeals, LocalDate today) {
        List<DealRecord> deals = allDeals.stream().filter(d -> side.equals(d.side())).toList();
        int followed = (int) deals.stream().filter(DealRecord::followed).count();
        double gained = round2(deals.stream().mapToDouble(d -> d.gain().doubleValue()).sum());
        double lost = round2(deals.stream().mapToDouble(d -> d.lost().doubleValue()).sum());

        // A sale recorded without a cost on file has no realised profit to count; it is neither
        // zero nor invented, so it is left out of the sum rather than pulling it down.
        double realised = round2(deals.stream()
                .filter(d -> !"sell".equals(side) || d.cost() != null)
                .mapToDouble(d -> "sell".equals(side)
                        ? (d.actualPrice().doubleValue() - d.cost().doubleValue()) * d.qty()
                        : (d.baselinePrice().doubleValue() - d.actualPrice().doubleValue()) * d.qty()).sum());

        List<ImpactSummary.MonthPoint> byMonth = new ArrayList<>();
        for (String label : sellMonthOrder(today)) {
            List<DealRecord> inMonth = deals.stream().filter(d -> monthOf(d.date()).equals(label)).toList();
            byMonth.add(new ImpactSummary.MonthPoint(label,
                    round2(inMonth.stream().mapToDouble(d -> d.gain().doubleValue()).sum()),
                    round2(inMonth.stream().mapToDouble(d -> d.lost().doubleValue()).sum())));
        }

        return new ImpactSummary(side, deals.size(), followed,
                deals.isEmpty() ? 0 : round2((followed * 100.0) / deals.size()),
                realised, gained, lost, round2(realised + lost), round2(gained - lost), byMonth);
    }

    /**
     * The buy side of the impact summary, from the procurement ledger's trailing twelve
     * months rather than from {@code deal} - ports {@code platform/api.ts}'s {@code summariseBuy}.
     */
    static ImpactSummary summariseBuy(BuyImpactSummary a) {
        int followed = (int) Math.round((a.captureRatePct() / 100.0) * a.lines());
        List<ImpactSummary.MonthPoint> byMonth = a.byMonth().stream()
                .map(m -> new ImpactSummary.MonthPoint(m.label(), m.gained(), m.lost()))
                .toList();
        return new ImpactSummary("buy", a.lines(), followed, a.captureRatePct(), a.savedTotal(),
                a.savedTotal(), a.leakedTotal(), round2(a.savedTotal() + a.leakedTotal()),
                round2(a.savedTotal() - a.leakedTotal()), byMonth);
    }

    static ImpactData buildImpact(List<DealRecord> allDealsSorted, List<DealRecord> recorded,
            BuyImpactSummary buyImpact, LocalDate today) {
        return new ImpactData(summarise("sell", allDealsSorted, today), summariseBuy(buyImpact), allDealsSorted, recorded);
    }

    // -- getHistory -------------------------------------------------------------------------

    /**
     * @param shortNames {@code item_number -> history.Catalogue.ProductRef.shortName()} for
     *     every product the tenant has, fetched once by the caller; a deal whose item is not
     *     on file (or carries no short name) falls back to the deal's own description, then the
     *     item number itself - {@code ProductNames}' old fallback order, over real rows.
     */
    static HistorySummary getHistory(ImpactData impact, List<com.aatlas.decisions.Decision> decisions,
            Map<String, String> shortNames) {
        List<HistoryRow> rows = new ArrayList<>();
        for (DealRecord d : impact.deals()) {
            double actual = d.actualPrice().doubleValue();
            double baseline = d.baselinePrice().doubleValue();
            double suggested = d.suggestedPrice().doubleValue();
            boolean recorded = Boolean.TRUE.equals(d.recorded());
            double applied = recorded ? actual : d.followed() ? suggested : actual;

            Double marginPct;
            if ("sell".equals(d.side())) {
                marginPct = d.cost() == null ? null : marginPercent(actual, d.cost().doubleValue());
            } else {
                marginPct = baseline != 0 ? ((baseline - actual) / baseline) * 100 : 0;
            }
            if (marginPct != null) {
                marginPct = Math.round(marginPct * 10.0) / 10.0;
            }

            double value = d.followed() ? d.gain().doubleValue() : -d.lost().doubleValue();
            String outcome = d.followed() ? (value > 0.5 ? "positive" : "neutral") : (value < -0.5 ? "negative" : "neutral");
            String outcomeLabel = "positive".equals(outcome) ? "Positive"
                    : "negative".equals(outcome) ? "Missed"
                    : d.followed() ? "Followed" : "Neutral";

            String name = shortNames.get(d.itemNumber());
            if (name == null || name.isBlank()) {
                name = d.description() != null && !d.description().isBlank() ? d.description() : d.itemNumber();
            }

            rows.add(new HistoryRow(
                    d.id(), d.date(), d.side(), d.itemNumber(), name,
                    d.counterparty(), d.qty(), suggested, applied, actual, marginPct, d.followed(),
                    outcome, outcomeLabel, value, recorded, d.customer()));
        }

        int total = rows.size();
        int followed = (int) rows.stream().filter(HistoryRow::followed).count();
        double gained = impact.sell().gained() + impact.buy().gained();
        double lost = impact.sell().lost() + impact.buy().lost();

        return new HistorySummary(rows, decisions, total, followed,
                total == 0 ? 0 : (int) Math.round((followed * 100.0) / total),
                gained, lost, gained - lost);
    }
}
