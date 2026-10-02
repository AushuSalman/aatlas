/**
 * The supply-chain models: machine-learned, one set per tenant, fitted to that tenant's own
 * purchase history and nothing else.
 *
 * <p>The first is the <em>delivery model</em>: for a supplier, an item, a branch and a
 * quantity, how many days the order will really take and the chance it arrives late. Two
 * random forests of regression trees (Tribuo, pure Java) on the tenant's received purchase
 * orders: one predicts the slip, the days by which an order beat or missed what the supplier
 * promised; the other predicts late as 0 or 1, which a regression forest turns into a
 * probability. Each order's features are what was knowable when it was placed: the supplier,
 * its country, the item's category, the branch, the quantity, the promised lead time, and the
 * supplier's record over its previous orders (how often late, by how much, how long they took).
 *
 * <p>Honesty is built in. Every training run holds back the latest fifth of the orders,
 * predicts them, and scores each supplier against a naive baseline that knew the same record
 * (the supplier's trailing slip and late rate). A supplier where the model beat neither is
 * reported as such and is not to be used; the serving side reads {@code usable}, so the Buy
 * screens never get a worse answer than the supplier's own record gives today. The models,
 * the per-supplier report and the context a forecast starts from are stored per tenant
 * ({@code delivery_models}); they retrain nightly and on demand.
 *
 * <p>Application module. Types in this package root are the public API other modules may depend
 * on ({@link com.aatlas.supplymodel.DeliveryModels}); everything under {@code internal} is
 * implementation. Depends on {@code policy} for who may train on demand and on {@code history}
 * to drop the tenant's cached recommendations after a run, so Buy shows the new forecasts at once.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "supplymodel",
        allowedDependencies = {"common", "history", "policy"})
package com.aatlas.supplymodel;
