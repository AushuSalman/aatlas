/**
 * Signup, login, JWT issuing and rotation, users, roles and seat policy.
 *
 * <p>Application module. Types in this package root are the public API other
 * modules may depend on; everything under it is internal.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "identity",
        allowedDependencies = {"common", "config", "ingest", "policy", "tenant"})
package com.aatlas.identity;
