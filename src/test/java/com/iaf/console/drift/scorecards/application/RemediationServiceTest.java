package com.iaf.console.drift.scorecards.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.TestClock;
import com.iaf.console.drift.auth.adapters.roles.ManagedRoleAuthorization;
import com.iaf.console.drift.auth.application.WriteGuard;
import com.iaf.console.drift.auth.domain.ConsolePrincipal;
import com.iaf.console.drift.auth.ports.outbound.AuthorizationPort;
import com.iaf.console.drift.drift.adapters.memory.InMemoryIdempotencyKeys;
import com.iaf.console.drift.drift.adapters.memory.InMemoryWatchStore;
import com.iaf.console.drift.drift.application.IdempotentCommands;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.OnBehalfOfPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.drift.ports.outbound.WorkflowStarterPort;
import com.iaf.console.drift.scorecards.adapters.content.RulePackLoader;
import com.iaf.console.drift.scorecards.adapters.engine.EmbeddedPolicyEngine;
import com.iaf.console.drift.scorecards.adapters.memory.InMemoryEvaluationStore;
import com.iaf.console.drift.scorecards.adapters.memory.InMemoryScoreStore;
import com.iaf.console.drift.scorecards.domain.RuleKey;
import com.iaf.console.drift.scorecards.domain.RulePacks;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.ForbiddenException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.outbox.InMemoryOutbox;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.JobAccepted;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;

/**
 * A one-click fix as the security review asks for it: a person needs {@code scorecards:remediate}
 * and the fix's own action on the resource, a person is always the actor of their own fix, and the
 * person's {@code X-On-Behalf-Of} assertion goes with the change, to the state service for tags and
 * to the platform for a workflow, as a revert's does.
 */
class RemediationServiceTest {

