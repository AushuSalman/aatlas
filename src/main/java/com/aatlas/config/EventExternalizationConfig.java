package com.aatlas.config;

import com.aatlas.common.event.DomainEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

/**
 * Where a committed domain event goes after the transaction closes.
 *
 * <p>Spring Modulith writes each event to {@code event_publication} inside the caller's
 * transaction, then publishes it. This mapping decides the broker destination: the event's
 * own {@link DomainEvent#type()} becomes the topic or stream name, so
 * {@code deal.recorded} lands on a topic of that name and the engine workers subscribe by
 * name rather than by class.
 *
 * <p>That indirection is the whole point of the blueprint's "thin publisher interface":
 * Redis Streams at launch, Kafka when the volume justifies it, and no application code
 * changes either way.
 */
@Configuration
public class EventExternalizationConfig {

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .selectByType(DomainEvent.class)
                // Target = the event's own name; key = the tenant, so everything that happens
                // to one tenant lands on one partition and is consumed in the order it occurred.
                // Two decisions on the same line recomputing out of order would leave the
                // snapshot showing the older price.
                .route(DomainEvent.class, event -> RoutingTarget
                        .forTarget(event.type())
                        .andKey(event.tenantId().toString()))
                .build();
    }
}
