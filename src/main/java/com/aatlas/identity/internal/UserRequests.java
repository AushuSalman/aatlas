package com.aatlas.identity.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The bodies of the Users screen's calls, and of accepting an invitation. */
final class UserRequests {

    private UserRequests() {
    }

    /**
     * @param persona the job function's wire value, e.g. {@code purchase-head}
     * @param workspaceRole {@code admin} or {@code member}; Super Admin is never granted here
     * @param permissions null keeps the job function's defaults
     */
    @Schema(name = "InviteUserRequest")
    record Invite(
            @NotBlank(message = "Enter their name.") @Size(max = 120, message = "Keep the name under 120 characters.") String name,
            @NotBlank(message = "Enter their email address.") @Size(max = 254, message = "That email address is too long.") String email,
            @NotBlank(message = "Choose a job function.") String persona,
            @NotBlank(message = "Choose Admin or Member.") String workspaceRole,
            UserPermissions permissions) {
    }

    @Schema(name = "UpdateUserRequest")
    record Update(
            @NotBlank(message = "Enter their name.") @Size(max = 120, message = "Keep the name under 120 characters.") String name,
            @NotBlank(message = "Choose a job function.") String persona,
            @NotBlank(message = "Choose Admin or Member.") String workspaceRole,
            UserPermissions permissions) {
    }

    @Schema(name = "InvitationLookupRequest")
    record Lookup(@NotBlank(message = "The invitation link is incomplete.") @Size(max = 512) String token) {
    }

    @Schema(name = "AcceptInvitationRequest")
    record Accept(
            @NotBlank(message = "The invitation link is incomplete.") @Size(max = 512) String token,
            @NotBlank(message = "A password is needed to continue.")
                    @Size(min = 8, max = 20, message = "Use between 8 and 20 characters.")
                    String password) {
    }
}
