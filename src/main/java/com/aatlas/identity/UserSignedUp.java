package com.aatlas.identity;

import com.aatlas.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A company and its first user now exist.
 *
 * <p>Published through the transactional outbox, so it commits with the rows it describes
 * and is relayed afterwards. The onboarding work it will trigger - provisioning the sample
 * dataset, warming the reference caches, the welcome mail - is deliberately not done on
 * the signup request thread: none of it is something the person waits for, and a slow
 * mail server must not be able to fail an account creation that already succeeded.
 *
 * @param tenantId the company created
 * @param userId its first user, who is also its owner
 * @param email the address as typed, for the welcome mail
 * @param seatRole the seat chosen at step two of the form
 * @param occurredAt from {@code AatlasClock}, never {@code Instant.now()}
 */
public record UserSignedUp(UUID tenantId, UUID userId, String email, SeatRole seatRole, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String type() {
        return "identity.user.signed-up";
    }
}
