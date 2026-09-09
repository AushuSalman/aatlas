/**
 * Shared infrastructure configuration: security, JWT keys, caching, persistence,
 * scheduling, JSON, OpenAPI and the clock.
 *
 * <p>A shared module. Every business module may depend on it; it may depend on none of
 * them, which is what stops configuration from quietly becoming a second place where
 * business rules live.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "config",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.aatlas.config;
