package com.aatlas.identity.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
@Tag(name = "Authentication", description = "Signup, sign-in, refresh, sign-out and password reset. No token required.")
class AuthController {

    private final SignupService signupService;
    private final SessionService sessionService;
    private final PasswordResetService passwordResetService;

    private final EmailVerificationService emailVerificationService;

    AuthController(
            SignupService signupService,
            SessionService sessionService,
            PasswordResetService passwordResetService,
            EmailVerificationService emailVerificationService,
            SsoService ssoService,
            UserManagementService userManagement,
            @Value("${aatlas.mail.app-url:http://localhost:3000}") String appUrl) {
        this.userManagement = userManagement;
        this.signupService = signupService;
        this.sessionService = sessionService;
        this.passwordResetService = passwordResetService;
        this.emailVerificationService = emailVerificationService;
        this.ssoService = ssoService;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    private final SsoService ssoService;
    private final String appUrl;
    private final UserManagementService userManagement;

    @Operation(summary = "Read an invitation before accepting it",
            description = "Who invited them, to which company, and the email the account will use.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The invitation is live."),
        @ApiResponse(responseCode = "400", description = "invalid_invitation: expired, used or withdrawn.")
    })
    @PostMapping(path = "/invitations/lookup", consumes = MediaType.APPLICATION_JSON_VALUE)
    UserManagementService.InvitationView lookupInvitation(@Valid @RequestBody UserRequests.Lookup request, HttpServletRequest http) {
        return userManagement.lookup(request.token(), clientIpOf(http));
    }

    @Operation(summary = "Accept an invitation", description = "Sets the password, activates the account and signs in.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Signed in."),
        @ApiResponse(responseCode = "400", description = "invalid_invitation, or weak_password.")
    })
    @PostMapping(path = "/invitations/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    AuthResponse acceptInvitation(@Valid @RequestBody UserRequests.Accept request, HttpServletRequest http) {
        return userManagement.accept(request.token(), request.password(), clientIpOf(http), userAgentOf(http));
    }

    @Operation(summary = "Verify a Google or Apple sign-in",
            description = """
                    Exchanges the authorization code (with the PKCE verifier), verifies the ID token's
                    signature, issuer, audience, expiry and nonce, and returns the identity with a
                    `ticket`. The ticket - not the identity - is what sign-in and signup accept.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Verified."),
        @ApiResponse(responseCode = "400", description = "sso_exchange_failed, sso_no_email or unknown_provider."),
        @ApiResponse(responseCode = "503", description = "sso_not_configured.")
    })
    @PostMapping(path = "/sso/{provider}", consumes = MediaType.APPLICATION_JSON_VALUE)
    SsoService.IdentityView exchangeSso(
            @PathVariable String provider, @Valid @RequestBody SsoRequests.Exchange request, HttpServletRequest http) {
        return ssoService.exchange(provider, request, clientIpOf(http));
    }

    @Operation(summary = "Sign in with a verified Google or Apple identity",
            description = "404 sso_not_linked when no account uses this identity; the ticket then stays valid for signup.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Signed in."),
        @ApiResponse(responseCode = "400", description = "sso_ticket_invalid."),
        @ApiResponse(responseCode = "404", description = "sso_not_linked.")
    })
    @PostMapping(path = "/sso/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    AuthResponse ssoLogin(@Valid @RequestBody SsoRequests.Login request, HttpServletRequest http) {
        return ssoService.login(request.ticket(), clientIpOf(http), userAgentOf(http));
    }

    /**
     * Apple's redirect_uri. Asking Apple for a name and email forces a form POST, which a
     * static frontend cannot receive, so it lands here and is passed on as a query string.
     * Nothing is verified here - the frontend checks the state, and the exchange verifies
     * the code - this only changes the envelope.
     */
    @Operation(summary = "Apple's form_post redirect", description = "Redirects to the frontend's /auth/callback with the same parameters.")
    @PostMapping(path = "/sso/apple/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> appleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String user) {
        UriComponentsBuilder target = UriComponentsBuilder.fromUriString(appUrl + "/auth/callback")
                .queryParam("provider", "apple");
        if (code != null) {
            target.queryParam("code", code);
        }
        if (state != null) {
            target.queryParam("state", state);
        }
        if (error != null) {
            target.queryParam("error", error);
        }
        String name = appleName(user);
        if (name != null) {
            target.queryParam("name", name);
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(target.encode().build().toUri())
                .build();
    }

    /** {@code {"name":{"firstName":"Sam","lastName":"Okafor"},"email":"…"}}, sent on the first authorisation only. */
    private String appleName(String userJson) {
        if (userJson == null || userJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode name = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(userJson).path("name");
            String full = (name.path("firstName").asText("") + " " + name.path("lastName").asText("")).strip();
            return full.isEmpty() ? null : truncate(full, 120);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }

    @Operation(summary = "Mail a verification code",
            description = "Sends a six-digit code, valid for ten minutes and five attempts. The code is never in the response.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Code sent. The challenge id is what confirm and signup take."),
        @ApiResponse(responseCode = "409", description = "email_taken: the address already has an account."),
        @ApiResponse(responseCode = "429", description = "rate_limited, with retryAfterSeconds."),
        @ApiResponse(responseCode = "503", description = "mail_failed: the mail server did not accept the message.")
    })
    @PostMapping(path = "/email/verify/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    EmailVerificationService.ChallengeView startVerification(
            @Valid @RequestBody VerificationRequests.Start request, HttpServletRequest http) {
        return emailVerificationService.start(request.email(), request.fullName(), clientIpOf(http));
    }

    @Operation(summary = "Send a new verification code",
            description = "Retires the previous code and resets the attempts. Waits 30s, 60s, 120s, then 300s between resends.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "A new code was sent."),
        @ApiResponse(responseCode = "400", description = "code_expired: the challenge is unknown, verified or spent."),
        @ApiResponse(responseCode = "429", description = "rate_limited, with retryAfterSeconds.")
    })
    @PostMapping(path = "/email/verify/resend", consumes = MediaType.APPLICATION_JSON_VALUE)
    EmailVerificationService.ChallengeView resendVerification(
            @Valid @RequestBody VerificationRequests.Resend request, HttpServletRequest http) {
        return emailVerificationService.resend(request.challengeId(), clientIpOf(http));
    }

    @Operation(summary = "Confirm a verification code",
            description = "On success the challenge id may be passed to signup as verificationId, within the hour.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Verified."),
        @ApiResponse(responseCode = "400", description = "invalid_code (with attemptsLeft), code_expired or too_many_attempts.")
    })
    @PostMapping(path = "/email/verify/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmVerification(@Valid @RequestBody VerificationRequests.Confirm request, HttpServletRequest http) {
        emailVerificationService.confirm(request.challengeId(), request.code(), clientIpOf(http));
    }

    @Operation(
            summary = "Create a company and its first user",
            description =
                    """
                    Creates the tenant, its settings, its owner, and a signed-in session, in one transaction.

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

    @Operation(summary = "Sign in with email and password",
            description = "Same response as signup. A wrong email and a wrong password are the same 401.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Signed in."),
        @ApiResponse(responseCode = "401", description = "invalid_credentials, or account_locked after ten failures."),
        @ApiResponse(responseCode = "429", description = "Too many attempts from this connection.")
    })
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return sessionService.login(request, clientIpOf(http), userAgentOf(http));
    }

    @Operation(summary = "Exchange a refresh token for a new pair",
            description = "The presented token is spent. Presenting it again revokes every session for the account.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "New access and refresh tokens."),
        @ApiResponse(responseCode = "401", description = "invalid_refresh_token, refresh_expired or refresh_reused.")
    })
    @PostMapping(path = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    AuthResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return sessionService.refresh(request.refreshToken(), clientIpOf(http), userAgentOf(http));
    }

    @Operation(summary = "Sign out", description = "Revokes the refresh token. Always 204; sign-out is idempotent.")
    @PostMapping(path = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest request) {
        sessionService.logout(request.refreshToken());
    }

    @Operation(summary = "Send a password reset link",
            description = "Always 202, whether or not the address has an account. The mail is the only answer.")
    @PostMapping(path = "/password/forgot", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        passwordResetService.forgot(request.email(), clientIpOf(http));
    }

    @Operation(summary = "Set a new password from a reset token",
            description = "Single use, one hour. Signs the account out everywhere.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Password changed."),
        @ApiResponse(responseCode = "400", description = "invalid_reset_token, or weak_password.")
    })
    @PostMapping(path = "/password/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        passwordResetService.reset(request.token(), request.password(), clientIpOf(http));
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
