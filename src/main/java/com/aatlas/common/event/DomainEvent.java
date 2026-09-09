package com.aatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A fact that has already happened, committed in the same transaction as the row that
 * caused it (transactional outbox) and published afterwards.
 *
 * <p>Consumers are the engine workers: a recorded deal recomputes the lines it touched
 * and drops the affected cache keys. Because the event and the row commit together, a
 * decision can neither be lost nor counted twice.
 */
public interface DomainEvent {

    /** Routing key, e.g. {@code deal.recorded}, {@code decision.applied}, {@code import.done}. */
    String type();

    /** Which tenant it happened in. Every consumer scopes its work by this. */
    UUID tenantId();

    /** When it happened, from {@link com.aatlas.common.time.AatlasClock}. */
    Instant occurredAt();
}
