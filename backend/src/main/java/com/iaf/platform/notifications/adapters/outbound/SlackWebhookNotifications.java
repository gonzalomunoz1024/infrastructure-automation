package com.iaf.platform.notifications.adapters.outbound;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.notifications.application.NotificationProperties;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.outbound.NotificationPort;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Chat delivery (profile {@code slack}): a Slack-compatible incoming webhook per channel
 * ({@code platform.notifications.channels.<name>.webhook}, else {@code slack.default-webhook}),
 * posting {@code {"text": ...}} in Slack's mrkdwn. Mattermost and Rocket.Chat accept the same
 * payload. The webhook URL is a secret: configured as a {@code vault:} reference, resolved per
 * message, and never logged or put in an error. A notification without a channel, or for a channel
 * without a webhook, is not posted.
 */
@Component
@Profile("slack")
@Primary
public class SlackWebhookNotifications implements NotificationPort {

    private final NotificationProperties props;
    private final CredentialsPort credentials;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public SlackWebhookNotifications(NotificationProperties props, CredentialsPort credentials, ObjectMapper json) {
        this(props, credentials, json, HttpClient.newBuilder().connectTimeout(props.getSlack().getTimeout()).build());
    }

    SlackWebhookNotifications(NotificationProperties props, CredentialsPort credentials, ObjectMapper json, HttpClient http) {
        requireReference("platform.notifications.slack.default-webhook", props.getSlack().getDefaultWebhook());
        for (Map.Entry<String, NotificationProperties.Channel> c : props.getChannels().entrySet()) {
            requireReference("platform.notifications.channels." + c.getKey() + ".webhook", c.getValue().getWebhook());
        }
        this.props = props;
        this.credentials = credentials;
        this.json = json;
        this.http = http;
    }

    @Override
    public String name() {
        return "slack";
    }

    @Override
    public Mono<Boolean> deliver(Notification n) {
        String ref = webhookFor(n.channel());
        if (ref == null) {
            return Mono.just(false);
        }
        return credentials.resolve(ref)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("the webhook reference " + ref + " resolves to nothing")))
                .flatMap(url -> Mono.fromCallable(() -> post(url, n)).subscribeOn(Schedulers.boundedElastic()));
    }

    /** The channel's own webhook, else the default one; null when the channel has neither (or there is no channel). */
    String webhookFor(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        String own = props.channel(channel).map(NotificationProperties.Channel::getWebhook).filter(w -> !w.isBlank()).orElse(null);
        if (own != null) {
            return own;
        }
        String fallback = props.getSlack().getDefaultWebhook();
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    private boolean post(String url, Notification n) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(json.createObjectNode().put("text", text(n)))))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : response.body().replaceAll("\\s+", " ").trim();
            throw new IOException("the webhook for " + n.channel() + " answered " + response.statusCode()
                    + (body.isEmpty() ? "" : ": " + (body.length() > 200 ? body.substring(0, 200) : body)));
        }
        return true;
    }

    /** Slack mrkdwn: the title in bold, the message, and a link back to the platform. */
    String text(Notification n) {
        StringBuilder t = new StringBuilder();
        if (!"info".equals(n.severity())) {
            t.append('[').append(escape(n.severity())).append("] ");
        }
        t.append('*').append(escape(n.title() == null ? n.kind() : n.title())).append('*');
        if (n.message() != null && !n.message().isBlank()) {
            t.append('\n').append(escape(n.message()));
        }
        String link = props.absolute(n.link());
        if (link != null && !link.isBlank()) {
            t.append("\n<").append(link).append("|Open in the platform>");
        }
        return t.toString();
    }

    private Duration timeout() {
        Duration d = props.getSlack().getTimeout();
        return d == null ? Duration.ofSeconds(10) : d;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void requireReference(String property, String value) {
        if (value != null && !value.isBlank() && !value.startsWith("vault:")) {
            throw new IllegalArgumentException(property + " must be a vault: reference to the webhook URL, never the URL itself");
        }
    }
}
