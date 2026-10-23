package com.iaf.console.platform.assertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.platform.assertion.OnBehalfOfAssertions.Claims;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions.InvalidAssertionException;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions.Signer;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions.User;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions.Verifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;

class OnBehalfOfAssertionsTest {

    private static final User DANA = new User("dana", "Dana Li", "dana@example.com", List.of("team-compute"));
    private final Instant now = Instant.parse("2026-10-23T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final ECKey key = OnBehalfOfAssertions.generateKey("bff-2026-10");
    private final Signer signer = new Signer(key, "console-bff", "platform", clock, Duration.ofSeconds(30));
    private final Verifier verifier = new Verifier(new JWKSet(key.toPublicJWK()), "console-bff", "platform", clock);

    @Test
    void signsAndVerifiesTheClaimsTable() {
        String jws = signer.sign(DANA, "123456789012", "global", "jti-1");
        assertThat(jws.split("\\.")).hasSize(3);
        Claims claims = verifier.verify(jws);
        assertThat(claims.issuer()).isEqualTo("console-bff");
        assertThat(claims.audience()).isEqualTo("platform");
        assertThat(claims.subject()).isEqualTo("dana");
        assertThat(claims.name()).isEqualTo("Dana Li");
        assertThat(claims.email()).isEqualTo("dana@example.com");
        assertThat(claims.groups()).containsExactly("team-compute");
        assertThat(claims.accountId()).isEqualTo("123456789012");
        assertThat(claims.region()).isEqualTo("global");
        assertThat(claims.jti()).isEqualTo("jti-1");
        assertThat(claims.issuedAt()).isEqualTo(now);
        assertThat(claims.expiresAt()).isEqualTo(now.plusSeconds(30));
        Signer live = new Signer(key, "console-bff", "platform");
        assertThat(Verifier.of(live).verify(live.sign(DANA, "123456789012", "global")).jti()).isNotBlank();
    }

    @Test
    void refusesWhatThePlatformRefuses() {
        ECKey other = OnBehalfOfAssertions.generateKey("someone-else");
        Signer forger = new Signer(other, "console-bff", "platform", clock, Duration.ofSeconds(30));
        assertReason(() -> verifier.verify(forger.sign(DANA, "123456789012", "global")), "key");

        Verifier trustingBoth = new Verifier(new JWKSet(List.of(key.toPublicJWK(), other.toPublicJWK())), "console-bff", "platform", clock);
        String tampered = tamper(signer.sign(DANA, "123456789012", "global"));
        assertReason(() -> trustingBoth.verify(tampered), "signature");

        Signer otherAudience = new Signer(key, "console-bff", "another-platform", clock, Duration.ofSeconds(30));
        assertReason(() -> verifier.verify(otherAudience.sign(DANA, "123456789012", "global")), "audience");

        Signer otherIssuer = new Signer(key, "someone", "platform", clock, Duration.ofSeconds(30));
        assertReason(() -> verifier.verify(otherIssuer.sign(DANA, "123456789012", "global")), "issuer");

        Claims expired = new Claims("console-bff", "platform", "dana", "Dana Li", null, List.of(), "123456789012", "global", "jti-2",
                now.minusSeconds(120), now.minusSeconds(90));
        assertReason(() -> verifier.verify(signer.sign(expired)), "expired");

        Claims tooLong = new Claims("console-bff", "platform", "dana", "Dana Li", null, List.of(), "123456789012", "global", "jti-3", now,
                now.plusSeconds(61));
        assertReason(() -> verifier.verify(signer.sign(tooLong)), "lifetime");

        Claims future = new Claims("console-bff", "platform", "dana", "Dana Li", null, List.of(), "123456789012", "global", "jti-4",
                now.plusSeconds(120), now.plusSeconds(150));
        assertReason(() -> verifier.verify(signer.sign(future)), "premature");

        Claims noAccount = new Claims("console-bff", "platform", "dana", "Dana Li", null, List.of(), null, "global", "jti-5", now, now.plusSeconds(30));
        assertReason(() -> verifier.verify(signer.sign(noAccount)), "claims");

        assertReason(() -> verifier.verify("not.a.jws"), "malformed");
        assertReason(() -> verifier.verify(""), "malformed");
    }

    @Test
    void allowsClockSkew() {
        Verifier late = new Verifier(new JWKSet(key.toPublicJWK()), "console-bff", "platform", Clock.fixed(now.plusSeconds(55), ZoneOffset.UTC));
        assertThat(late.verify(signer.sign(DANA, "123456789012", "global")).subject()).isEqualTo("dana");
        Verifier tooLate = new Verifier(new JWKSet(key.toPublicJWK()), "console-bff", "platform", Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC));
        assertReason(() -> tooLate.verify(signer.sign(DANA, "123456789012", "global")), "expired");
    }

    @Test
    void keysAreGeneratedNotCommitted() {
        assertThat(key.isPrivate()).isTrue();
        assertThat(signer.publicKey().isPrivate()).isFalse();
        assertThat(signer.keyId()).isEqualTo("bff-2026-10");
        assertThatThrownBy(() -> new Signer(key.toPublicJWK(), "i", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Signer(key, "i", "a", clock, Duration.ofSeconds(90))).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertReason(Runnable call, String reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InvalidAssertionException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static String tamper(String jws) {
        String[] parts = jws.split("\\.");
        char last = parts[2].charAt(parts[2].length() - 1);
        char flipped = last == 'A' ? 'B' : 'A';
        return parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 1) + flipped;
    }
}
