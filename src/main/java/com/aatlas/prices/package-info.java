/**
 * "Set your prices": suggested list prices for items with no price yet, and the bulk
 * write that applies them.
 *
 * <p>Application module. Suggestions are built from the cost ladder, the reference
 * gross-margin benchmarks, the tenant's own peer band and any competitor observations,
 * with the basis spelled out so a prospect can read exactly where a number came from. The
 * write itself goes through {@code history.PriceBook}, which owns {@code product_prices},
 * and is recorded as one bulk-sell decision through {@code decisions.DecisionRecorder} so
 * History and the Overview see it.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "prices",
        allowedDependencies = {"common", "decisions", "history"})
package com.aatlas.prices;
