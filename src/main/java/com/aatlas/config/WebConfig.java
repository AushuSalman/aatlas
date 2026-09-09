package com.aatlas.config;

import com.aatlas.common.tenant.TenantContextFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Servlet-layer registration.
 *
 * <p>{@link TenantContextFilter} is registered here, explicitly, rather than annotated
 * {@code @Component}. Two reasons, and the second is not theoretical:
 *
 * <ol>
 *   <li>Order matters. The filter reads a JWT that Spring Security has already validated,
 *       so it must run after the security chain. Saying so here is clearer than a
 *       {@code @Order} on the class and an assumption about what else is registered.</li>
 *   <li>A filter must not be proxied. Spring Modulith's observability wraps beans that
 *       live in application modules so it can trace calls between them. CGLIB cannot
 *       override {@code GenericFilterBean.init()} because it is final, so the proxy's
 *       {@code init} runs against uninitialised state and the filter fails to start —
 *       taking Tomcat down with it. Constructing the filter here keeps it off the
 *       proxying path entirely.</li>
 * </ol>
 */
@Configuration
public class WebConfig {

    @Bean
    FilterRegistrationBean<TenantContextFilter> tenantContextFilter() {
        FilterRegistrationBean<TenantContextFilter> registration =
                new FilterRegistrationBean<>(new TenantContextFilter());
        registration.addUrlPatterns("/api/*");
        // After Spring Security's chain, which sits at LOWEST_PRECEDENCE - 100 by default,
        // so the JWT is already validated by the time the tenant is read from it.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 50);
        registration.setName("tenantContextFilter");
        return registration;
    }
}
