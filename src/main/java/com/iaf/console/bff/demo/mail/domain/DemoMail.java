package com.iaf.console.bff.demo.mail.domain;

import java.time.Instant;

/** One notification email the demo collected instead of sending: whom to, what it said, and the notification it carried. */
public record DemoMail(String to, String subject, String text, String link, String notificationId, String kind, String accountId, Instant sentAt) {
}
