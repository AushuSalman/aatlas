package com.aatlas.approvals.internal;

import com.aatlas.approvals.ApprovalLimits;
import com.aatlas.approvals.ApprovalRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-off on decisions raised over a seat's approval limit. Group K of the blueprint's
 * endpoint inventory.
 */
@RestController
@RequestMapping(path = "/api/v1/approvals", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Approvals", description = "Requests raised when a decision exceeds the seat's limit.")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token."),
    @ApiResponse(responseCode = "404", description = "not_found: no such approval request.")
})
class ApprovalController {

    private final ApprovalService service;

    ApprovalController(ApprovalService service) {
        this.service = service;
    }

    @Operation(summary = "Pending requests for my role, and mine",
            description = "Every pending request waiting on the signed-in seat's role, plus every request the "
                    + "signed-in user raised themselves regardless of status - see `mine` on each row.")
    @GetMapping
    List<ApprovalRequest> list() {
        return service.list();
    }

    @Operation(summary = "One request with the decision behind it")
    @GetMapping("/{id}")
    ApprovalRequest get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "Commit the decision",
            description = "Moves the linked decision to `approved` and publishes `ApprovalGranted`; the module "
                    + "that raised the request finishes writing the deal.")
    @PostMapping(path = "/{id}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApprovalRequest approve(@PathVariable UUID id, @RequestBody(required = false) DecideRequest body) {
        return service.approve(id, body == null ? null : body.note());
    }

    @Operation(summary = "Reject with a note",
            description = "Moves the linked decision to `rejected` and publishes `ApprovalRejected`.")
    @PostMapping(path = "/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApprovalRequest reject(@PathVariable UUID id, @RequestBody(required = false) DecideRequest body) {
        return service.reject(id, body == null ? null : body.note());
    }

    @Operation(summary = "My seat's limit and approver")
    @GetMapping("/limits")
    ApprovalLimits limits() {
        return service.limits();
    }
}
