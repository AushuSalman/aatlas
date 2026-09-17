/**
 * Overview snapshot, region and store intel, product scores, demographics.
 *
 * <p>Application module. Types in this package root are the public API other
 * modules may depend on; everything under it is internal.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "insights",
        allowedDependencies = {"analytics", "common", "decisions", "history"})
package com.aatlas.insights;
