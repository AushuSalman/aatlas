package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Access level: what someone may administer, separate from the job function that decides
 * which workspaces they open. Wire values match the frontend's {@code WorkspaceRole}.
 */
enum WorkspaceRole {
    /** Created the workspace. One per workspace; never granted from the Users screen. */
    SUPER_ADMIN("super-admin"),
    /** Manages Members day to day. */
    ADMIN("admin"),
    MEMBER("member");

    private final String wireValue;

    WorkspaceRole(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    String wireValue() {
        return wireValue;
    }

    static WorkspaceRole fromWire(String value) {
        for (WorkspaceRole r : values()) {
            if (r.wireValue.equals(value)) {
                return r;
            }
        }
        throw ApiException.badRequest("validation_failed", "Choose Admin or Member.");
    }

    String label() {
        return switch (this) {
            case SUPER_ADMIN -> "Super Admin";
            case ADMIN -> "Admin";
            case MEMBER -> "Member";
        };
    }

    @Converter
    static class JpaConverter implements AttributeConverter<WorkspaceRole, String> {
        @Override
        public String convertToDatabaseColumn(WorkspaceRole role) {
            return role == null ? null : role.wireValue;
        }

        @Override
        public WorkspaceRole convertToEntityAttribute(String value) {
            return value == null ? null : fromWire(value);
        }
    }
}
