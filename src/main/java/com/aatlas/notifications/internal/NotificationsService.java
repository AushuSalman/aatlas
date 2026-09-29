package com.aatlas.notifications.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The bell's reads and read-marks, for the signed-in person. */
@Service
class NotificationsService {

    private final NotificationsJdbc notifications;

    NotificationsService(NotificationsJdbc notifications) {
        this.notifications = notifications;
    }

    record View(int unread, List<NotificationsJdbc.Row> items) {
    }

    View list() {
        List<NotificationsJdbc.Row> rows = notifications.latest(TenantContext.requireTenantId(), user());
        return new View((int) rows.stream().filter(r -> !r.read()).count(), rows);
    }

    void read(UUID id) {
        notifications.markRead(TenantContext.requireTenantId(), user(), id);
    }

    void readAll() {
        notifications.markAllRead(TenantContext.requireTenantId(), user());
    }

    private static UUID user() {
        return TenantContext.currentUserId()
                .orElseThrow(() -> ApiException.forbidden("Notifications are read by a signed-in person."));
    }
}
