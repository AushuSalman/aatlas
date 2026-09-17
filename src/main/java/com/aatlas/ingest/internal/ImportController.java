package com.aatlas.ingest.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.ingest.internal.ImportBatchView.ImportIssueView;
import com.aatlas.ingest.internal.csv.ImportKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * CSV import: upload, correct the columns, commit, roll back.
 *
 * <p>The flow the connect screen walks, in four calls. Upload returns a full report, so the
 * screen can show what the file contains before anything is loaded; the mapping can be
 * corrected as many times as needed because nothing has been written yet; commit is the
 * only call that changes the tenant's data; and delete takes it back.
 */
@RestController
@RequestMapping(path = "/api/v1/imports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Imports", description = "Load sales, purchases, products and competitor prices from CSV exports.")
@Validated
class ImportController {

    private final ImportService imports;

    ImportController(ImportService imports) {
        this.imports = imports;
    }

    @Operation(
            summary = "Upload a CSV and validate it",
            description =
                    """
                    Stores the file, detects which column holds which field, and validates every row.
                    Nothing is loaded: the response is a report of what the file contains, what would
                    be rejected and why. `kind` is `sales` (default), `purchases`, `products` or
                    `competitor_prices`.

                    `status` is `VALIDATED` when the file is ready to commit, or `NEEDS_MAPPING` when a
                    required field has no column - in which case `missingRequired` names them and the
                    user picks columns before continuing. `duplicateOf` names an earlier committed batch
                    with the same bytes; `isSample` says the file is the Hardin sample.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Stored and validated."),
        @ApiResponse(responseCode = "400", description = "No file, a file too large, an unknown kind, or one that cannot be read."),
        @ApiResponse(responseCode = "409", description = "remove_sample_first: this workspace runs on the sample.")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ImportBatchView> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "kind", defaultValue = "sales") String kind,
            @RequestParam(value = "dataSourceId", required = false) UUID dataSourceId) {
        ImportBatchView created = imports.upload(kindOf(kind), file, dataSourceId);
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/imports/{id}").build(created.id()))
                .body(created);
    }

    @Operation(summary = "The field table of one import kind",
            description = "Keys, labels, template headers, examples and hints, for the data guide and the column picker.")
    @GetMapping("/fields")
    ImportFieldsView fields(@RequestParam("kind") String kind) {
        return imports.fields(kindOf(kind));
    }

    @Operation(
            summary = "The report for one import",
            description = "Also the way a running commit is followed: poll until `status` leaves `COMMITTING`.")
    @GetMapping("/{id}")
    ImportBatchView get(@PathVariable UUID id) {
        return imports.get(id);
    }

    @Operation(summary = "Every import this tenant has uploaded, newest first; `kind` filters")
    @GetMapping
    List<ImportBatchView> list(@RequestParam(value = "kind", required = false) String kind) {
        return imports.list(kind == null || kind.isBlank() ? null : kindOf(kind));
    }

    @Operation(
            summary = "Reassign columns and revalidate",
            description =
                    """
                    Send the whole mapping, not a patch - claiming a column for one field takes it from
                    another. A field left out has no column, which is how a wrongly detected one is
                    cleared. The file is read again and the report replaced.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Revalidated against the new mapping."),
        @ApiResponse(responseCode = "400", description = "An unknown field or a negative column index."),
        @ApiResponse(responseCode = "409", description = "The file has already been committed.")
    })
    @PutMapping(path = "/{id}/mapping", consumes = MediaType.APPLICATION_JSON_VALUE)
    ImportBatchView updateMapping(@PathVariable UUID id, @Valid @RequestBody UpdateMappingRequest request) {
        return imports.updateMapping(id, request.mapping());
    }

    @Operation(
            summary = "Row problems, paged",
            description = "Ordered by line. Filter with `severity=error` to see only what was rejected.")
    @GetMapping("/{id}/issues")
    List<ImportIssueView> issues(
            @PathVariable UUID id,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return imports.issues(id, severity, limit, offset);
    }

    @Operation(
            summary = "Load the accepted rows",
            description =
                    """
                    Returns **202** immediately and loads in the background: a 24-month export is
                    hundreds of thousands of rows and nobody holds a request open for that. Poll
                    `GET /api/v1/imports/{id}` until `status` is `COMMITTED` or `FAILED`.

                    All or nothing. A partial import is the worst outcome available - the tenant cannot
                    tell which months are complete - so a failure leaves nothing loaded and the same
                    batch can be committed again.

                    Products, branches, customers and suppliers the catalogue has never seen are created
                    (branches with no region, customers with no segment, suppliers with no figures) and
                    reported in `branchesNeedingRegion` and `summary`, so a person can finish them.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "The load has started."),
        @ApiResponse(responseCode = "400", description = "Columns are incomplete, or no row passed validation."),
        @ApiResponse(responseCode = "409", description = "Already committing, already committed, or the sample file on a real workspace.")
    })
    @PostMapping("/{id}/commit")
    ResponseEntity<ImportBatchView> commit(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(imports.commit(id));
    }

    @Operation(
            summary = "Roll an import back, or delete one that never loaded",
            description =
                    """
                    A committed batch is rolled back in one transaction: its rows are removed from the
                    fact tables and any products, branches, customers or suppliers it created that
                    nothing else references are deleted. Answers **200** with the batch as `ROLLED_BACK`.
                    A batch that never loaded is deleted outright: **204**.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Rolled back."),
        @ApiResponse(responseCode = "204", description = "Deleted."),
        @ApiResponse(responseCode = "409", description = "import_in_progress or already_rolled_back.")
    })
    @DeleteMapping("/{id}")
    ResponseEntity<ImportBatchView> delete(@PathVariable UUID id) {
        return imports.delete(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static ImportKind kindOf(String kind) {
        try {
            return ImportKind.from(kind);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("unknown_kind", ex.getMessage());
        }
    }
}
