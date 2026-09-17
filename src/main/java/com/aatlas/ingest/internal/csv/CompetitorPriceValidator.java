package com.aatlas.ingest.internal.csv;

import com.aatlas.ingest.internal.csv.ImportReport.RowIssue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Validates a competitor-price observation file. */
@Component
public class CompetitorPriceValidator extends AbstractValidator<CompetitorPriceRow> {

    static final Set<String> REGION_KEYS = Set.of("south", "west", "north", "east");

    private static final int MAX_COMPETITOR_LENGTH = 80;

    @Override
    public ImportKind kind() {
        return ImportKind.COMPETITOR_PRICES;
    }

    @Override
    protected List<RowIssue> checkRow(List<String> row, ColumnMapping m, ValidationContext ctx, int line,
            FileState state) {
        List<RowIssue> issues = new ArrayList<>();

        if (cell(row, m, CompetitorPriceField.ITEM).isEmpty()) {
            issues.add(RowIssue.error(line, CompetitorPriceField.ITEM, "Item number is blank"));
        }

        String competitor = cell(row, m, CompetitorPriceField.COMPETITOR);
        if (competitor.isEmpty()) {
            issues.add(RowIssue.error(line, CompetitorPriceField.COMPETITOR, "Competitor is blank"));
        } else if (competitor.length() > MAX_COMPETITOR_LENGTH) {
            issues.add(RowIssue.warning(line, CompetitorPriceField.COMPETITOR,
                    "Competitor name shortened to " + MAX_COMPETITOR_LENGTH + " characters"));
        }

        Optional<BigDecimal> price = ValueParsers.parseNumber(cell(row, m, CompetitorPriceField.PRICE));
        if (price.isEmpty()) {
            issues.add(RowIssue.error(line, CompetitorPriceField.PRICE, "Price is not a number"));
        } else if (price.get().signum() <= 0) {
            issues.add(RowIssue.error(line, CompetitorPriceField.PRICE,
                    "Price is " + plain(price.get()) + " — must be above zero"));
        }

        checkCurrency(row, m, CompetitorPriceField.CURRENCY, ctx, line).ifPresent(issues::add);

        String region = cell(row, m, CompetitorPriceField.REGION);
        if (!region.isEmpty() && !resolvesRegion(region, ctx)) {
            issues.add(RowIssue.warning(line, CompetitorPriceField.REGION, "Region \"" + region
                    + "\" is not a branch, state or region — observation kept without a region"));
        }

        String rawObserved = cell(row, m, CompetitorPriceField.OBSERVED_AT);
        if (!rawObserved.isEmpty()) {
            Optional<LocalDate> observed = ValueParsers.parseDate(rawObserved, ctx.dateOrder());
            if (observed.isEmpty()) {
                issues.add(RowIssue.warning(line, CompetitorPriceField.OBSERVED_AT,
                        "Could not read the observed date \"" + rawObserved + "\" — using today"));
            } else if (ctx.today() != null && observed.get().isAfter(ctx.today())) {
                issues.add(RowIssue.warning(line, CompetitorPriceField.OBSERVED_AT,
                        "Observed date is in the future — using today"));
            }
        }

        String url = cell(row, m, CompetitorPriceField.SOURCE_URL);
        if (!url.isEmpty() && !url.toLowerCase(Locale.ROOT).matches("^https?://.*")) {
            issues.add(RowIssue.warning(line, CompetitorPriceField.SOURCE_URL,
                    "Source URL does not start with http:// or https:// — kept as written"));
        }
        return issues;
    }

    /** A branch code, a subdivision code or one of the four region keys the tenant knows. */
    static boolean resolvesRegion(String region, ValidationContext ctx) {
        String key = region.strip().toLowerCase(Locale.ROOT);
        return REGION_KEYS.contains(key) || ctx.knownBranches().contains(key) || ctx.regionCodes().contains(key);
    }

    @Override
    protected CompetitorPriceRow toRow(List<String> row, ColumnMapping m, ValidationContext ctx, int line) {
        String competitor = cell(row, m, CompetitorPriceField.COMPETITOR);
        if (competitor.length() > MAX_COMPETITOR_LENGTH) {
            competitor = competitor.substring(0, MAX_COMPETITOR_LENGTH).strip();
        }
        LocalDate observed = ValueParsers.parseDate(cell(row, m, CompetitorPriceField.OBSERVED_AT), ctx.dateOrder())
                .filter(d -> ctx.today() == null || !d.isAfter(ctx.today()))
                .orElse(ctx.today());
        return new CompetitorPriceRow(
                line,
                cell(row, m, CompetitorPriceField.ITEM),
                competitor,
                ValueParsers.parseNumber(cell(row, m, CompetitorPriceField.PRICE)).orElse(BigDecimal.ZERO),
                writtenCurrency(ctx),
                cell(row, m, CompetitorPriceField.REGION),
                observed,
                blankToNull(cell(row, m, CompetitorPriceField.SOURCE_URL)));
    }

    @Override
    protected void tally(CompetitorPriceRow row, Tally tally) {
        Tally.add(tally.items, row.item());
        Tally.add(tally.competitors, row.competitor().toLowerCase(Locale.ROOT));
        tally.date(row.observedAt());
    }
}
