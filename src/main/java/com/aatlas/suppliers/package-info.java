/**
 * Supplier panel, commercial terms, ratings, reviews, lookup and risk.
 *
 * <p>Application module. Types in this package root are the public API other
 * modules may depend on; everything under it is internal.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "suppliers",
        allowedDependencies = {"common", "policy"})
package com.aatlas.suppliers;
