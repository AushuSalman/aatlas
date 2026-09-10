package com.aatlas.decisions.internal;

import com.aatlas.analytics.ProcurementAnalytics;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.web.CursorPage;
import com.aatlas.decisions.DealRecord;
import com.aatlas.decisions.Decision;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * History and impact reads: the History screen and Overview's "gained/lost" figures. Ports
 * {@code intel/history.ts}'s {@code getHistory} and the impact half of
 * {@code platform/api.ts} over real rows instead of {@code localStorage}.
 */
@Service
@Transactional(readOnly = true)
public class HistoryService {

    private final DealRepository deals;
    private final DecisionRepository decisions;
    private final ProcurementAnalytics procurementAnalytics;

    HistoryService(DealRepository deals, DecisionRepository decisions, ProcurementAnalytics procurementAnalytics) {
        this.deals = deals;
        this.decisions = decisions;
        this.procurementAnalytics = procurementAnalytics;
    }

    private List<DealRecord> allDeals(UUID tenantId) {
        return deals.findByTenantIdOrderByDealDateDescCreatedAtDesc(tenantId).stream()
                .map(Mappers::toDealRecord)
                .toList();
    }

    ImpactData buildImpact() {
        UUID tenantId = TenantContext.requireTenantId();
        if (!deals.existsByTenantId(tenantId)) {
            throw noHistory();
        }
        List<DealRecord> all = allDeals(tenantId);
        List<DealRecord> recorded = all.stream().filter(d -> Boolean.TRUE.equals(d.recorded())).toList();
        return HistoryEngine.buildImpact(all, recorded, procurementAnalytics.trailingTwelveMonthImpact());
    }

    public HistorySummary getHistory() {
        ImpactData impact = buildImpact();
        List<Decision> recent = decisions.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId(),
                        org.springframework.data.domain.PageRequest.of(0, 500)).stream()
                .map(Mappers::toDecision)
                .toList();
        return HistoryEngine.getHistory(impact, recent);
    }

    public HistorySummaryView summary() {
        HistorySummary h = getHistory();
        return new HistorySummaryView(h.total(), h.adoptionPct(), h.gained(), h.lost(), h.net());
    }

    private static ApiException noHistory() {
        return new ApiException(HttpStatus.NOT_FOUND, "no_history",
                "This workspace has no history yet. Connect a data source with "
                        + "POST /api/v1/data-sources - {\"kind\":\"sample\"} is the quickest way to see "
                        + "every screen.");
    }

    // -- /history rows: filterable and keyset paged ------------------------------------------

    public CursorPage<HistoryRow> history(String side, String item, String q, String sort, String cursor, int limit) {
        List<HistoryRow> rows = new java.util.ArrayList<>(getHistory().rows());
        if (side != null && !side.isBlank()) {
            rows.removeIf(r -> !r.side().equalsIgnoreCase(side.strip()));
        }
        if (item != null && !item.isBlank()) {
            rows.removeIf(r -> !r.itemNumber().equalsIgnoreCase(item.strip()));
        }
        if (q != null && !q.isBlank()) {
            String needle = q.strip().toLowerCase(java.util.Locale.ROOT);
            rows.removeIf(r -> !(r.itemNumber().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || r.name().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || r.scope().toLowerCase(java.util.Locale.ROOT).contains(needle)));
        }
        java.util.Comparator<HistoryRow> comparator = switch (sort == null ? "date" : sort) {
            case "value" -> java.util.Comparator.comparingDouble(HistoryRow::value).reversed();
            case "margin" -> java.util.Comparator.comparingDouble(HistoryRow::marginPct).reversed();
            default -> java.util.Comparator.comparing(HistoryRow::date).reversed();
        };
        rows.sort(comparator);

        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            String after = decodeRowCursor(cursor);
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id().toString().equals(after)) {
                    start = i + 1;
                    break;
                }
            }
        }
        List<HistoryRow> window = rows.subList(start, rows.size());
        List<HistoryRow> page = window.stream().limit((long) limit + 1).toList();
        return CursorPage.of(page, limit, r -> encodeRowCursor(r.id().toString()));
    }

    private static String encodeRowCursor(String id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeRowCursor(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_cursor", "That cursor is not one this API issued.");
        }
    }

    // -- /deals --------------------------------------------------------------------------

    public CursorPage<DealRecord> pagedDeals(int limit, String cursor) {
        UUID tenantId = TenantContext.requireTenantId();
        List<DealRecord> all = allDeals(tenantId);
        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            String after = decodeRowCursor(cursor);
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id().toString().equals(after)) {
                    start = i + 1;
                    break;
                }
            }
        }
        List<DealRecord> window = all.subList(start, all.size());
        List<DealRecord> page = window.stream().limit((long) limit + 1).toList();
        return CursorPage.of(page, limit, d -> encodeRowCursor(d.id().toString()));
    }
}
