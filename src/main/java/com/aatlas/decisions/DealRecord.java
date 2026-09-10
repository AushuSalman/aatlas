package com.aatlas.decisions;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// UUID is still used below for decisionId.

/**
 * A closed sale or purchase. Field for field the frontend's {@code DealRow}
 * (platform/types.ts): {@code recorded}/{@code customer}/{@code recordedAt}/
 * {@code belowFloor}/{@code destinationId} are present only on deals recorded through this
 * module (null/omitted on the wire for the seeded historical rows).
 *
 * @param id the frontend's own string id ({@code d-s-99} for a seeded row, {@code rec-...}/
 *     {@code recb-...} for one recorded here) - not this row's uuid primary key, exactly like
 *     {@code catalog}'s tables are keyed by the frontend's own item/store code strings.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DealRecord(
        String id,
        LocalDate date,
        String side,
        String itemNumber,
        String description,
        String counterparty,
        int qty,
        BigDecimal cost,
        BigDecimal baselinePrice,
        BigDecimal suggestedPrice,
        BigDecimal actualPrice,
        boolean followed,
        BigDecimal gain,
        BigDecimal lost,
        Boolean recorded,
        String customer,
        Instant recordedAt,
        Boolean belowFloor,
        String destinationId,
        UUID decisionId) {
}
