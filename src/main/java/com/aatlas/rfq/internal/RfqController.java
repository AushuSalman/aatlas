package com.aatlas.rfq.internal;

import com.aatlas.rfq.Rfq;
import com.aatlas.rfq.RfqAwardResult;
import com.aatlas.rfq.RfqRecommendation;
import com.aatlas.rfq.RfqStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFQ rounds: create, list, read, edit while draft, send, enter replies, recommend and
 * award. Group J of the blueprint's endpoint inventory.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/rfqs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "RFQ", description = "Request-for-quotation rounds, invites, supplier quotes and award.")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token."),
    @ApiResponse(responseCode = "404", description = "not_found: no such round.")
})
class RfqController {

    private final RfqService service;

    RfqController(RfqService service) {
        this.service = service;
    }

    @Operation(summary = "Create a round",
            description = "Drafts an invitation to the given suppliers from this order's ranked panel "
                    + "(`buy.ProcurementPlanReader`) and writes the request message. `createRfq`.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Rfq create(@Valid @RequestBody CreateRfqRequest request) {
        return service.create(request);
    }

    @Operation(summary = "List rounds by status")
    @GetMapping
    List<Rfq> list(@RequestParam(required = false) RfqStatus status,
            @RequestParam(defaultValue = "50") int limit) {
        return service.list(status, limit);
    }

    @Operation(summary = "Round with invites and quotes")
    @GetMapping("/{id}")
    Rfq get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "Edit the request while draft",
            description = "Quantity, required days, priority and notes only; the invite panel itself is not "
                    + "recomputed. 409 once the round has been sent.")
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Rfq update(@PathVariable UUID id, @RequestBody UpdateRfqRequest request) {
        return service.update(id, request);
    }

    @Operation(summary = "Send by email to invited suppliers",
            description = "Draft only, 409 otherwise. Stamps every invite `sentAt` and moves the round to "
                    + "`sent`.")
    @PostMapping("/{id}/send")
    Rfq send(@PathVariable UUID id) {
        return service.send(id);
    }

    @Operation(summary = "Enter a supplier's reply, or decline",
            description = "Any invite named in the body is recorded as given; every other invite still waiting "
                    + "gets a deterministic simulated reply (`simulateQuote`) - there is no supplier inbox behind "
                    + "this yet. Send the round first.")
    @PostMapping(path = "/{id}/quotes", consumes = MediaType.APPLICATION_JSON_VALUE)
    Rfq quotes(@PathVariable UUID id, @RequestBody(required = false) EnterQuotesRequest request) {
        return service.enterQuotes(id, request == null ? new EnterQuotesRequest(List.of()) : request);
    }

    @Operation(summary = "Which quote to take, and why",
            description = "Scores every live (non-declined) quote under the order's own priority weights. "
                    + "409 with no live quotes yet.")
    @GetMapping("/{id}/recommendation")
    RfqRecommendation recommendation(@PathVariable UUID id) {
        return service.recommendation(id);
    }

    @Operation(summary = "Award the round",
            description = "Closes the round and writes a decision plus a purchase - or, over the signed-in "
                    + "seat's approval limit, an approval request. Same shape as `POST /buy/select`.")
    @PostMapping(path = "/{id}/award", consumes = MediaType.APPLICATION_JSON_VALUE)
    RfqAwardResult award(@PathVariable UUID id, @Valid @RequestBody AwardRequest request) {
        return service.award(id, request);
    }

    @Operation(summary = "CSV quote sheet")
    @GetMapping(path = "/{id}/export", produces = "text/csv")
    ResponseEntity<String> export(@PathVariable UUID id) {
        String csv = service.exportCsv(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"rfq-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
