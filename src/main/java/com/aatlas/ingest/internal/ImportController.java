package com.aatlas.ingest.internal;

import com.aatlas.ingest.internal.ImportBatchView.ImportIssueView;
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
 * CSV import: upload, correct the columns, commit.
 *
 * <p>The flow the connect screen walks, in four calls. Upload returns a full report, so the
 * screen can show what the file contains before anything is loaded; the mapping can be
 * corrected as many times as needed because nothing has been written yet; and commit is the
 * only call that changes the tenant's data.
 */
@RestController
@RequestMapping(path = "/api/v1/imports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Imports", description = "Load sales history from a CSV export.")
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
                    be rejected and why.

                    `status` is `VALIDATED` when the file is ready to commit, or `NEEDS_MAPPING` when a
                    required field has no column - in which case `missingRequired` names them and the
                    user picks columns before continuing.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Stored and validated."),
        @ApiResponse(responseCode = "400", description = "No file, a file too large, or one that cannot be read.")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ImportBatchView> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "dataSourceId", required = false) UUID dataSourceId) {
        ImportBatchView created = imports.upload(file, dataSourceId);
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/imports/{id}").build(created.id()))
                .body(created);
    }

    @Operation(
            summary = "The report for one import",
            description = "Also the way a running commit is followed: poll until `status` leaves `COMMITTING`.")
    @GetMapping("/{id}")
    ImportBatchView get(@PathVariable UUID id) {
        return imports.get(id);
    }

    @Operation(summary = "Every import this tenant has uploaded, newest first")
    @GetMapping
    List<ImportBatchView> list() {
        return imports.list();
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

                    Products are created for item numbers the catalogue has not seen. Branches and
                    customers are not: an unknown code is kept on the row and reported in
                    `unresolvedBranches` / `unresolvedCustomers`, because the history is true whether or
                    not the branch has been set up here yet.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "The load has started."),
        @ApiResponse(responseCode = "400", description = "Columns are incomplete, or no row passed validation."),
        @ApiResponse(responseCode = "409", description = "Already committing, or already committed.")
    })
    @PostMapping("/{id}/commit")
    ResponseEntity<ImportBatchView> commit(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(imports.commit(id));
    }
}
