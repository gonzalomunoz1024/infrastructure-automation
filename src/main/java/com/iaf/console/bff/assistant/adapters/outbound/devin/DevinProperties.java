package com.iaf.console.bff.assistant.adapters.outbound.devin;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The firm's Devin, the copilot's assistant when {@code console.assistant.provider} is
 * {@code devin}: its API at {@code base-url}, called with {@code api-key}, which comes from the
 * environment ({@code CONSOLE_ASSISTANT_DEVIN_API_KEY}, the Helm chart's secret) and never from a
 * file in the repository. A turn's session is read every {@code poll-interval}; one call that gets
 * no answer within {@code request-timeout} counts as a failed call. {@code max-acu-limit} caps what
 * one session may spend (0 leaves Devin's own limit).
 */
@ConfigurationProperties(prefix = "console.assistant.devin")
public record DevinProperties(
        @DefaultValue("https://api.devin.ai") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("3s") Duration pollInterval,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("0") int maxAcuLimit) {

    public DevinProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.devin.ai" : baseUrl.strip().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.strip();
        if (maxAcuLimit < 0) {
            throw new IllegalArgumentException("console.assistant.devin.max-acu-limit is 0 (none) or more");
        }
    }

    /** Whether there is a key to call Devin with. */
    public boolean hasKey() {
        return !apiKey.isEmpty();
    }

    /** The settings without the key, as they may be logged. */
    @Override
    public String toString() {
        return "DevinProperties[baseUrl=" + baseUrl + ", apiKey=" + (hasKey() ? "(set)" : "(none)") + ", pollInterval=" + pollInterval
                + ", requestTimeout=" + requestTimeout + ", maxAcuLimit=" + maxAcuLimit + "]";
    }
}
