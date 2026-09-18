package com.aatlas.identity.internal;

import com.aatlas.policy.PolicyReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * What the Users screen decides, enforced on every authenticated request.
 *
 * <p>Two things an access token cannot know, because it was minted before the change:
 * <ul>
 *   <li>The account was suspended or removed. Answered 401, which the frontend treats as
 *       "the session is over" and signs out - suspension takes effect on the next click,
 *       not when the fifteen-minute token runs out.
 *   <li>An admin took a module away. The module's API is closed to them (403), so hiding
 *       it in the rail is not the only thing standing in the way.
 * </ul>
 *
 * <p>Only the module endpoints that belong to one module are guarded. Branches, products
 * and the supplier list are also read by other screens' pickers, so closing them would
 * break Sell for someone without Branches; their own screens are closed by the frontend's
 * route guard instead.
 *
 * <p>Looked up per user, cached for thirty seconds and evicted on every change the Users
 * screen makes, so a change is seen immediately on this pod and within the TTL on others.
 */
@Component
class UserAccessGuard extends OncePerRequestFilter {

    private record Access(UserStatus status, List<String> modules, boolean bulk, boolean guardrails) {
    }

    /** Path prefix to the module that owns it. Checked in order; the first match wins. */
    private static final Map<String, String> MODULE_PATHS = new LinkedHashMap<>();

    static {
        MODULE_PATHS.put("/api/v1/sell", "sell");
        MODULE_PATHS.put("/api/v1/buy", "buy");
        MODULE_PATHS.put("/api/v1/insights", "insights");
        MODULE_PATHS.put("/api/v1/analytics", "insights");
    }

    private final UserAccountRepository users;
    private final PolicyReader policy;
    private final ObjectMapper json;
    private final Cache<UUID, Optional<Access>> cache = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    UserAccessGuard(UserAccountRepository users, PolicyReader policy, ObjectMapper json) {
        this.users = users;
        this.policy = policy;
        this.json = json;
    }

    void evict(UUID userId) {
        cache.invalidate(userId);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token) || token.getToken().getClaimAsString("tid") == null) {
            chain.doFilter(request, response);
            return;
        }

        UUID userId = UUID.fromString(token.getToken().getSubject());
        Optional<Access> access = cache.get(userId, this::load);
        if (access.isEmpty() || access.get().status() != UserStatus.ACTIVE) {
            refuse(response, HttpStatus.UNAUTHORIZED, "account_inactive",
                    "This account has been suspended or removed. Contact your workspace admin.");
            return;
        }

        String path = request.getRequestURI();
        String module = MODULE_PATHS.entrySet().stream()
                .filter(e -> path.equals(e.getKey()) || path.startsWith(e.getKey() + "/"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        Access a = access.get();
        if (module != null && !a.modules().contains(module)) {
            refuse(response, HttpStatus.FORBIDDEN, "not_allowed", "Your access does not include this module.");
            return;
        }
        if (module != null && path.contains("/bulk") && !a.bulk()) {
            refuse(response, HttpStatus.FORBIDDEN, "not_allowed", "Your access does not include bulk actions.");
            return;
        }
        if (path.startsWith("/api/v1/guardrails") && !"GET".equals(request.getMethod()) && !a.guardrails()) {
            refuse(response, HttpStatus.FORBIDDEN, "not_allowed", "Your access does not include changing guardrails.");
            return;
        }
        chain.doFilter(request, response);
    }

    private Optional<Access> load(UUID userId) {
        return users.findById(userId).map(u -> {
            UserPermissions p = u.getPermissions() != null
                    ? u.getPermissions()
                    : UserPermissions.of(policy.personaFor(u.getTenantId(), u.getSeatRole().wireValue()));
            return new Access(u.getStatus(), p.modules(), p.bulk(), p.guardrails());
        });
    }

    private void refuse(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://docs.aatlas.io/errors/" + code);
        body.put("title", code);
        body.put("status", status.value());
        body.put("detail", message);
        body.put("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }
}
