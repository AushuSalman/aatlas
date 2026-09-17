package com.aatlas.ingest.internal.csv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** One accepted competitor-price observation. {@code observedAt} is never null: blank meant today. */
public record CompetitorPriceRow(
        int line,
        String item,
        String competitor,
        BigDecimal price,
        String currency,
        String region,
        LocalDate observedAt,
        String sourceUrl) implements AcceptedRow {

    @Override
    public Map<String, Object> preview() {
        Map<String, Object> out = new HashMap<>();
        out.put("item", item);
        out.put("competitor", competitor);
        out.put("price", price);
        out.put("currency", currency);
        out.put("region", region);
        out.put("observedAt", observedAt == null ? null : observedAt.toString());
        out.put("sourceUrl", sourceUrl);
        return out;
    }

    @Override
    public CompetitorPriceRow forSample(int days, BigDecimal fx) {
        return new CompetitorPriceRow(line, item, competitor, AcceptedRow.convert(price, fx), currency, region,
                AcceptedRow.shift(observedAt, days), sourceUrl);
    }
}
