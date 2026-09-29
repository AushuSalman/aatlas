package com.aatlas.notifications.internal;

import com.aatlas.notifications.Notifications;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code notifications} and {@code notification_reads}. */
@Repository
class NotificationsJdbc implements Notifications {

    /** The bell lists this many, newest first. */
    static final int LATEST = 30;

    private final JdbcTemplate jdbc;

    NotificationsJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void publish(UUID tenantId, String kind, String title, String body, String link) {
        jdbc.update("insert into notifications (tenant_id, kind, title, body, link) values (?, ?, ?, ?, ?)",
                tenantId, kind, title.length() > 200 ? title.substring(0, 200) : title, body, link);
    }

    record Row(UUID id, String kind, String title, String body, String link, OffsetDateTime createdAt, boolean read) {
    }

    @Transactional(readOnly = true)
    List<Row> latest(UUID tenantId, UUID userId) {
        return jdbc.query("""
                select n.id, n.kind, n.title, n.body, n.link, n.created_at,
                       exists (select 1 from notification_reads r
                                where r.notification_id = n.id and r.user_id = ?) as read
                  from notifications n
                 where n.tenant_id = ?
                 order by n.created_at desc
                 limit ?
                """, (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("title"),
                        rs.getString("body"), rs.getString("link"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getBoolean("read")),
                userId, tenantId, LATEST);
    }

    @Transactional
    void markRead(UUID tenantId, UUID userId, UUID notificationId) {
        jdbc.update("""
                insert into notification_reads (tenant_id, notification_id, user_id)
                select tenant_id, id, ? from notifications where tenant_id = ? and id = ?
                on conflict (notification_id, user_id) do nothing
                """, userId, tenantId, notificationId);
    }

    @Transactional
    void markAllRead(UUID tenantId, UUID userId) {
        jdbc.update("""
                insert into notification_reads (tenant_id, notification_id, user_id)
                select tenant_id, id, ? from notifications where tenant_id = ?
                on conflict (notification_id, user_id) do nothing
                """, userId, tenantId);
    }
}
