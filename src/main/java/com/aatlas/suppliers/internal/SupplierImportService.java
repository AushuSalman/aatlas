package com.aatlas.suppliers.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.HistoryCaches;
import com.aatlas.suppliers.internal.csv.SupplierColumnMapping;
import com.aatlas.suppliers.internal.csv.SupplierDraft;
import com.aatlas.suppliers.internal.csv.SupplierImportReport;
import com.aatlas.suppliers.internal.csv.SupplierImportValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Importing a supplier panel from a file.
 *
 * <p>Two operations, and the split is the point. {@link #preview} validates and writes
 * nothing, so the modal can re-run it every time the user corrects a column mapping without
 * having committed to anything. {@link #importPanel} validates again - the file is the
 * authority, not whatever the browser concluded about it - and then writes.
 *
 * <p>No batch table, unlike the sales import. A supplier panel is tens or hundreds of rows,
 * not a 24-month history, so it validates in milliseconds and commits in one transaction;
 * persisting a batch to poll would be machinery serving nothing. The file is not kept either,
 * for the same reason the modal holds it: re-validating means re-posting it, which for a file
 * this size is cheaper than storing it.
 */
@Service
public class SupplierImportService {

    private static final Logger log = LoggerFactory.getLogger(SupplierImportService.class);

    /** What the rating source says for a supplier whose numbers came from the buyer's own file. */
    private static final String IMPORT_RATING_SOURCE = "Imported from your file";

    private final SupplierRepository suppliers;
    private final SupplierWriter writer;
    private final SupplierImportValidator validator;
    private final SupplierPanelAccess access;
    private final SupplierProductLinkSeeder links;
    private final AatlasClock clock;
    private final HistoryCaches caches;

    SupplierImportService(
            SupplierRepository suppliers,
            SupplierWriter writer,
            SupplierImportValidator validator,
            SupplierPanelAccess access,
            SupplierProductLinkSeeder links,
            AatlasClock clock,
            HistoryCaches caches) {
        this.suppliers = suppliers;
        this.writer = writer;
        this.validator = validator;
        this.access = access;
        this.links = links;
        this.clock = clock;
        this.caches = caches;
    }

    /** What a commit did, on top of what validation found. */
    public record ImportOutcome(SupplierImportReport report, int created, int updated) {
    }

    /**
     * Validates without writing.
     *
     * @param mapping the user's column choices, or empty to detect them from the headers
     */
    @Transactional(readOnly = true)
    public SupplierImportReport preview(String csv, Optional<SupplierColumnMapping> mapping) {
        TenantContext.requireTenantId();
        return mapping.map(m -> validator.validate(csv, m))
                .orElseGet(() -> validator.validateWithDetectedMapping(csv));
    }

    /**
     * Validates and writes the accepted rows.
     *
     * <p>A supplier already on the panel is updated rather than duplicated. The match is on
     * name and country, which is the key the file itself is de-duplicated by - re-importing a
     * corrected export is the normal way to fix a supplier's numbers, and it has to converge
     * rather than accumulate.
     *
     * <p>One transaction: a panel half-imported is worse than one not imported, because
     * nobody can tell which half.
     */
    @Transactional
    public ImportOutcome importPanel(String csv, Optional<SupplierColumnMapping> mapping) {
        UUID tenantId = TenantContext.requireTenantId();
        access.requireBuySeatOrDirector();
        UUID userId = TenantContext.currentUserId().orElse(null);

        SupplierImportReport report = mapping.map(m -> validator.validate(csv, m))
                .orElseGet(() -> validator.validateWithDetectedMapping(csv));

        if (!report.missingRequired().isEmpty()) {
            throw ApiException.badRequest("missing_required_columns",
                    "Assign a column to every required field before importing: "
                            + report.missingRequired().stream().map(f -> f.label()).toList());
        }
        if (report.drafts().isEmpty()) {
            throw ApiException.badRequest("nothing_to_import",
                    "No row in this file could be read as a supplier.");
        }

        Instant now = clock.now();
        int created = 0;
        int updated = 0;

        for (SupplierDraft draft : report.drafts()) {
            String supplierKey = "csv-" + draft.key().replace('|', '-');
            Optional<SupplierEntity> existing = suppliers.findByTenantIdAndSupplierKey(tenantId, supplierKey);

            if (existing.isPresent()) {
                writer.update(existing.get(), draft, SupplierWriter.IMPORTED_SOURCE);
                updated++;
            } else {
                writer.create(tenantId, supplierKey, draft, userId, SupplierWriter.IMPORT_SOURCE,
                        SupplierWriter.IMPORTED_SOURCE);
                created++;
            }
        }

        // A supplier nobody has linked to a product can quote on nothing, because
        // SupplierGateway.panelFor only falls back to the whole panel for an item with no
        // links at all. Without this an imported supplier would be invisible on every product
        // the seeded panel already covers - ten new suppliers, and the buy screen unchanged.
        // So they get the same "no opinion recorded yet" default the seed writes: every
        // supplier against every product. Idempotent, so the re-import path costs nothing.
        int linked = 0;
        if (created > 0) {
            suppliers.flush();
            linked = links.linkAllForTenant(tenantId);
        }

        // Readiness counts suppliers and is cached per tenant for 15 minutes; evicted after
        // commit, as ImportCommitter does, so the next read sees the panel it just wrote.
        caches.evictAfterCommit(tenantId);

        log.info("Supplier import for tenant {}: {} created, {} updated, {} rejected, {} product links",
                tenantId, created, updated, report.rejectedRows(), linked);

        return new ImportOutcome(report, created, updated);
    }

}
