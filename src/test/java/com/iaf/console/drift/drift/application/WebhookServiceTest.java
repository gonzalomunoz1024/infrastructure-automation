package com.iaf.console.drift.drift.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.iaf.console.drift.TestClock;
import com.iaf.console.drift.drift.adapters.memory.InMemoryWatchStore;
import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.Watch;

import reactor.core.publisher.Mono;

class WebhookServiceTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-10-23T12:00:00Z"));
    private final InMemoryWatchStore watches = new InMemoryWatchStore();
    private final ShardScanner scanner = scanner();
    private final WebhookService webhooks = new WebhookService(Map.of("vsphere", "s3cret"), watches, scanner, clock);

    private static ShardScanner scanner() {
        ShardScanner s = Mockito.mock(ShardScanner.class);
        Mockito.when(s.checkNow(ArgumentMatchers.anyString())).thenReturn(Mono.empty());
        return s;
    }

    private static final String BODY = "{\"event\":\"vm.changed\",\"objects\":[{\"providerId\":\"vm-1006\",\"type\":\"vm\"},{\"providerId\":\"vm-9999\"}]}";

    @Test
    void aSignedNotificationMakesTheNamedResourcesDueNow() {
        Watch w = Watch.builder("wfrn:wf:compute:global:123456789012:vm/vm-01", "123456789012", "global", "vm").providerId("vm-1006")
                .registryId("vm-01").tier(Tier.COLD).nextCheckAt(clock.instant().plus(Duration.ofHours(20))).build();
        watches.save(w).block();
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        WebhookService.Scheduled scheduled = webhooks.receive("vsphere", "sha256=" + WebhookService.sign("s3cret", body), body).block();
        assertThat(scheduled.scheduled()).isEqualTo(1);
        Watch due = watches.find(w.wfrn()).block();
        assertThat(due.tier()).isEqualTo(Tier.HOT);
        assertThat(due.nextCheckAt()).isEqualTo(clock.instant());
        Mockito.verify(scanner, Mockito.timeout(5000)).checkNow(w.wfrn());
    }

    @Test
    void theSameNotificationSentAgainSchedulesNothing() {
        Watch w = Watch.builder("wfrn:wf:compute:global:123456789012:vm/vm-02", "123456789012", "global", "vm").providerId("vm-1006")
                .registryId("vm-02").tier(Tier.WARM).nextCheckAt(clock.instant().plus(Duration.ofMinutes(50))).build();
        watches.save(w).block();
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        String signature = "sha256=" + WebhookService.sign("s3cret", body);
        assertThat(webhooks.receive("vsphere", signature, body).block().scheduled()).isEqualTo(1);
        // replayed within the window: verified, but nothing is read again
        assertThat(webhooks.receive("vsphere", signature, body).block().scheduled()).isZero();
        // another notification is another change
        byte[] other = BODY.replace("vm.changed", "vm.reconfigured").getBytes(StandardCharsets.UTF_8);
        assertThat(webhooks.receive("vsphere", "sha256=" + WebhookService.sign("s3cret", other), other).block().scheduled()).isEqualTo(1);
        // past the window the first one counts again
        clock.advance(WebhookService.REPLAY_WINDOW.plusSeconds(1));
        assertThat(webhooks.receive("vsphere", signature, body).block().scheduled()).isEqualTo(1);
    }

    @Test
    void anUnsignedOrMissignedOrUnknownSenderIsRefused() {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> webhooks.receive("vsphere", null, body).block()).isInstanceOf(WebhookService.BadSignature.class);
        assertThatThrownBy(() -> webhooks.receive("vsphere", "sha256=" + WebhookService.sign("other", body), body).block())
                .isInstanceOf(WebhookService.BadSignature.class);
        assertThatThrownBy(() -> webhooks.receive("vsphere", "sha256=zz", body).block()).isInstanceOf(WebhookService.BadSignature.class);
        assertThatThrownBy(() -> webhooks.receive("mongodb", "sha256=" + WebhookService.sign("s3cret", body), body).block())
                .isInstanceOf(WebhookService.BadSignature.class);
    }

    @Test
    void aBodyWithoutObjectsIsBadInput() {
        byte[] body = "{\"objects\":[]}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> webhooks.receive("vsphere", "sha256=" + WebhookService.sign("s3cret", body), body).block())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
