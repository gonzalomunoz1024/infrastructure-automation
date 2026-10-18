package com.iaf.platform.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

class IdempotencyKeysTest {

    static byte[] body(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aKeyIsOneTo255VisibleAsciiCharacters() {
        assertThat(IdempotencyKeys.validate("  order-42  ")).isEqualTo("order-42");
        assertThat(IdempotencyKeys.validate("a".repeat(255))).hasSize(255);
        assertThatThrownBy(() -> IdempotencyKeys.validate(" ")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> IdempotencyKeys.validate("a".repeat(256))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeys.validate("two words")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeys.validate("café")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeys.validate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameRequestWrittenDifferentlyHasTheSameFingerprint() {
        String a = IdempotencyKeys.fingerprint("POST", "/api/v1/vms", "b=2&a=1", null, body("{\"name\":\"web-1\",\"size\":{\"cpu\":2,\"ram\":4}}"));
        String b = IdempotencyKeys.fingerprint("post", "/api/v1/vms", "a=1&b=2", "", body("{ \"size\": { \"ram\": 4, \"cpu\": 2 },\n \"name\": \"web-1\" }"));
        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(IdempotencyKeys.fingerprint("POST", "/api/v1/vms", null, null, body("")))
                .isEqualTo(IdempotencyKeys.fingerprint("POST", "/api/v1/vms", null, null, body("{}")));
    }

    @Test
    void anyDifferenceInBodyPathQueryOrVersionChangesTheFingerprint() {
        String base = IdempotencyKeys.fingerprint("POST", "/api/v1/vms", null, null, body("{\"name\":\"web-1\"}"));
        assertThat(IdempotencyKeys.fingerprint("POST", "/api/v1/vms", null, null, body("{\"name\":\"web-2\"}"))).isNotEqualTo(base);
        assertThat(IdempotencyKeys.fingerprint("POST", "/api/v1/dbs", null, null, body("{\"name\":\"web-1\"}"))).isNotEqualTo(base);
        assertThat(IdempotencyKeys.fingerprint("POST", "/api/v1/vms", "version=1.0.0", null, body("{\"name\":\"web-1\"}"))).isNotEqualTo(base);
        assertThat(IdempotencyKeys.fingerprint("POST", "/api/v1/vms", null, "1.0.0", body("{\"name\":\"web-1\"}"))).isNotEqualTo(base);
        assertThat(IdempotencyKeys.fingerprint("POST", "/api/v1/vms", null, null, body("not json"))).isNotEqualTo(base);
    }

    @Test
    void toolCallsAreFingerprintedOnToolVersionAndArguments() {
        var f = JsonNodeFactory.instance;
        String a = IdempotencyKeys.fingerprint("vm_restart", null, f.objectNode().put("vmId", "vm-1").put("reason", "stuck"));
        assertThat(IdempotencyKeys.fingerprint("vm_restart", null, f.objectNode().put("reason", "stuck").put("vmId", "vm-1"))).isEqualTo(a);
        assertThat(IdempotencyKeys.fingerprint("vm_restart_batch", null, f.objectNode().put("reason", "stuck").put("vmId", "vm-1"))).isNotEqualTo(a);
        assertThat(IdempotencyKeys.fingerprint("vm_restart", "1.2.0", f.objectNode().put("reason", "stuck").put("vmId", "vm-1"))).isNotEqualTo(a);
    }

    @Test
    void anInFlightKeyIsAbandonedAfterFiveMinutesAndEveryKeyExpiresWithItsLifetime() {
        Instant t0 = Instant.parse("2026-10-17T10:00:00Z");
        KeyedRequest inFlight = KeyedRequest.inFlight("ana", "k", "fp", t0, Duration.ofHours(24));
        assertThat(inFlight.liveAt(t0.plus(Duration.ofMinutes(4)))).isTrue();
        assertThat(inFlight.liveAt(t0.plus(KeyedRequest.IN_FLIGHT_TIMEOUT))).isFalse();

        KeyedRequest answered = inFlight.withAnswer(new StoredAnswer(202, Map.of(), "{}"));
        assertThat(answered.liveAt(t0.plus(Duration.ofHours(23)))).isTrue();
        assertThat(answered.liveAt(t0.plus(Duration.ofHours(24)))).isFalse();
    }

    @Test
    void onlyASuccessIsKept() {
        assertThat(new StoredAnswer(202, null, null).succeeded()).isTrue();
        assertThat(new StoredAnswer(400, null, null).succeeded()).isFalse();
        assertThat(new StoredAnswer(429, null, null).succeeded()).isFalse();
        assertThat(new StoredAnswer(500, null, null).body()).isEmpty();
    }
}
