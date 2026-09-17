package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Reference data every consumer gets the same way: the tenant's guardrails (or the
 * defaults), the gross-margin benchmarks, the commodity trend and the shipping origins.
 * Everything here is labelled {@code reference} wherever it reaches a screen.
 */
public interface Reference {

    Guardrails guardrails();

    /** Subcategory row, else category row, else the default ({@code *}). */
    Benchmark benchmark(String category, String subcategory);

    /** The commodity trend for a key; {@code none} for an unknown key. */
    Commodity commodity(String key);

    /** The shipping origin for a supplier country; the fallback origin when unknown. */
    Origin origin(String country);

    /** The inland leg for a US state within a lane, when one is on file. */
    Optional<Lane> lane(String subdivisionCode, String entry);

    record Guardrails(BigDecimal minMarginPct, BigDecimal maxDiscountPct, BigDecimal maxSpeedPremiumPct,
            BigDecimal maxMarketDeviationPct) {
    }

    /** A gross-margin band. {@code matched} says which row answered: subcategory, category or default. */
    record Benchmark(String category, String subcategory, BigDecimal targetMarginPct, BigDecimal lowMarginPct,
            BigDecimal highMarginPct, String note, String matched) {
    }

    record Commodity(String key, String label, BigDecimal pct90, LocalDate asOf) {
    }

    /** From {@code logistics_origins}; {@code fallback} is true for a country with no lane. */
    record Origin(String country, String entry, String mode, String gateway, BigDecimal inboundPct, int inboundDays,
            BigDecimal dutyPct, String dutyNote, boolean fallback) {
    }

    record Lane(String regionKey, String regionLabel, BigDecimal inlandPct, int inlandDays) {
    }
}
