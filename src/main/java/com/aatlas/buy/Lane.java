package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One resolved freight lane: origin country in, destination market region out. The
 * frontend's {@code Lane} in {@code platform/logistics.ts}.
 */
@Schema(name = "Lane")
public record Lane(
        String originCountry,
        String regionKey,
        String regionLabel,
        String mode,
        String gateway,
        double freightPct,
        double dutyPct,
        String dutyNote,
        int transitDays,
        String routeNote) {
}
