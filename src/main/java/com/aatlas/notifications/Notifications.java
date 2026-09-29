package com.aatlas.notifications;

import java.util.UUID;

/** Tell a tenant something, in the bell. */
public interface Notifications {

    /**
     * @param kind  what it is about, e.g. {@code competitor-prices}
     * @param title one line, shown bold
     * @param body  one or two sentences, nullable
     * @param link  the in-app path it opens, e.g. {@code /app/sell?panel=competition}, nullable
     */
    void publish(UUID tenantId, String kind, String title, String body, String link);
}
