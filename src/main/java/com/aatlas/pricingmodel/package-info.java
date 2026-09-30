/**
 * The pricing model's settings: which steps of the recommendation chains a tenant has
 * switched off and which knobs they have turned, with the change history and a view of
 * what the model has learned from their decisions. Two sides of one registry - the sell
 * model and the buying model - are served one at a time by a {@code side} parameter and
 * kept in one stored row, so a save on one side never touches the other.
 *
 * <p>This module owns only the tenant's row ({@code pricing_model_settings}: the hand-set
 * <em>overrides</em> and, beside them, the <em>learned</em> settings the model tuner
 * re-fitted from the tenant's own decisions and outcomes) and {@code pricing_model_history}.
 * The registry - every parameter of both sides, its default and its range - is
 * {@link com.aatlas.history.PricingModel}, and the engines never read this module: they get
 * the tenant's model through {@link com.aatlas.history.Reference#pricingModel()}, which
 * reads the same row per request and layers it (defaults, then learned, then hand-set) so a
 * save or a retune here shows on the next recommendation. The same split as the
 * guardrails: {@code policy} owns the row, {@code history.Reference} is how engines read it.
 *
 * <p>Who may write is the seat's {@code guardrails} permission from {@code policy}, the
 * same seats that may move the margin guardrails. The learning view and the tuner read the
 * tenant's deals and measured outcomes through {@code decisions} and reduce them with
 * {@link com.aatlas.history.DecisionPatterns}, the pure maths the chain itself uses. The
 * tuner runs nightly for every active tenant and on demand for one.
 *
 * <p>Application module. Types in this package root are the public API other modules may
 * depend on; everything under {@code internal} is implementation.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "pricingmodel",
        allowedDependencies = {"common", "history", "policy", "decisions"})
package com.aatlas.pricingmodel;
