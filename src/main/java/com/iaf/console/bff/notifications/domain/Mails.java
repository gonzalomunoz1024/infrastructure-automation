package com.iaf.console.bff.notifications.domain;

/**
 * The email a notification makes: to the person's identity-provider address, the notification's
 * title as the subject, its text, and the absolute address that opens it in the console.
 */
public final class Mails {

    public static final String SUBJECT_PREFIX = "[Console] ";

    /** One plain-text email. */
    public record Mail(String to, String subject, String text, String link) {
    }

    private Mails() {
    }

    public static Mail of(Recipient to, Notification n, String baseUrl) {
        if (!to.hasAddress()) {
            throw new IllegalArgumentException("the person has no address to write to");
        }
        String link = Links.absolute(baseUrl, n.link());
        StringBuilder text = new StringBuilder();
        text.append("Hello ").append(to.displayName()).append(",\n\n");
        text.append(n.title()).append('\n');
        if (n.body() != null && !n.body().isBlank()) {
            text.append('\n').append(n.body()).append('\n');
        }
        text.append("\nOpen it in the console: ").append(link).append('\n');
        text.append("\nAccount ").append(n.accountId()).append(". You get this email because your notification settings send ")
                .append(NotificationKinds.TEST.equals(n.kind()) ? "a test" : n.kind()).append(" by email; change them at ")
                .append(Links.absolute(baseUrl, Links.settings())).append('\n');
        return new Mail(to.email(), SUBJECT_PREFIX + n.title(), text.toString(), link);
    }
}
