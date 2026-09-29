/**
 * Competition: live competitor prices from shopping-data providers, and the plain-words
 * account of how a price is set.
 *
 * <p>Application module. An item's description is searched on whichever providers have a key
 * configured - Google Shopping through SerpApi, eBay's Browse API, Amazon through Rainforest,
 * Google Shopping through Oxylabs - and every listing comes back with what was done with it:
 * kept, or dropped with the reason (off-topic title, another listing from the same seller,
 * far from the others, wrong currency). What is kept is written to {@code competitor_prices}
 * with the provider as its {@code source} and the listing's URL, so it is as citable as an
 * imported row and the pricing engines read it the same way: it becomes the competitor anchor.
 *
 * <p>Depends on {@code common} and {@code history} only: the catalogue, the competitor read
 * contract, the reference guardrails and the cache eviction that makes a new observation show
 * in the next recommendation.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "competition",
        allowedDependencies = {"common", "history"})
package com.aatlas.competition;
