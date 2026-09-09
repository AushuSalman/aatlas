package com.aatlas.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The one exception type controllers and services throw for an expected failure.
 *
 * <p>Carries the HTTP status and a stable machine-readable {@code code} so the Next.js
 * client can branch on the code rather than on wording. Anything not thrown as an
 * {@code ApiException} is a bug and becomes a 500 with a trace id.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    // ---- the shapes the API actually returns -------------------------------

    public static ApiException notFound(String entity, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found",
                "%s %s does not exist".formatted(entity, id));
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    /**
     * The snapshot this screen reads has not been computed yet. The controller answers
     * 202 with a job id rather than computing it on the request thread.
     */
    public static ApiException snapshotPending(String jobId) {
        return new ApiException(HttpStatus.ACCEPTED, "snapshot_pending",
                "This view is being recomputed. Poll /api/v1/admin/jobs/" + jobId + ".",
                Map.of("jobId", jobId));
    }

    /** A buy over the seat's approval limit commits nothing and raises a request. */
    public static ApiException approvalRequired(Object approvalId, String approverRole) {
        return new ApiException(HttpStatus.ACCEPTED, "approval_required",
                "This decision exceeds your approval limit and was sent to " + approverRole + ".",
                Map.of("approvalId", approvalId, "approverRole", approverRole));
    }
}
