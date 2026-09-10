package com.aatlas.policy;

import java.util.List;
import java.util.UUID;

/**
 * Seat policy, as the other modules read it.
 *
 * <p>The eight personas are rows in {@code role_policy}: a platform default per seat, and
 * an optional override per tenant per seat. Callers never see the rows - they get the
 * merged answer for one tenant, which is the only question anyone asks.
 *
 * <p>Takes the role as its wire value ({@code purchase-head}) rather than the
 * {@code SeatRole} enum on purpose: {@code identity} depends on this module for
 * {@code /me}, so this module cannot depend on {@code identity} without a cycle, and the
 * JWT carries the wire value anyway.
 */
public interface PolicyReader {

    /**
     * The persona for one seat in one tenant: the tenant's override if it has one, the
     * platform default otherwise.
     *
     * @throws com.aatlas.common.error.ApiException 400 if the role is not one of the eight
     */
    Persona personaFor(UUID tenantId, String role);

    /** All eight, in the order the seat picker shows them. */
    List<Persona> personasFor(UUID tenantId);
}
