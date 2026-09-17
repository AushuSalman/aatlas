/**
 * Ask Aatlas: natural-language intent routing over the other modules' intelligence. Port
 * of {@code src/lib/intel/assistant.ts}'s {@code ask()}.
 *
 * <p>{@code POST /assistant/ask}, {@code GET /assistant/suggestions} (persona-aware via
 * {@code policy}'s {@link com.aatlas.policy.PolicyReader}), {@code GET /assistant/history}
 * - migration V13's {@code assistant_question} table.
 *
 * <p>Depends on {@code bulk}'s public {@link com.aatlas.bulk.SellLineReader} and
 * {@link com.aatlas.bulk.BuyLineReader} for the two intents that need per-item
 * recommendations, which is the real engines' current stand-in; every intent this module
 * cannot honestly compute from those two readers and its own tiny product/store lookup is
 * a small, explicitly-labelled simplification of another track's engine rather than a
 * guess at its shape - see {@link com.aatlas.assistant.internal.AssistantService}'s class
 * doc for exactly which.
 *
 * <p>Application module. Types in this package root are the public API other
 * modules may depend on; everything under it is internal. This module currently has no
 * public types - no other module calls the assistant.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "assistant",
        allowedDependencies = {"bulk", "common", "history", "policy"})
package com.aatlas.assistant;
