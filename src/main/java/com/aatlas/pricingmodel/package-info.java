/**
 * The pricing model's settings: which steps of the recommendation chain a tenant has
 * switched off and which knobs they have turned, with the change history and a view of
 * what the model has learned from their decisions.
 *
 * <p>This module owns only the tenant's <em>overrides</em> ({@code pricing_model_settings}
 * and {@code pricing_model_history}). The registry - every parameter, its default and its
 * range - is {@link com.aatlas.history.PricingModel}, and the engines never read this
 * module: they get the tenant's model through
 * {@link com.aatlas.history.Reference#pricingModel()}, which reads the same row per
 * request so a save here shows on the next recommendation. The same split as the
 * guardrails: {@code policy} owns the row, {@code history.Reference} is how engines read it.
 *
 * <p>Who may write is the seat's {@code guardrails} permission from {@code policy}, the
 * same seats that may move the margin guardrails. The learning view reads the tenant's
 * deals through {@code decisions} and reduces them with
 * {@link com.aatlas.history.DecisionPatterns}, the pure maths the chain itself uses.
 *
 * <p>Application module. Types in this package root are the public API other modules may
 * depend on; everything under {@code internal} is implementation.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "pricingmodel",
        allowedDependencies = {"common", "history", "policy", "decisions"})
package com.aatlas.pricingmodel;
