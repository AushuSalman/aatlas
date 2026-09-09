package com.aatlas.identity.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Public authentication endpoints.
 *
 * <p>Mapped under {@code /api/v1/auth}, which {@code SecurityConfig} leaves open - these
 * are the calls a caller makes precisely because they have no token yet.
 *
 * <p>Thin by rule: read the request, hand it to the service, shape the response.
 * {@code ArchitectureRulesTest} fails the build if a controller reaches a repository, so
 * the transaction and every decision in it stay in one place.
 */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authentication", description = "Signup and sign-in. No token required.")
class AuthController {

    private final SignupService signupService;

    AuthController(SignupService signupService) {
        this.signupService = signupService;
    }

    @Operation(
            summary = "Create a company and its first user",
            description =
                    """
                    Creates the tenant, its owner, and a signed-in session, in one transaction.

                    The returned `session` matches the frontend's `Session` type, so the client
                    can render immediately without decoding the access token. `session.dataSource`
                    is always null: a new tenant has no history to price from, and that absence
                    is what routes the client to onboarding rather than the workspace.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Account created and signed in."),
        @ApiResponse(responseCode = "400", description = "The form is invalid or the password is too weak."),
        @ApiResponse(responseCode = "409", description = "That email address already has an account."),
        @ApiResponse(responseCode = "429", description = "Too many accounts from this connection.")
    })
    @PostMapping(path = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AuthResponse> signUp(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
        AuthResponse response = signupService.signUp(request, clientIpOf(http), userAgentOf(http));

        // 201 with a Location pointing at the user this created. Costs nothing and means a
        // generic client does not have to know where the resource lives.
        return ResponseEntity.created(
                        UriComponentsBuilder.fromPath("/api/v1/users/{id}")
                                .build(response.session().user().id()))
                .body(response);
    }

    /**
     * The caller's address, for the rate limiter and the token's audit trail.
     *
     * <p>{@code X-Forwarded-For} is trusted only because this API is never exposed
     * directly - an ingress terminates TLS and rewrites the header. Were that not true the
     * header would be attacker-controlled and the rate limit would be trivially bypassed by
     * varying it, so the deployment assumption is worth stating rather than implying.
     *
     * <p>The first entry is the original client; the rest are proxies that added themselves.
     */
    private static String clientIpOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).strip();
            if (!first.isEmpty()) {
                return truncate(first, 45);
            }
        }
        return request.getRemoteAddr();
    }

    /** Truncated before storage: the header is caller-controlled and the column is not a log. */
    private static String userAgentOf(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        return agent == null || agent.isBlank() ? null : truncate(agent.strip(), 400);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
