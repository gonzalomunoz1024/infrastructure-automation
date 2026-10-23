package com.iaf.console.state.inventory.application;

import static com.iaf.console.state.inventory.domain.Fixtures.ACCOUNT;
import static com.iaf.console.state.inventory.domain.Fixtures.REGION;
import static com.iaf.console.state.inventory.domain.Fixtures.T0;
import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.state.TestClock;
import com.iaf.console.state.inventory.adapters.memory.InMemoryAccountKeys;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.DriftField;
import com.iaf.console.state.inventory.domain.Fixtures;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceDecider;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.domain.ResourceFold;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceTypeInfo;

/** Envelope encryption: sealed at rest, masked by default, revealed on request, gone with the account's key. */
class SensitiveDataTest {

    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final InMemoryAccountKeys keys = new InMemoryAccountKeys();
    private static final byte[] MASTER = SensitiveData.randomMasterKey();
    private final SensitiveData sensitive = new SensitiveData(keys, MASTER, clock);

    private static ResourceEvent requested() {
        ResourceIdentity id = ResourceIdentity.mint("wf", "data", REGION, ACCOUNT, "database");
        ObjectNode input = Json.object().put("name", "orders").put("engine", "postgres").put("secret", "hunter2").put("adminPassword", "p4ss");
        return ResourceDecider.mint(id, new Commands.Mint("database", REGION, "orders", "create-database", input, by("dana")), T0);
    }

    @Test
    void sealsTheMarkedAndTheSecretNamedFieldsAndNothingElse() {
        ResourceEvent plain = requested();
        ResourceEvent sealed = sensitive.seal(plain, Fixtures.DATABASE).block();
        ObjectNode input = (ObjectNode) sealed.data().get("input");
        assertThat(SensitiveData.isSealed(input.get("secret"))).isTrue();
        assertThat(SensitiveData.isSealed(input.get("adminPassword"))).isTrue();
        assertThat(input.get("engine").asText()).isEqualTo("postgres");
        assertThat(input.get("name").asText()).isEqualTo("orders");
        assertThat(sealed.data().toString()).doesNotContain("hunter2").doesNotContain("p4ss");
        assertThat(sealed.key()).isEqualTo(plain.key());
        assertThat(sensitive.seal(sealed, Fixtures.DATABASE).block().data()).as("sealing twice changes nothing").isEqualTo(sealed.data());
    }

    @Test
    void maskedByDefaultRevealedOnRequest() {
        ResourceEvent sealed = sensitive.seal(requested(), Fixtures.DATABASE).block();
        assertThat(SensitiveData.mask(sealed).data().get("input").get("secret").asText()).isEqualTo(SensitiveData.MASK);
        assertThat(sensitive.reveal(sealed).block().data().get("input").get("secret").asText()).isEqualTo("hunter2");

        Resource r = ResourceFold.apply(null, sealed);
        assertThat(SensitiveData.isSealed(r.attributes().get("secret"))).isTrue();
        assertThat(SensitiveData.mask(r).attributes().get("secret").asText()).isEqualTo(SensitiveData.MASK);
        assertThat(SensitiveData.mask(r).attributes().get("engine").asText()).isEqualTo("postgres");
        Resource revealed = sensitive.reveal(r).block();
        assertThat(revealed.attributes().get("secret").asText()).isEqualTo("hunter2");
        assertThat(revealed.attributes().get("adminPassword").asText()).isEqualTo("p4ss");
        assertThat(revealed.version()).isEqualTo(r.version());
    }

    @Test
    void driftOnASensitivePathIsSealedOnBothSides() {
        Resource r = ResourceFold.apply(null, sensitive.seal(requested(), Fixtures.DATABASE).block());
        Resource active = ResourceFold.apply(r, ResourceDecider.registered(r, new Commands.Registration("job_1", "create-database", null, "db-1", "db-1",
                "orders", Json.object().put("name", "orders").put("engine", "postgres"), by("platform")), T0).orElseThrow());
        ResourceEvent drift = ResourceDecider.recordDrift(active, new Commands.RecordDrift("fnd_1", "warning",
                List.of(new DriftField("/secret", Json.node("hunter2"), Json.node("changed")), new DriftField("/engine", Json.node("postgres"),
                        Json.node("mysql"))), by("drift")), T0);
        ResourceEvent sealed = sensitive.seal(drift, Fixtures.DATABASE).block();
        assertThat(SensitiveData.isSealed(sealed.data().get("fields").get(0).get("expected"))).isTrue();
        assertThat(SensitiveData.isSealed(sealed.data().get("fields").get(0).get("observed"))).isTrue();
        assertThat(sealed.data().get("fields").get(1).get("observed").asText()).isEqualTo("mysql");
        Resource drifted = ResourceFold.apply(active, sealed);
        assertThat(SensitiveData.mask(drifted).drift().fields().get(0).observed().asText()).isEqualTo(SensitiveData.MASK);
        assertThat(sensitive.reveal(drifted).block().drift().fields().get(0).observed().asText()).isEqualTo("changed");
    }

