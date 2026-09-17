package com.aatlas.decisions.internal;

import com.aatlas.analytics.BuyImpactSummary;
import com.aatlas.decisions.DealRecord;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The pure arithmetic behind the History screen and the impact rollup: decision history is
 * every recommendation, what was applied, what actually happened. Ported field for field from
 * {@code intel/history.ts} ({@code getHistory}) and the impact half of {@code platform/api.ts}
 * ({@code summarise}, {@code summariseBuy}, {@code buildImpact}).
 *
 * <p>Stateless and Spring-free so it can be golden-tested directly against
 * {@code golden/history.json} without a database - see {@code HistoryEngineGoldenTest}.
 */
final class HistoryEngine {

    /** Calendar-month abbreviation, Jan=index 0. Ports {@code catalog.ts}'s implicit month lookup via {@code monthOf}. */
    private static final String[] MONTH_ABBR =
            {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /** The order {@code summarise}'s {@code byMonth} iterates in - a trailing twelve ending at the fixture "now". */
    private static final String[] SELL_MONTH_ORDER =
            {"Oct", "Nov", "Dec", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep"};

    private HistoryEngine() {
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
    static ImpactSummary summarise(String side, List<DealRecord> allDeals) {
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
        for (String label : SELL_MONTH_ORDER) {
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

    static ImpactData buildImpact(List<DealRecord> allDealsSorted, List<DealRecord> recorded, BuyImpactSummary buyImpact) {
        return new ImpactData(summarise("sell", allDealsSorted), summariseBuy(buyImpact), allDealsSorted, recorded);
    }

    // -- getHistory -------------------------------------------------------------------------

    static HistorySummary getHistory(ImpactData impact, List<com.aatlas.decisions.Decision> decisions) {
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

            rows.add(new HistoryRow(
                    d.id(), d.date(), d.side(), d.itemNumber(),
                    ProductNames.shortName(d.itemNumber(), d.description()),
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
