package com.iaf.platform.audit.adapters.outbound;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.audit.application.SiemProperties;
import com.iaf.platform.audit.ports.outbound.SiemPort;
import com.iaf.platform.common.ports.CredentialsPort;

/**
 * Splunk HTTP Event Collector (profile {@code siem}, on when {@code platform.siem.hec.url} is set):
 * one {@code POST} per batch with one JSON event object per evidence entry, authenticated with
 * {@code Authorization: Splunk <token>}. The token is a Vault reference resolved at send time (the
 * credentials adapter caches it), so a rotated token is picked up without a restart; it is never
 * logged and never part of an error.
 */
@Component
@Profile("siem")
@Primary
@ConditionalOnExpression("!'${platform.siem.hec.url:}'.isBlank()")
public class SplunkHecSiem implements SiemPort {

    private final SiemProperties.Hec config;
    private final CredentialsPort credentials;
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI url;

    @Autowired
    public SplunkHecSiem(SiemProperties props, CredentialsPort credentials, ObjectMapper json) {
        this(props.getHec(), credentials, json, HttpClient.newBuilder().connectTimeout(props.getHec().getTimeout()).build());
    }

    SplunkHecSiem(SiemProperties.Hec config, CredentialsPort credentials, ObjectMapper json, HttpClient http) {
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new IllegalArgumentException("platform.siem.hec.url is required for the HEC export");
        }
        if (config.getToken() == null || !config.getToken().startsWith("vault:")) {
            throw new IllegalArgumentException("platform.siem.hec.token must be a vault: reference to the HEC token, never the token itself");
        }
        this.config = config;
        this.credentials = credentials;
        this.json = json;
        this.http = http;
        this.url = URI.create(config.getUrl().trim());
    }

    @Override
    public String name() {
        return "hec";
    }

    @Override
    public void send(List<SiemEvent> batch) throws IOException, InterruptedException {
        if (batch.isEmpty()) {
            return;
        }
        String token = credentials.resolve(config.getToken()).block(timeout());
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("the HEC token reference " + config.getToken() + " resolves to nothing");
        }
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(timeout())
                .header("Authorization", "Splunk " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body(batch)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String text = response.body() == null ? "" : response.body();
            throw new IOException("HEC answered " + response.statusCode() + (text.isBlank() ? "" : ": " + abbreviate(text)));
        }
    }

    /** One event object per entry, concatenated as HEC expects; {@code time} is epoch seconds with millis. */
    byte[] body(List<SiemEvent> batch) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (SiemEvent e : batch) {
            ObjectNode event = json.createObjectNode();
            if (e.at() != null) {
                event.put("time", BigDecimal.valueOf(e.at().toEpochMilli()).movePointLeft(3));
            }
            event.put("source", config.getSource());
            event.put("sourcetype", config.getSourcetype());
            if (config.getIndex() != null && !config.getIndex().isBlank()) {
                event.put("index", config.getIndex());
            }
            event.set("event", json.valueToTree(e));
            out.write(json.writeValueAsBytes(event));
            out.write('\n');
        }
        return out.toByteArray();
    }

    private Duration timeout() {
        return config.getTimeout() == null ? Duration.ofSeconds(10) : config.getTimeout();
    }

    private static String abbreviate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
