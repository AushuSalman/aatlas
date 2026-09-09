package com.aatlas.common.event;

/**
 * The thin publisher seam the blueprint calls for: Redis Streams at launch volumes,
 * Kafka when events pass a few thousand a second, and neither during a unit test.
 * Callers only ever see this interface, so the swap is a configuration change.
 *
 * <p>Publishing is transactional. The event is written to Spring Modulith's event
 * publication registry inside the caller's transaction and relayed after commit; if the
 * relay fails, the entry stays incomplete and is retried rather than silently dropped.
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}
