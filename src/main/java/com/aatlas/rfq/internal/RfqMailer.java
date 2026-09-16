package com.aatlas.rfq.internal;

/**
 * Sends a round's drafted message to one invited supplier. An interface for the same reason
 * {@code identity.internal.Mailer} is: the transport is a deployment choice ({@link
 * LogRfqMailer} today, SMTP through {@code spring-boot-starter-mail} once there is a real
 * inbox on the other end), and nothing in {@code RfqService} should know which.
 */
interface RfqMailer {

    void send(String toEmailOrName, String ref, String message);
}
