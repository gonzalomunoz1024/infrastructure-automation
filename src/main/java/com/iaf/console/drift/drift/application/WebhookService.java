package com.iaf.console.drift.drift.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A provider's change notification: verified with {@code X-Webhook-Signature}, an HMAC-SHA256 of
 * the raw body under the provider's secret ({@code sha256=<hex>}), it moves the resources it
 * names to the hot tier, due now, and reads them right away instead of waiting for the interval.
 */
public final class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final int MAX_OBJECTS = 1000;

    /** A signature that does not verify: the contract's 401. */
    public static final class BadSignature extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BadSignature(String message) {
            super(message);
        }
    }

    /** What a notification did. */
    public record Scheduled(int scheduled) {
    }

    private final Map<String, String> secrets;
    private final WatchStorePort watches;
    private final ShardScanner scanner;
    private final Clock clock;

    public WebhookService(Map<String, String> secrets, WatchStorePort watches, ShardScanner scanner, Clock clock) {
        this.secrets = Map.copyOf(secrets);
        this.watches = watches;
        this.scanner = scanner;
        this.clock = clock;
    }

    public Mono<Scheduled> receive(String provider, String signature, byte[] body) {
        String secret = secrets.get(provider);
        if (secret == null) {
            // no signing material: nothing can prove the sender, and which providers exist is not told
            return Mono.error(new BadSignature("X-Webhook-Signature cannot be verified for this provider"));
        }
        try {
            verify(secret, signature, body);
        } catch (BadSignature e) {
            return Mono.error(e);
        }
        JsonNode payload;
        try {
            payload = Json.MAPPER.readTree(body);
        } catch (java.io.IOException e) {
            return Mono.error(new IllegalArgumentException("the body is not JSON"));
        }
        JsonNode objects = payload.path("objects");
        if (!objects.isArray() || objects.isEmpty()) {
            return Mono.error(new IllegalArgumentException("objects is a non-empty array"));
        }
        if (objects.size() > MAX_OBJECTS) {
            return Mono.error(new IllegalArgumentException("objects holds at most " + MAX_OBJECTS + " entries"));
        }
        return Flux.fromIterable(objects)
                .filter(o -> o.hasNonNull("providerId"))
                .concatMap(o -> watches.findByProviderId(o.get("providerId").asText(), o.hasNonNull("type") ? o.get("type").asText() : null)
                        .filter(w -> !o.hasNonNull("region") || o.get("region").asText().equals(w.region()))
                        .concatMap(w -> WatchUpdates.update(watches, w.wfrn(), x -> x.toBuilder().tier(Tier.HOT).nextCheckAt(clock.instant()).build())))
                .map(Watch::wfrn)
                .collectList()
                .doOnSuccess(wfrns -> {
                    if (!wfrns.isEmpty()) {
                        log.info("webhook from {}: {} resource(s) read now", provider, wfrns.size());
                        Flux.fromIterable(wfrns)
                                .flatMap(wfrn -> scanner.checkNow(wfrn).onErrorResume(e -> {
                                    log.warn("{}: the read after the webhook failed: {}", wfrn, e.toString());
                                    return Mono.empty();
                                }), 4)
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe();
                    }
                })
                .map(wfrns -> new Scheduled(wfrns.size()));
    }

    /** {@code sha256=<hex>} over the raw body; compared in constant time. */
    static void verify(String secret, String signature, byte[] body) {
        if (signature == null || signature.isBlank()) {
            throw new BadSignature("X-Webhook-Signature is missing");
        }
        String given = signature.trim();
        if (given.toLowerCase(Locale.ROOT).startsWith("sha256=")) {
            given = given.substring(7);
        }
        byte[] expected = HexFormat.of().parseHex(sign(secret, body));
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(given.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadSignature("X-Webhook-Signature is not hex");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BadSignature("X-Webhook-Signature does not match");
        }
    }

    /** The signature a provider computes: for tests and the demo script. */
    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> providers() {
        return List.copyOf(secrets.keySet());
    }
}
