package com.aatlas.suppliers.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.suppliers.internal.csv.SupplierColumnMapping;
import com.aatlas.suppliers.internal.csv.SupplierField;
import com.aatlas.suppliers.internal.csv.SupplierImportReport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Importing a supplier panel from a CSV.
 *
 * <p>Two endpoints rather than the upload-then-commit pair the sales import uses. A supplier
 * file is small enough to validate synchronously, so there is nothing to poll and no batch to
 * keep: the modal posts the file to see what is in it, the user corrects a column or two, and
 * the same file is posted again to import. Re-posting a few kilobytes costs less than storing
 * it and reasoning about when to clean it up.
 */
@RestController
@RequestMapping(path = "/api/v1/suppliers/imports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Suppliers", description = "Supplier panel import.")
class SupplierImportController {

    /** Generous for a vendor master and small enough that a synchronous parse stays honest. */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final SupplierImportService imports;
    private final ObjectMapper json;

    SupplierImportController(SupplierImportService imports, ObjectMapper json) {
        this.imports = imports;
        this.json = json;
    }

    @Operation(
            summary = "The columns an import can carry",
            description = "Labels, hints and which four are required. The modal renders its column "
                    + "picker from this rather than hard-coding a second copy of the list.")
    @GetMapping("/fields")
    List<FieldView> fields() {
        return java.util.Arrays.stream(SupplierField.values())
                .map(f -> new FieldView(f.key(), f.label(), f.required(), f.hint()))
                .toList();
    }

    @Operation(
            summary = "Validate a supplier CSV without importing it",
            description = """
                    Parses the file, detects which column holds which field, and reports what it
                    found: the drafts that would be created, every row problem, and the counts.

                    Nothing is written, so the modal can call this again each time the user
                    corrects a column. Pass `mapping` to override detection - a JSON object of
                    field key to zero-based column index, e.g. `{"name":0,"country":1}`.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The report. Check missingRequired before importing."),
        @ApiResponse(responseCode = "400", description = "The file is empty, too large, or not readable as CSV.")
    })
    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    SupplierImportReport preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mapping", required = false) String mapping) {
        return imports.preview(read(file), parseMapping(mapping));
    }

    @Operation(
            summary = "Import a supplier CSV onto the panel",
            description = """
                    Validates the file again - the file is the authority, not what the browser
                    concluded about it - then creates the suppliers it accepts.

                    A supplier already on the panel is updated rather than duplicated, matched on
                    name and country. Re-importing a corrected export is the intended way to fix a
                    supplier's numbers, so it converges instead of accumulating.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Imported. The body carries the report plus created/updated."),
        @ApiResponse(responseCode = "400", description = "A required column is unassigned, or no row could be read."),
        @ApiResponse(responseCode = "403", description = "Only buy seats and the director may change the panel.")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ImportResultView> importPanel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mapping", required = false) String mapping) {

        SupplierImportService.ImportOutcome outcome = imports.importPanel(read(file), parseMapping(mapping));
        return ResponseEntity.status(201).body(
                new ImportResultView(outcome.report(), outcome.created(), outcome.updated()));
    }

    /** One importable column, for the modal's picker. */
    record FieldView(String key, String label, boolean required, String hint) {
    }

    /** The report, plus what the write actually did. */
    record ImportResultView(SupplierImportReport report, int created, int updated) {
    }

    /**
     * Reads the upload as UTF-8 text.
     *
     * <p>The size cap is checked here rather than left to the servlet container's global limit,
     * which is set for 200 MB sales histories. A vendor master that size is a mistake, and
     * saying so costs less than parsing it.
     */
    private static String read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("empty_file", "Choose a CSV file to import.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("file_too_large",
                    "That file is larger than 5 MB. A supplier panel should be well under it.");
        }
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw ApiException.badRequest("unreadable_file", "That file could not be read.");
        }
    }

    /**
     * Parses the optional mapping override.
     *
     * <p>Absent means "detect from the headers", which is what the first call always wants.
     * An unknown field name or a non-numeric index is the caller's mistake and is reported as
     * one rather than silently dropping the column.
     */
    private Optional<SupplierColumnMapping> parseMapping(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Integer> parsed = json.readValue(raw, new TypeReference<Map<String, Integer>>() {});
            Map<SupplierField, Integer> columns = new EnumMap<>(SupplierField.class);
            parsed.forEach((key, index) -> {
                if (index != null && index >= 0) {
                    columns.put(SupplierField.from(key), index);
                }
            });
            return Optional.of(new SupplierColumnMapping(columns));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw ApiException.badRequest("bad_mapping",
                    "mapping must be a JSON object of field key to column index. " + ex.getMessage());
        }
    }
}
