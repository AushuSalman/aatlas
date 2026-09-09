package com.aatlas.common.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds {@link TenantContext} for the duration of a request from the validated JWT.
 *
 * <p>Runs after the security filter chain has authenticated the token, so the claims
 * here are ones the server signed. Also seeds the MDC, which is what makes
 * "p95 per endpoint per tenant" answerable from the logs.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String CLAIM_TENANT = "tid";
    public static final String CLAIM_ROLE = "role";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean bound = false;

        if (authentication instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            String tenantId = jwt.getClaimAsString(CLAIM_TENANT);
            if (tenantId != null) {
                TenantContext.set(new TenantContext.Actor(
                        UUID.fromString(tenantId),
                        UUID.fromString(jwt.getSubject()),
                        jwt.getClaimAsString(CLAIM_ROLE)));
                MDC.put("tenantId", tenantId);
                MDC.put("userId", jwt.getSubject());
                bound = true;
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            if (bound) {
                TenantContext.clear();
                MDC.remove("tenantId");
                MDC.remove("userId");
            }
        }
    }
}
