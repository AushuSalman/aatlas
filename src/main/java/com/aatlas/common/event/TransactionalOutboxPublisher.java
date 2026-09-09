package com.aatlas.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The one implementation the application uses.
 *
 * <p>It hands the event to Spring, where Spring Modulith persists it to the
 * {@code event_publication} table inside the current transaction. After commit, the
 * registry delivers it to every {@link org.springframework.modulith.ApplicationModuleListener}
 * and to the externalisation bridge (Redis Streams or Kafka, per profile). One code path,
 * one guarantee, whichever broker is behind it.
 */
@Component
public class TransactionalOutboxPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionalOutboxPublisher.class);

    private final ApplicationEventPublisher delegate;

    public TransactionalOutboxPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        log.debug("Publishing {} for tenant {}", event.type(), event.tenantId());
        delegate.publishEvent(event);
    }
}
