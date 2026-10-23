package com.iaf.console.fake.registry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Ids;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.content.application.ContentStore;
import com.iaf.console.fake.events.application.EventFeed;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.DevUsers;
import com.iaf.console.fake.registry.domain.ResourceRecord;
import com.iaf.console.fake.runs.domain.JobContext;
import com.iaf.console.fake.runs.domain.StepFailure;
import com.iaf.console.wfrn.MintedIds;
import com.iaf.console.wfrn.Wfrn;

class RegistryTest {

    private static final ContentStore CONTENT = ContentStore.load();
    private static final String ACCOUNT = "123456789012";

    private final FakeClock clock = new FakeClock(Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC));
    private final Ids ids = Ids.seeded(clock.clock(), 11);
    private final EventFeed events = new EventFeed(clock, ids, 1000);
    private final Registry registry = new Registry(clock, ids, CONTENT, events);

    private static Registry.Registrar by(String jobId, JobContext context) {
        return new Registry.Registrar(jobId, "create-vm", "2.0.1", "console-" + ACCOUNT, "team-compute", context, null, null);
    }

    private static ObjectNode attributes(String name) {
        return Json.object().put("name", name).put("cpu", 2);
    }

    @Test
    void aPlatformJobKeepsTheProviderIdAsTheResourceId() {
        Registry.Outcome outcome = registry.register(new Registry.Request("vm", "vm-1001", null, attributes("web-01"), List.of()), by("job-1", null), null);
        assertThat(outcome.created()).isTrue();
        assertThat(outcome.record().id()).isEqualTo("vm-1001");
        assertThat(outcome.record().wfrn()).isNull();
        assertThat(outcome.record().status()).isEqualTo("ready");
        assertThat(events.list(0, 10, List.of("resource."), DevUsers.byName("gonzalo"))).hasSize(1);
    }

    @Test
    void aConsoleJobMintsAnIdAndAWfrnInItsAccount() {
        JobContext context = new JobContext(ACCOUNT, "global", null, "req-1", Map.of());
        ResourceRecord record = registry.register(new Registry.Request("vm", "vm-1002", null, attributes("web-02"), List.of()), by("job-2", context), null)
                .record();
        assertThat(MintedIds.isMinted(record.id())).isTrue();
        assertThat(record.providerId()).isEqualTo("vm-1002");
        Wfrn wfrn = Wfrn.parse(record.wfrn());
        assertThat(wfrn.account()).isEqualTo(ACCOUNT);
        assertThat(wfrn.type()).isEqualTo("vm");
        assertThat(wfrn.id()).isEqualTo(record.id());
        assertThat(registry.byWfrn(record.wfrn())).contains(record);
        assertThat(registry.find("vm-1002")).contains(record);
    }

    @Test
    void theContextWfrnBindsOnceAndTheSecondRegistrationOfTheTypeMints() {
        String wanted = new Wfrn("wf", "compute", "global", ACCOUNT, "vm", "vm-01jchosen000000000000000a").format();
        JobContext context = new JobContext(ACCOUNT, "global", wanted, null, Map.of());
        Registry.Outcome first = registry.register(new Registry.Request("vm", "vm-2001", null, attributes("a"), List.of()), by("job-3", context), wanted);
        assertThat(first.boundContextWfrn()).isTrue();
        assertThat(first.record().wfrn()).isEqualTo(wanted);
        assertThat(first.record().id()).isEqualTo("vm-01jchosen000000000000000a");
        Registry.Outcome second = registry.register(new Registry.Request("vm", "vm-2002", null, attributes("b"), List.of()), by("job-3", context), null);
        assertThat(second.boundContextWfrn()).isFalse();
        assertThat(second.record().wfrn()).isNotEqualTo(wanted);
    }

    @Test
    void anotherOwnerReusingAnIdIsACollision() {
        registry.register(new Registry.Request("vm", "vm-3001", null, attributes("web"), List.of()), by("job-4", null), null);
        Registry.Registrar other = new Registry.Registrar("job-5", "create-database", "1.4.0", "raj", "team-data", null, null, null);
        assertThatThrownBy(() -> registry.register(new Registry.Request("database", "vm-3001", null, attributes("db"), List.of()), other, null))
                .isInstanceOf(StepFailure.class)
                .satisfies(e -> assertThat(((StepFailure) e).error().errorClass()).isEqualTo("resource.idCollision"));
    }

    @Test
    void retiringAndReRegisteringSupersedesTheOldRecord() {
        ResourceRecord first = registry.register(new Registry.Request("vm", "vm-4001", null, attributes("web"), List.of()), by("job-6", null), null).record();
        Registry.Outcome retired = registry.register(new Registry.Request("vm", "vm-4001", "retired", Json.object(), List.of()), by("job-7", null), null);
        assertThat(retired.retired()).isTrue();
        assertThat(first.retired()).isTrue();
        Registry.Outcome fresh = registry.register(new Registry.Request("vm", "vm-4001", null, attributes("web"), List.of()), by("job-8", null), null);
        assertThat(fresh.created()).isTrue();
        assertThat(fresh.record().supersedes()).isEqualTo(first.id());
        assertThat(fresh.record().id()).isNotEqualTo(first.id());
    }

    @Test
    void consoleAccountsSeeTheirAccountAndPublicResourcesAndPageById() {
        JobContext mine = new JobContext(ACCOUNT, "global", null, null, Map.of());
        JobContext theirs = new JobContext("210987654321", "global", null, null, Map.of());
        for (int i = 0; i < 4; i++) {
            registry.register(new Registry.Request("vm", "vm-5" + i, null, attributes("mine-" + i), List.of()), by("job-9", mine), null);
        }
        registry.register(new Registry.Request("vm", "vm-6", null, attributes("theirs"), List.of()), by("job-10", theirs), null);
        registry.register(new Registry.Request("image", "img-1", null, Json.object().put("templateId", "tpl"), List.of()),
                new Registry.Registrar("job-11", "build-vm-image", "1.1.0", "mia", "team-platform", null, null, null), null);
        Caller console = new Caller("sa-1", "console", null, List.of("team-compute"), List.of(), false, Caller.SERVICE, Set.of("registry:read"), ACCOUNT, null);
        List<ResourceRecord> visible = registry.list(Registry.Filter.none(), console);
        assertThat(visible).extracting(ResourceRecord::type).containsOnly("vm", "image");
        assertThat(visible).filteredOn(r -> "vm".equals(r.type())).allMatch(r -> ACCOUNT.equals(r.accountId()));
        Registry.Page page = registry.page(new Registry.Filter("vm", null, null, null, null, null), console, 3, null);
        assertThat(page.items()).hasSize(3);
        assertThat(page.next()).isNotNull();
        Registry.Page rest = registry.page(new Registry.Filter("vm", null, null, null, null, null), console, 3, page.next());
        assertThat(rest.items()).hasSize(1);
        assertThat(rest.next()).isNull();
        assertThat(registry.visible("vm-6", console)).isEmpty();
        assertThat(registry.visible("vm-6", DevUsers.byName("gonzalo"))).isPresent();
    }
}
