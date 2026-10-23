package com.iaf.console.identity.accesskeys.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** An access key's states and which secret parts open it. */
class AccessKeyTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @Test
    void secretIsHashedAndOpensTheKey() {
        AccessKey key = AccessKey.create("ck_1", "123456789012", "usr_1", "laptop", "s3cret", null, NOW);
        assertThat(key.status(NOW)).isEqualTo(AccessKeyStatus.active);
        assertThat(key.secretHash()).isEqualTo(Secrets.hash("ck_1", "s3cret")).doesNotContain("s3cret");
        assertThat(key.accepts("s3cret", NOW)).isTrue();
        assertThat(key.accepts("other", NOW)).isFalse();
        assertThat(key.accepts(null, NOW)).isFalse();
        assertThat(key.view(NOW).status()).isEqualTo(AccessKeyStatus.active);
        assertThat(key.created("s3cret", NOW).secretPart()).isEqualTo("s3cret");
        assertThat(key.created("s3cret", NOW).graceUntil()).isNull();
    }

    @Test
    void rotationKeepsThePreviousSecretForTheGrace() {
        AccessKey key = AccessKey.create("ck_1", "123456789012", "usr_1", null, "old", null, NOW);
        AccessKey rotated = key.rotated("new", NOW);
        Instant inGrace = NOW.plus(AccessKey.ROTATION_GRACE).minusSeconds(1);
        Instant afterGrace = NOW.plus(AccessKey.ROTATION_GRACE);

        assertThat(rotated.version()).isEqualTo(2);
        assertThat(rotated.status(inGrace)).isEqualTo(AccessKeyStatus.rotating);
        assertThat(rotated.accepts("old", inGrace)).isTrue();
        assertThat(rotated.accepts("new", inGrace)).isTrue();
        assertThat(rotated.status(afterGrace)).isEqualTo(AccessKeyStatus.active);
        assertThat(rotated.accepts("old", afterGrace)).isFalse();
        assertThat(rotated.accepts("new", afterGrace)).isTrue();
        assertThat(rotated.created("new", NOW).graceUntil()).isEqualTo(NOW.plus(AccessKey.ROTATION_GRACE));
    }

    @Test
    void expiryAndRevocationCloseTheKey() {
        AccessKey expiring = AccessKey.create("ck_1", "123456789012", "usr_1", null, "s", NOW.plus(Duration.ofDays(1)), NOW);
        assertThat(expiring.status(NOW)).isEqualTo(AccessKeyStatus.active);
        assertThat(expiring.status(NOW.plus(Duration.ofDays(1)))).isEqualTo(AccessKeyStatus.expired);
        assertThat(expiring.usable(NOW.plus(Duration.ofDays(2)))).isFalse();
        assertThat(expiring.accepts("s", NOW.plus(Duration.ofDays(2)))).isFalse();

        AccessKey revoked = expiring.revoked(NOW);
        assertThat(revoked.status(NOW)).isEqualTo(AccessKeyStatus.revoked);
        assertThat(revoked.usable(NOW)).isFalse();
        assertThat(revoked.accepts("s", NOW)).isFalse();
        assertThat(revoked.version()).isEqualTo(2);
        assertThat(revoked.used(NOW.plusSeconds(5)).lastUsedAt()).isEqualTo(NOW.plusSeconds(5));
    }
}
