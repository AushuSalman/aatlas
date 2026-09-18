package com.aatlas.identity.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Users screen. Super Admins and Admins only; every refusal carries the reason the
 * screen shows, and field-level ones carry {@code field} so the form can put it in place.
 */
@RestController
@RequestMapping(path = "/api/v1/users", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Users", description = "Invite people, set access levels and per-user module access, suspend and remove.")
class UsersController {

    private final UserManagementService service;

    UsersController(UserManagementService service) {
        this.service = service;
    }

    @Operation(summary = "Everyone in the workspace", description = "Removed users are not listed.")
    @GetMapping
    List<UserManagementService.UserRecord> list() {
        return service.list();
    }

    @Operation(summary = "Invite someone", description = "Creates them as invited and emails a link to set a password. Valid 7 days.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Invited."),
        @ApiResponse(responseCode = "400", description = "validation_failed, with field."),
        @ApiResponse(responseCode = "403", description = "not_allowed: an access level or access this admin cannot grant."),
        @ApiResponse(responseCode = "409", description = "email_taken."),
        @ApiResponse(responseCode = "503", description = "mail_failed: nothing was saved.")
    })
    @PostMapping(path = "/invitations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    UserManagementService.UserRecord invite(@Valid @RequestBody UserRequests.Invite request) {
        return service.invite(request);
    }

    @Operation(summary = "Change someone's name, job function, access level or module access")
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    UserManagementService.UserRecord update(@PathVariable UUID id, @Valid @RequestBody UserRequests.Update request) {
        return service.update(id, request);
    }

    @Operation(summary = "Suspend", description = "Signs them out everywhere; they cannot sign in until reactivated.")
    @PostMapping("/{id}/suspend")
    UserManagementService.UserRecord suspend(@PathVariable UUID id) {
        return service.setSuspended(id, true);
    }

    @Operation(summary = "Reactivate a suspended user")
    @PostMapping("/{id}/reactivate")
    UserManagementService.UserRecord reactivate(@PathVariable UUID id) {
        return service.setSuspended(id, false);
    }

    @Operation(summary = "Send the invitation email again", description = "The previous link stops working.")
    @PostMapping("/{id}/invitations/resend")
    UserManagementService.UserRecord resend(@PathVariable UUID id) {
        return service.resendInvitation(id);
    }

    @Operation(summary = "Remove from the workspace", description = "Their past decisions keep their name. They can be invited again.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID id) {
        service.remove(id);
    }

    @Operation(summary = "Recent invitations and access changes, newest first")
    @GetMapping("/audit")
    List<UserManagementService.AuditView> audit() {
        return service.auditLog();
    }
}