    @Test
    void shreddingTheAccountsKeyLeavesOnlyTheMask() {
        ResourceEvent sealed = sensitive.seal(requested(), Fixtures.DATABASE).block();
        Resource r = ResourceFold.apply(null, sealed);
        assertThat(sensitive.shred(ACCOUNT).block()).isTrue();
        assertThat(sensitive.shred(ACCOUNT).block()).as("a second shred finds nothing").isFalse();
        assertThat(sensitive.reveal(r).block().attributes().get("secret").asText()).isEqualTo(SensitiveData.MASK);
        assertThat(sensitive.reveal(sealed).block().data().get("input").get("secret").asText()).isEqualTo(SensitiveData.MASK);
        // a shredded account never gets a key again: nothing new is sealed for it, the old values stay unreadable
        assertThatThrownBy(() -> sensitive.seal(requested(), Fixtures.DATABASE).block()).isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("account.shredded");
        assertThat(sensitive.reveal(r).block().attributes().get("secret").asText()).isEqualTo(SensitiveData.MASK);
    }

    @Test
    void aShredOnAnotherReplicaReachesThisOneWithinTheKeyTtl() {
        TestClock moving = new TestClock(T0);
        SensitiveData here = new SensitiveData(keys, MASTER, moving);
        SensitiveData there = new SensitiveData(keys, MASTER, moving);
        Resource r = ResourceFold.apply(null, here.seal(requested(), Fixtures.DATABASE).block());
        assertThat(here.reveal(r).block().attributes().get("secret").asText()).isEqualTo("hunter2");
        assertThat(there.shred(ACCOUNT).block()).isTrue();
        moving.advance(SensitiveData.KEY_TTL.plusSeconds(1));
        assertThat(here.reveal(r).block().attributes().get("secret").asText()).isEqualTo(SensitiveData.MASK);
    }

    @Test
    void nestedSecretsAreSealedWhereverTheyAre() {
        ResourceTypeInfo type = new ResourceTypeInfo("database", "databases", "data", Map.of(), "missing", Set.of("/connection/certificate", "secret"),
                Set.of(), Set.of());
        ResourceIdentity id = ResourceIdentity.mint("wf", "data", REGION, ACCOUNT, "database");
        ObjectNode input = Json.object().put("name", "orders");
        input.putObject("connection").put("host", "db.internal").put("password", "p4ss").put("certificate", "-----BEGIN-----");
        input.putArray("users").addObject().put("name", "app").put("apiKey", "k-123");
        input.putObject("labels").put("secret", "not-top-level");
        ResourceEvent sealed = sensitive.seal(ResourceDecider.mint(id, new Commands.Mint("database", REGION, "orders", "create-database", input,
                by("dana")), T0), type).block();
        JsonNode in = sealed.data().get("input");
        assertThat(SensitiveData.isSealed(in.get("connection").get("password"))).as("by its name, nested").isTrue();
        assertThat(SensitiveData.isSealed(in.get("connection").get("certificate"))).as("by the type's pointer").isTrue();
        assertThat(SensitiveData.isSealed(in.get("users").get(0).get("apiKey"))).as("inside an array").isTrue();
        assertThat(SensitiveData.isSealed(in.get("labels").get("secret"))).as("a secret-named field anywhere").isTrue();
        assertThat(in.get("connection").get("host").asText()).isEqualTo("db.internal");
        assertThat(sealed.data().toString()).doesNotContain("p4ss").doesNotContain("BEGIN").doesNotContain("k-123");
        assertThat(SensitiveData.mask(sealed).data().get("input").get("users").get(0).get("apiKey").asText()).isEqualTo(SensitiveData.MASK);
        assertThat(sensitive.reveal(sealed).block().data().get("input").get("connection").get("password").asText()).isEqualTo("p4ss");
    }

    @Test
    void anotherMasterKeyCannotOpenTheAccountsKey() {
        Resource r = ResourceFold.apply(null, sensitive.seal(requested(), Fixtures.DATABASE).block());
        SensitiveData other = new SensitiveData(keys, SensitiveData.randomMasterKey(), clock);
        assertThatThrownBy(() -> other.reveal(r).block()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SensitiveData(keys, new byte[16], clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
