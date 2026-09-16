package com.aatlas.rfq.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the RFQ mail to the log instead of sending it - the only mailer there is until SMTP
 * is wired for this module, matching {@code identity.internal.LogMailer}'s own note: replace
 * with a real transport before this runs against anything but the sample supplier panel.
 */
@Component
class LogRfqMailer implements RfqMailer {

    private static final Logger log = LoggerFactory.getLogger(LogRfqMailer.class);

    @Override
    public void send(String toEmailOrName, String ref, String message) {
        log.info("[mail] To: {}\n[mail] Subject: {}\n{}", toEmailOrName, ref, message);
    }
}
