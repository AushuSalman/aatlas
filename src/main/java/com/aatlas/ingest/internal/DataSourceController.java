package com.aatlas.ingest.internal;

import com.aatlas.ingest.DataSourceView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Group E of the blueprint, the part that gates the workspace: what is connected. */
@RestController
@RequestMapping(path = "/api/v1/data-sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Data sources", description = "What a tenant has connected. None = onboarding.")
class DataSourceController {

    private final DataSourceService service;

    DataSourceController(DataSourceService service) {
        this.service = service;
    }

    @Operation(summary = "Connected sources and their sync state",
            description = "Newest first. An empty list is what routes the client to onboarding.")
    @GetMapping
    List<DataSourceView> list() {
        return service.list();
    }

    @Operation(summary = "Connect a source",
            description = """
                    `{"kind":"sample"}` copies the seeded catalogue for the tenant's country into the \
                    workspace and answers 201 with the recorded source. Calling it again is a 409 \
                    `already_connected` carrying the existing source's id.

                    `erp` and `warehouse` are recorded as `pending` with the label, detail, config and \
                    schedule given; no connector runs yet. `csv` is refused: use POST /api/v1/imports.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Recorded."),
        @ApiResponse(responseCode = "400", description = "validation_failed or use_imports."),
        @ApiResponse(responseCode = "409", description = "already_connected: the sample dataset is already there.")
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DataSourceView> connect(@Valid @RequestBody ConnectDataSourceRequest request) {
        DataSourceView source = service.connect(request);
        return ResponseEntity.created(
                        UriComponentsBuilder.fromPath("/api/v1/data-sources/{id}").build(source.id()))
                .body(source);
    }

    @Operation(summary = "Disconnect a source",
            description = "Removes the source. The catalogue and history it brought stay.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Disconnected."),
        @ApiResponse(responseCode = "404", description = "not_found: no such source on this tenant.")
    })
    @DeleteMapping("/{id}")
    ResponseEntity<Void> disconnect(@PathVariable UUID id) {
        service.disconnect(id);
        return ResponseEntity.noContent().build();
    }
}
