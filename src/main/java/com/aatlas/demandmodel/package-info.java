/**
 * The demand model: a machine-learned forecaster, one per tenant, fitted to that tenant's own
 * sales history and nothing else.
 *
 * <p>Where the pricing chain estimates price sensitivity with a log-log regression over monthly
 * sales ({@link com.aatlas.history.SalesHistory#elasticity}), this module trains a random forest
 * of regression trees (Tribuo, pure Java) on weekly rows per item and branch: the price that
 * week, how far it sat from the item's usual price, the move from the week before, the margin
 * over cost, the competitor median where one was observed, the previous weeks' demand, and the
 * season. It predicts units a week. From that one model the chains can read three things the
 * regression only approximates: a demand forecast, a price sensitivity (the model probed across a
 * price grid), and a full profit curve.
 *
 * <p>Honesty is built in. Every training run holds back the latest weeks, forecasts them, and
 * scores the model against a naive baseline (the trailing mean) item by item. A pair where the
 * model did not beat the baseline is reported as such and is not to be used; the serving side
 * reads {@code usable}, so a tenant never gets a worse answer than the rule engine gives today.
 * The model, its per-pair report and the context needed to forecast from are stored per tenant
 * ({@code demand_models}); it retrains nightly and on demand.
 *
 * <p>Application module. Types in this package root are the public API other modules may depend
 * on ({@link com.aatlas.demandmodel.DemandModels}); everything under {@code internal} is
 * implementation. Depends on {@code history} for the catalogue and the regression it is compared
 * with, and on {@code policy} for who may train on demand.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "demandmodel",
        allowedDependencies = {"common", "history", "policy"})
package com.aatlas.demandmodel;