    static final String ACCOUNT = "123456789012";
    static final String WFRN = "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-01";
    static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "dana");
    static final ConsolePrincipal DANA = new ConsolePrincipal("dana", ACCOUNT, "global", List.of("team-compute"), List.of("operator"), null);
    static final ConsolePrincipal VIC = new ConsolePrincipal("vic", ACCOUNT, "global", List.of("team-compute"), List.of("viewer"), null);
    static final ConsolePrincipal AUD = new ConsolePrincipal("aud", ACCOUNT, "global", List.of("auditors"), List.of("auditor"), null);
    static final ConsolePrincipal BFF = new ConsolePrincipal("console-bff", null, null, List.of(), List.of(), "console-bff");

    record Started(String accountId, CatalogEntry entry, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options) {
    }

    private final TestClock clock = new TestClock(Instant.parse("2026-10-23T12:00:00Z"));
    private final RulePacks packs = RulePackLoader.load(null);
    private final InMemoryEvaluationStore evaluations = new InMemoryEvaluationStore();
    private final InMemoryScoreStore scores = new InMemoryScoreStore();
    private final InMemoryWatchStore watches = new InMemoryWatchStore();
    private final ScorecardMetrics metrics = new ScorecardMetrics(new SimpleMeterRegistry());
    private final PlatformPort platform = mock(PlatformPort.class);
    private final StateServicePort state = mock(StateServicePort.class);
    private final List<Started> started = new CopyOnWriteArrayList<>();
    private final List<String> signed = new CopyOnWriteArrayList<>();
    /** What the guard asked, as {@code <action> on <resource>}. */
    private final List<String> asked = new CopyOnWriteArrayList<>();
    /** Actions refused whatever the roles say, as a custom role without them would be. */
    private final Set<String> denied = ConcurrentHashMap.newKeySet();
    private RemediationService service;

    /** A catalog entry of the VM type, as the platform lists it. */
    static CatalogEntry entry(String workflow, String verb, String... fields) {
        ObjectNode properties = Json.object();
        properties.putObject("vmId").put("type", "string");
        properties.putObject("reason").put("type", "string");
        for (String field : fields) {
            properties.putObject(field).put("type", "integer");
        }
        ObjectNode node = Json.object();
        node.put("workflow", workflow);
        node.put("noun", "vm");
        node.put("verb", verb);
        node.put("method", "POST");
        node.put("path", "/api/v1/vms/{vmId}:" + verb);
        node.put("version", "1.2.0");
        node.put("subjectInput", "vmId");
        node.putObject("input").put("type", "object").set("properties", properties);
        return Json.MAPPER.convertValue(node, CatalogEntry.class);
    }

    static ObjectNode owner(String team) {
        return Json.object().put("owner", team);
    }

    @BeforeEach
    void setUp() {
        AuthorizationPort roles = new ManagedRoleAuthorization();
        AuthorizationPort recording = (person, action, resource) -> {
            asked.add(action + " on " + resource);
            return denied.contains(action) ? Mono.just(false) : roles.allowed(person, action, resource);
        };
        WorkflowStarterPort starter = (accountId, entry, pathParams, input, options) -> Mono.fromSupplier(() -> {
            started.add(new Started(accountId, entry, pathParams, input, options));
            return new JobAccepted("job-" + started.size(), "queued", Map.of());
        });
        OnBehalfOfPort onBehalfOf = (accountId, region, person) -> Mono.fromSupplier(() -> {
            signed.add(person.subject());
            return "signed-for-" + person.subject();
        });
        when(platform.listCatalog("vm")).thenReturn(Mono.just(List.of(entry("update-vm", "update", "cpu", "memoryGb"), entry("restart-vm", "restart"))));
        when(state.tag(anyString(), anyMap(), anyString(), any(), any(), any())).thenReturn(Mono.empty());
        when(state.requestUpdate(anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(Mono.empty());
        when(state.noteJob(anyString(), anyString())).thenReturn(Mono.empty());
        ScorecardQueries queries = new ScorecardQueries(packs, evaluations, scores, clock);
        service = new RemediationService(queries, evaluations, watches, platform, state, starter, onBehalfOf, new WriteGuard(recording, "wf"),
                new IdempotentCommands(new InMemoryIdempotencyKeys(clock), Duration.ofHours(24), clock), metrics);

        // a production VM with one vCPU and no tags: it fails the owner tag and the CPU minimum
        ObjectNode desired = Json.object().put("cpu", 1).put("memoryGb", 16);
        Watch vm = Watch.builder(WFRN, ACCOUNT, "global", "vm").service("compute").name("vm-01").environment("prod").registryId("vm-01")
                .desired(1, desired).tags(Map.of()).build();
        watches.save(vm).block();
        ScorecardEvaluator evaluator = new ScorecardEvaluator(packs, new EmbeddedPolicyEngine(), evaluations, scores,
                new ScorecardEvents(new InMemoryOutbox(), clock), metrics, clock);
        evaluator.score(vm, null, false).block();
    }

    private Throwable refusal(String pack, String rule, RemediationService.Request request, String relayed, ConsolePrincipal caller) {
        return catchThrowable(() -> service.remediate(SCOPE, pack, rule, request, null, null, relayed, caller).block());
    }

    @Test
    void aTagsFixIsMadeInTheCallersNameAndCarriesTheAssertionTheBffRelays() {
        // the body names someone else: the person who calls is the actor
        RemediationService.Request request = new RemediationService.Request(WFRN, owner("team-compute"), "tag it", "mallory");
        IdempotentCommands.Outcome outcome = service.remediate(SCOPE, "required-tags", "owner", request, "key-1", "req-1", "assertion-for-dana", DANA)
                .block();

        assertThat(outcome.status()).isEqualTo(200);
        assertThat(outcome.body().path("kind").asText()).isEqualTo("tags");
        assertThat(outcome.body().path("tags").path("owner").asText()).isEqualTo("team-compute");
        verify(state).tag(eq(WFRN), eq(Map.of("owner", "team-compute")), eq("dana"), eq("assertion-for-dana"), eq("req-1"),
                eq("scorecard:" + ACCOUNT + ":key-1"));
        assertThat(signed).as("a relayed assertion is used as it is").isEmpty();
        assertThat(asked).containsExactly("scorecards:remediate on " + WFRN, "compute:tag on " + WFRN);

        // the same key and body replay the answer; nothing is set twice
        IdempotentCommands.Outcome replay = service.remediate(SCOPE, "required-tags", "owner", request, "key-1", "req-1", "assertion-for-dana", DANA)
                .block();
        assertThat(replay.replay()).isTrue();
        verify(state, times(1)).tag(anyString(), anyMap(), anyString(), any(), any(), any());
    }

    @Test
    void aPersonCallingWithoutTheBffIsSignedForByTheLocalSigner() {
        service.remediate(SCOPE, "required-tags", "owner", new RemediationService.Request(WFRN, owner("team-compute"), null, null), null, null, null,
                DANA).block();

        assertThat(signed).containsExactly("dana");
        verify(state).tag(eq(WFRN), anyMap(), eq("dana"), eq("signed-for-dana"), isNull(), isNull());
    }

    @Test
    void theBffNamesThePersonItRelaysButNeverFixesForNobody() {
        service.remediate(SCOPE, "required-tags", "owner", new RemediationService.Request(WFRN, owner("team-compute"), null, "dana"), "key-2", "req-2",
                "assertion-for-dana", BFF).block();
        verify(state).tag(eq(WFRN), anyMap(), eq("dana"), eq("assertion-for-dana"), eq("req-2"), eq("scorecard:" + ACCOUNT + ":key-2"));
        assertThat(asked).as("the console's own services pass the guard").isEmpty();

        // the same key for someone else is another request, not a replay
        Throwable reused = catchThrowable(() -> service.remediate(SCOPE, "required-tags", "owner",
                new RemediationService.Request(WFRN, owner("team-compute"), null, "erin"), "key-2", "req-3", "assertion-for-erin", BFF).block());
        assertThat(reused).isInstanceOf(ConflictException.class).hasMessageStartingWith("idempotency.mismatch");

        // no assertion and no person to sign for: no fix, whoever the body names
        Throwable noPerson = refusal("required-tags", "owner", new RemediationService.Request(WFRN, owner("team-compute"), null, "dana"), null, BFF);
        assertThat(noPerson).isInstanceOf(ConflictException.class).hasMessageStartingWith("remediation.needsPerson");
        Throwable noWorkflowPerson = refusal("compute-baseline", "prod-min-cpu", new RemediationService.Request(WFRN, null, null, "dana"), null, BFF);
        assertThat(noWorkflowPerson).isInstanceOf(ConflictException.class).hasMessageStartingWith("remediation.needsPerson");
        verify(state, times(1)).tag(anyString(), anyMap(), anyString(), any(), any(), any());
        assertThat(started).isEmpty();
    }

    @Test
    void aWorkflowFixRunsTheCatalogsVerbForTheCallerWithTheirAssertion() {
        RemediationService.Request request = new RemediationService.Request(WFRN, null, null, "mallory");
        IdempotentCommands.Outcome outcome = service.remediate(SCOPE, "compute-baseline", "prod-min-cpu", request, "key-3", "req-4",
                "assertion-for-dana", DANA).block();

        assertThat(outcome.status()).isEqualTo(202);
        assertThat(outcome.body().path("jobId").asText()).isEqualTo("job-1");
        assertThat(asked).containsExactly("scorecards:remediate on " + WFRN, "compute:update on " + WFRN);
        verify(state).requestUpdate(eq(WFRN), eq("update-vm"), eq("1.2.0"), eq(Json.object().put("cpu", 2)), eq("dana"), eq("req-4"));
        Started job = started.get(0);
        assertThat(job.entry().workflow()).isEqualTo("update-vm");
        assertThat(job.input()).containsEntry("vmId", "vm-01").containsEntry("cpu", 2);
        assertThat(job.options().onBehalfOf()).isEqualTo("assertion-for-dana");
        assertThat(job.options().labels()).containsExactly(Map.entry(RemediationService.RULE_LABEL, "compute-baseline.prod-min-cpu"));
        assertThat(job.options().resourceWfrn()).isEqualTo(WFRN);
        assertThat(signed).isEmpty();
    }

    @Test
    void aViewerOrAnAuditorFixesNothing() {
        for (ConsolePrincipal who : List.of(VIC, AUD)) {
            assertThat(refusal("required-tags", "owner", new RemediationService.Request(WFRN, owner("team-compute"), null, null), "assertion", who))
                    .as("%s's tags fix", who.subject()).isInstanceOf(ForbiddenException.class).hasMessageContaining("scorecards:remediate");
            assertThat(refusal("compute-baseline", "prod-min-cpu", new RemediationService.Request(WFRN, null, null, null), "assertion", who))
                    .as("%s's workflow fix", who.subject()).isInstanceOf(ForbiddenException.class);
        }
        verify(state, never()).tag(anyString(), anyMap(), anyString(), any(), any(), any());
        verify(state, never()).requestUpdate(anyString(), anyString(), any(), any(), anyString(), any());
        verify(platform, never()).listCatalog(any());
        assertThat(started).isEmpty();
        assertThat(signed).isEmpty();
    }

    @Test
    void thePersonNeedsTheFixesOwnActionTooBeforeAnythingIsSignedOrChanged() {
        // a role with scorecards:remediate but not the type's update or tag, as a custom role may be
        denied.add("compute:update");
        denied.add("compute:tag");

        assertThat(refusal("compute-baseline", "prod-min-cpu", new RemediationService.Request(WFRN, null, null, null), null, DANA))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("compute:update");
        assertThat(refusal("required-tags", "owner", new RemediationService.Request(WFRN, owner("team-compute"), null, null), null, DANA))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("compute:tag");

        assertThat(signed).isEmpty();
        assertThat(started).isEmpty();
        verify(state, never()).tag(anyString(), anyMap(), anyString(), any(), any(), any());
        verify(state, never()).requestUpdate(anyString(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    void anotherAccountsResourceIsNotThereRatherThanForbidden() {
        String other = "wfrn:wf:compute:global:210987654321:vm/vm-02";
        assertThat(refusal("required-tags", "owner", new RemediationService.Request(other, owner("x"), null, null), "assertion", VIC))
                .isInstanceOf(NotFoundException.class);
        assertThat(asked).isEmpty();
    }

    @Test
    void theVerbCheckedIsTheOneTheWorkflowRuns() {
        List<String> verbs = new ArrayList<>();
        verbs.add(RemediationService.verbOf(entry("restart-vm", "restart"), packs.rule(new RuleKey("compute-baseline", "prod-powered-on")).orElseThrow().remediation()));
        verbs.add(RemediationService.verbOf(entry("mongodb-upgrade", "upgrade"), packs.rule(new RuleKey("data-baseline", "supported-version")).orElseThrow().remediation()));
        assertThat(verbs).containsExactly("restart", "upgrade");
    }
}
