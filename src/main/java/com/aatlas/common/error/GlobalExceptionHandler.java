package com.aatlas.common.error;

import com.fasterxml.jackson.databind.JsonMappingException.Reference;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error leaves the API as RFC 9457 {@code application/problem+json}.
 *
 * <p>Expected failures carry their {@code code} and details. Unexpected ones are logged
 * with the stack trace and returned as a bare 500 carrying only the trace id, so an
 * internal message never reaches a browser.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BASE_TYPE = "https://docs.aatlas.io/errors/";

    @ExceptionHandler(ApiException.class)
    public ProblemDetail onApi(ApiException ex, HttpServletRequest request) {
        ProblemDetail problem = base(ex.status(), ex.code(), ex.getMessage(), request);
        ex.details().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new TreeMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ProblemDetail problem =
                base(HttpStatus.BAD_REQUEST, "validation_failed", "The request body is not valid.", request);
        problem.setProperty("fields", fields);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail onConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getConstraintViolations()
                .forEach(v -> fields.put(v.getPropertyPath().toString(), v.getMessage()));
        ProblemDetail problem =
                base(HttpStatus.BAD_REQUEST, "validation_failed", "A request parameter is not valid.", request);
        problem.setProperty("fields", fields);
        return problem;
    }

    /**
     * A required query parameter was not sent at all.
     *
     * <p>The sibling of {@link #onConstraintViolation}, and the gap next to it: a parameter
     * sent empty fails {@code @NotBlank} and is already a 400, while the same parameter
     * omitted fails during binding - before any controller or any constraint runs - and fell
     * to the catch-all as a 500. The two mistakes are the same mistake, so they now read the
     * same way, under the same {@code fields} map the rest of this class uses.
     *
     * <p>Worth its own handler rather than being left to the 500 because a caller who
     * mistypes a parameter name (this API has {@code destination}, not {@code store}) is
     * told they have broken the server, and goes looking in the wrong place. It was found
     * doing exactly that.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail onMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {

        ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "missing_parameter",
                "\"" + ex.getParameterName() + "\" is required.", request);
        problem.setProperty("fields",
                Map.of(ex.getParameterName(), "This parameter is required."));
        return problem;
    }

    /**
     * A query parameter that cannot be read as the type it is declared as - {@code qty=abc}
     * for an {@code int}, or an unknown value for an enum bound from the query string.
     *
     * <p>Also a binding failure, also a 500 until now, and for the same reason: the
     * constraint annotations that produce the tidy 400s never get to run, because there is no
     * value of the right type for them to check.
     *
     * <p>The rejected value is named and the required type is not: {@code "abc" is not a
     * whole number} is the caller's own input handed back, whereas the type's class name
     * describes our internals.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onParameterTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        String name = ex.getName();
        String expected = readableType(ex.getRequiredType());
        String detail = "\"" + ex.getValue() + "\" is not " + expected + ".";

        ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "invalid_parameter",
                "\"" + name + "\": " + detail, request);
        problem.setProperty("fields", Map.of(name, detail));
        problem.setProperty("rejectedValue", String.valueOf(ex.getValue()));
        return problem;
    }

    /** The declared type as a person would say it, never as a class name. */
    private static String readableType(Class<?> type) {
        if (type == null) {
            return "the right type";
        }
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class) {
            return "a whole number";
        }
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            return "a number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "true or false";
        }
        if (type.isEnum()) {
            return "one of " + String.join(", ", java.util.Arrays.stream(type.getEnumConstants())
                    .map(String::valueOf).toList());
        }
        return "a valid value";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail onAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return base(HttpStatus.FORBIDDEN, "forbidden", "This seat may not perform that action.", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail onAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return base(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail onNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return base(HttpStatus.NOT_FOUND, "not_found", "No such endpoint.", request);
    }

    /**
     * The path exists but not for this verb.
     *
     * <p>Without this it falls to the catch-all and becomes a 500, which sends a caller looking
     * for a server fault over a typo in their own request. It is also actively misleading: a
     * {@code POST} to a path that only answers {@code GET /{id}} matches the {@code {id}}
     * pattern, so the error names a method rather than a missing endpoint.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail onMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        ProblemDetail problem = base(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed",
                ex.getMethod() + " is not supported here.", request);
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            problem.setProperty("allowed", ex.getSupportedHttpMethods().stream().map(Object::toString).toList());
        }
        return problem;
    }

    /**
     * The body is in a format this endpoint does not read - most often JSON sent to something
     * expecting multipart, or a missing {@code Content-Type} altogether.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail onUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {

        ProblemDetail problem = base(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type",
                "This endpoint does not accept that content type.", request);
        if (!ex.getSupportedMediaTypes().isEmpty()) {
            problem.setProperty("supported", ex.getSupportedMediaTypes().stream().map(Object::toString).toList());
        }
        return problem;
    }

    /**
     * A body Jackson could not turn into the target type: malformed JSON, a wrong field
     * type, or an enum rejecting an unknown value from its {@code @JsonCreator}.
     *
     * <p>Without this the enum case in particular becomes a 500, because the failure
     * happens during binding rather than inside a controller - an unknown seat name would
     * read to the caller as "the server is broken" instead of "that is not a seat".
     *
     * <p>The exception's own message is deliberately not echoed: it carries class names
     * and parser offsets that describe our internals rather than the caller's mistake.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Unreadable request body on {} {}", request.getMethod(), request.getRequestURI(), ex);

        ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "malformed_request",
                "The request body could not be read. Check the field types and any enumerated values.", request);

        // The rejected value is worth naming when we can do it without leaking internals:
        // InvalidFormatException knows which value failed and what it was being read as.
        if (ex.getCause() instanceof InvalidFormatException invalid) {
            String field = invalid.getPath().stream()
                    .map(Reference::getFieldName)
                    .filter(java.util.Objects::nonNull)
                    .reduce((first, second) -> first + "." + second)
                    .orElse(null);
            if (field != null) {
                problem.setProperty("field", field);
                problem.setProperty("rejectedValue", String.valueOf(invalid.getValue()));
            }
        }
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return base(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "Something went wrong on our side. The trace id identifies this request.", request);
    }

    private ProblemDetail base(HttpStatus status, String code, String message, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setType(URI.create(BASE_TYPE + code));
        problem.setTitle(code);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        String traceId = org.slf4j.MDC.get("traceId");
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }
}
