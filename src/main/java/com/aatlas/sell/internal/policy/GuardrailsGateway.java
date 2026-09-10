package com.aatlas.sell.internal.policy;

/**
 * The tenant's pricing guardrails, as {@code applyGuardrails}, {@code speedPricing} and the
 * customer-quote screen read them.
 *
 * <p>WAVE2-BRIEF says a real, already-merged public reader exists on {@code policy} for
 * this ("check wave 1's policy module... depend on that module's package-root type
 * directly"). It does not: {@code policy}'s package root has {@link
 * com.aatlas.policy.PolicyReader} (seat personas) and {@link
 * com.aatlas.policy.GuardrailsChanged} (a change event), but {@code GuardrailsService},
 * {@code GuardrailsView} and the {@code pricing_guardrails} entity are all
 * package-private in {@code policy.internal} - there is no root-level type that answers
 * "what are this tenant's guardrails right now". Depending on the internal one would fail
 * {@code ModularityTests}, so this is the same stand-in every other cross-track dependency
 * in this wave gets: reads the {@code pricing_guardrails} table policy's own V6 migration
 * created (tenant-scoped, the same table {@code GuardrailsService} reads), falling back to
 * {@code seed/guardrails.json} exactly the way {@code GuardrailsService.current()} does when
 * a tenant has never saved.
 *
 * <p>TODO(merge): replace with a real {@code com.aatlas.policy.GuardrailsReader} once
 * {@code policy} exposes one at its package root.
 */
public interface GuardrailsGateway {

    GuardrailValues current();
}
