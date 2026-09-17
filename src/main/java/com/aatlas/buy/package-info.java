/**
 * Landed-cost panel, buy intel, procurement plan, negotiation and bulk awards.
 *
 * <p>Application module. Types in this package root are the public API other
 * modules may depend on; everything under it is internal.
 *
 * <p><b>{@code allowedDependencies} does not cover all of this module's coupling.</b> It
 * constrains Java references, and Modulith can only see those. {@code CatalogGateway} and
 * {@code SupplierGateway} read {@code products}, {@code stores}, {@code product_stores},
 * {@code suppliers} and {@code supplier_products} with plain SQL - tables owned by {@code
 * catalog} and {@code suppliers}. That is a real dependency, deliberately taken because
 * neither module exposes a reader yet, but it is invisible here: a column renamed in {@code
 * catalog} breaks this module at runtime and the modularity build stays green. Both
 * gateways carry a {@code TODO(merge)} to be replaced by a public reader, which is what
 * would put the dependency back where this annotation can see it.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "buy",
        allowedDependencies = {"approvals", "common", "decisions", "history", "policy"})
package com.aatlas.buy;
