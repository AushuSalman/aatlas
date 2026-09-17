package com.aatlas.ingest.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.ingest.internal.csv.ImportKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Downloadable CSV templates and the Hardin sample files. Public, like the rest of
 * {@code /api/v1/reference}: a prospect reads them before signing up.
 */
@RestController
@RequestMapping("/api/v1/reference")
@Tag(name = "Reference", description = "Templates and sample files for every import kind.")
class TemplatesController {

    static final String SUPPLIERS = "suppliers";

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final SampleFiles samples;

    TemplatesController(SampleFiles samples) {
        this.samples = samples;
    }

    @Operation(summary = "A CSV template for one kind",
            description = "The header the import detects, plus three example rows. `kind` also accepts `suppliers`.")
    @GetMapping(value = "/templates/{kind}.csv")
    ResponseEntity<byte[]> template(@PathVariable String kind) {
        String body = SUPPLIERS.equalsIgnoreCase(kind)
                ? ImportTemplates.suppliersTemplate(samples.suppliersBytes())
                : ImportTemplates.template(kindOf(kind));
        return csv(body.getBytes(StandardCharsets.UTF_8), "aatlas-" + kind.toLowerCase() + "-template.csv");
    }

    @Operation(summary = "The Hardin sample file for one kind",
            description = "The file the sample workspace is loaded from. `kind` also accepts `suppliers`.")
    @GetMapping(value = "/samples/{kind}.csv")
    ResponseEntity<byte[]> sample(@PathVariable String kind) {
        byte[] body = SUPPLIERS.equalsIgnoreCase(kind) ? samples.suppliersBytes() : samples.bytes(kindOf(kind));
        return csv(body, "hardin-" + kind.toLowerCase() + ".csv");
    }

    private static ResponseEntity<byte[]> csv(byte[] body, String fileName) {
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(body);
    }

    private static ImportKind kindOf(String kind) {
        try {
            return ImportKind.from(kind);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("unknown_kind", ex.getMessage());
        }
    }
}
