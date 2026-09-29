/**
 * Notifications: what the bell in the header shows.
 *
 * <p>Application module. Any module tells the tenant something through {@link
 * com.aatlas.notifications.Notifications#publish}; the bell lists the latest, with read state
 * kept per person. Nothing is emailed - the bell is the only channel.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "notifications",
        allowedDependencies = {"common"})
package com.aatlas.notifications;
