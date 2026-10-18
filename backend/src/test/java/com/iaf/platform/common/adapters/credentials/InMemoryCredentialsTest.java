package com.iaf.platform.common.adapters.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.config.PlatformProperties;

import reactor.test.StepVerifier;

class InMemoryCredentialsTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    static InMemoryCredentials empty() {
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir("/nowhere/at/all");
        InMemoryCredentials vault = new InMemoryCredentials(props, Clock.fixed(NOW, ZoneOffset.UTC));
        vault.seed();
        return vault;
    }

    @Test
    void guessesTheTypeFromThePathThenTheAuth() {
        assertThat(InMemoryCredentials.typeOf("vault:ssh/ops-automation", null)).isEqualTo("ssh-key");
        assertThat(InMemoryCredentials.typeOf("vault:backup/api-key", "header")).isEqualTo("api-key");
        assertThat(InMemoryCredentials.typeOf("vault:db/password", "basic")).isEqualTo("password");
        assertThat(InMemoryCredentials.typeOf("vault:vsphere/token", "bearer")).isEqualTo("token");
        assertThat(InMemoryCredentials.typeOf("vault:mongodb/ops-manager", "basic")).isEqualTo("basic");
        assertThat(InMemoryCredentials.typeOf("vault:platform/vault-token", "header")).isEqualTo("token");
        assertThat(InMemoryCredentials.typeOf("vault:x/y", "header")).isEqualTo("api-key");
        assertThat(InMemoryCredentials.typeOf("vault:x/y", null)).isEqualTo("token");
    }

    @Test
    void storesUpsertsAndListsWithoutValues() {
        InMemoryCredentials vault = empty();
        StepVerifier.create(vault.list()).assertNext(l -> assertThat(l).isEmpty()).verifyComplete();
        StepVerifier.create(vault.store("vault:fleet/token", "token", "one", "mateo", "team-compute"))
                .assertNext(info -> {
                    assertThat(info.name()).isEqualTo("fleet/token");
                    assertThat(info.path()).isEqualTo("vault:fleet/token");
                    assertThat(info.type()).isEqualTo("token");
                    assertThat(info.owner()).isEqualTo("team-compute");
                    assertThat(info.updatedAt()).isEqualTo(NOW);
                })
                .verifyComplete();
        StepVerifier.create(vault.store("fleet/token", null, "two", "mateo", "team-compute")).assertNext(i -> assertThat(i.type()).isEqualTo("token")).verifyComplete();
        StepVerifier.create(vault.store("a/basic", "basic", "u:p", "mateo", null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(vault.resolve("vault:fleet/token")).expectNext("two").verifyComplete();
        StepVerifier.create(vault.resolve("fleet/token")).expectNext("two").verifyComplete();
        StepVerifier.create(vault.resolve("")).verifyComplete();
        StepVerifier.create(vault.list())
                .assertNext(l -> {
                    assertThat(l).extracting(CredentialInfo::name).containsExactly("a/basic", "fleet/token");
                    assertThat(l.toString()).doesNotContain("two").doesNotContain("u:p");
                })
                .verifyComplete();
        StepVerifier.create(vault.store("", "token", "x", "mateo", null)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(vault.store("x", "token", "", "mateo", null)).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void keepsTheOwnerAndDescribesOneCredentialByReferenceWithoutItsValue() {
        InMemoryCredentials vault = empty();
        StepVerifier.create(vault.store("fleet/token", "token", "one", "mateo", " team-compute ")).assertNext(i -> assertThat(i.owner()).isEqualTo("team-compute")).verifyComplete();
        StepVerifier.create(vault.store("shared/token", "token", "two", "gonzalo", " ")).assertNext(i -> assertThat(i.owner()).isNull()).verifyComplete();
        StepVerifier.create(vault.info("vault:fleet/token"))
                .assertNext(info -> {
                    assertThat(info.name()).isEqualTo("fleet/token");
                    assertThat(info.owner()).isEqualTo("team-compute");
                    assertThat(info.toString()).doesNotContain("one");
                })
                .verifyComplete();
        StepVerifier.create(vault.info("shared/token")).assertNext(info -> assertThat(info.owner()).isNull()).verifyComplete();
        StepVerifier.create(vault.info("vault:nope")).verifyComplete();
        StepVerifier.create(vault.info(" ")).verifyComplete();
        StepVerifier.create(vault.info(null)).verifyComplete();
        StepVerifier.create(vault.list())
                .assertNext(l -> assertThat(l).extracting(CredentialInfo::owner).containsExactly("team-compute", null))
                .verifyComplete();
        // a new value may hand the credential to another team, or free it
        StepVerifier.create(vault.store("fleet/token", "token", "three", "ana", "team-data")).assertNext(i -> assertThat(i.owner()).isEqualTo("team-data")).verifyComplete();
        StepVerifier.create(vault.info("fleet/token")).assertNext(info -> assertThat(info.owner()).isEqualTo("team-data")).verifyComplete();
        StepVerifier.create(vault.store("fleet/token", "token", "four", "gonzalo", null)).assertNext(i -> assertThat(i.owner()).isNull()).verifyComplete();
    }
}
