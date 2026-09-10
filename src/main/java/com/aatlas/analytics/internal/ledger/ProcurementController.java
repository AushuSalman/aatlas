package com.aatlas.analytics.internal.ledger;

import com.aatlas.common.web.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Buying insights dashboard: the procurement ledger, reduced at read time. Group N of the
 * wave-2 brief. Every endpoint takes the same {@code range}/{@code from}/{@code to}/
 * {@code branch}/{@code category} query shape as the frontend's own {@code DateRange}/
 * {@code Filters} so a screen can hit exactly the endpoint it needs rather than always paying
 * for the full payload.
 */
@RestController
@RequestMapping(path = "/api/v1/analytics/procurement", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Procurement analytics", description = "The Buying insights dashboard, reduced from the purchase-order ledger.")
class ProcurementController {

    private final ProcurementAnalyticsService service;

    ProcurementController(ProcurementAnalyticsService service) {
        this.service = service;
    }

    @Operation(summary = "The full dashboard payload: KPIs, timeline, mixes, scorecard, delivery, opportunities, status")
    @GetMapping
    BuyAnalytics procurement(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String category) {
        return service.analyze(range, from, to, branch, category);
    }

    @Operation(summary = "Spend over time, bucketed by day, week or month depending on the range")
    @GetMapping("/timeline")
    List<TimelinePoint> timeline(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String category) {
        return service.analyze(range, from, to, branch, category).timeline();
    }

    @Operation(summary = "The supplier scorecard, with fulfilment risk (exposure x reliability)")
    @GetMapping("/suppliers")
    List<SupplierRow> suppliers(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String category) {
        return service.analyze(range, from, to, branch, category).suppliers();
    }

    @Operation(summary = "Spend mix by supplier, category, branch, region or origin",
            description = "dimension is one of supplier (default), category, branch, region, origin.")
    @GetMapping("/mix")
    List<MixSlice> mix(
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String category) {
        return service.mix(dimension, range, from, to, branch, category);
    }

    @Operation(summary = "On-time delivery by bucket, and the worst misses")
    @GetMapping("/delivery")
    DeliveryResponse delivery(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String category) {
        BuyAnalytics a = service.analyze(range, from, to, branch, category);
        return new DeliveryResponse(a.delivery(), a.lateLines());
    }

    record DeliveryResponse(List<DeliveryPoint> delivery, List<PoRow> lateLines) {
    }

    @Operation(summary = "Lines furthest over target - where money is going that should not be")
    @GetMapping("/opportunities")
    List<Opportunity> opportunities(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String category) {
        return service.analyze(range, from, to, branch, category).opportunities();
    }

    @Operation(summary = "The purchase order rows, searchable and status-filterable, keyset paged")
    @GetMapping("/ledger")
    CursorPage<PoRow> ledger(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return service.searchLedger(q, status, limit, cursor);
    }

    @Operation(summary = "The range presets and the earliest order on file")
    @GetMapping("/ranges")
    ProcurementAnalyticsService.RangesResponse ranges() {
        return service.ranges();
    }
}
