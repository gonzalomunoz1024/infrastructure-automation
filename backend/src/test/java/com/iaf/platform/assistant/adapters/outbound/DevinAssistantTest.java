package com.iaf.platform.assistant.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.domain.Events;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.AssistantRequest;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.Context;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.config.PlatformProperties;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The Devin adapter against a handcrafted exchange function: request shapes, polling, follow-ups, failures. */
class DevinAssistantTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Identity WHO = new Identity("u-ada", "Ada", "ada@example.com", List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);

    /** Answers like the Devin API: one create response, then the GET bodies in order (the last one repeats). */
    static final class FakeDevin implements ExchangeFunction {
        final List<ClientRequest> requests = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final Deque<String> polls = new ArrayDeque<>();
        HttpStatus createStatus = HttpStatus.OK;
        String createBody = "{\"session_id\":\"devin-1\",\"status_enum\":\"working\"}";

        FakeDevin polls(String... bodies) {
            polls.addAll(List.of(bodies));
            return this;
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            requests.add(request);
            bodies.add(request.method() == HttpMethod.GET ? "" : bodyOf(request));
            String path = request.url().getPath();
            if (request.method() == HttpMethod.POST && path.endsWith("/sessions")) {
                return Mono.just(json(createStatus, createBody));
            }
            if (request.method() == HttpMethod.POST && path.endsWith("/message")) {
                return Mono.just(json(HttpStatus.OK, "{\"ok\":true}"));
            }
            if (request.method() == HttpMethod.GET && path.contains("/sessions/")) {
                String body = polls.size() > 1 ? polls.poll() : polls.peek();
                return Mono.just(json(HttpStatus.OK, body == null ? "{}" : body));
            }
            return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
        }

        static String bodyOf(ClientRequest request) {
            MockClientHttpRequest out = new MockClientHttpRequest(request.method(), request.url());
            request.writeTo(out, ExchangeStrategies.withDefaults()).block(Duration.ofSeconds(5));
            return out.getBodyAsString().block(Duration.ofSeconds(5));
        }

        static ClientResponse json(HttpStatus status, String body) {
            return ClientResponse.create(status).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build();
        }

        JsonNode body(int i) {
            try {
                return Events.mapper().readTree(bodies.get(i));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static PlatformProperties.Assistant props() {
        PlatformProperties.Assistant p = new PlatformProperties.Assistant();
        p.setDevinUrl("https://devin.test/v3");
        p.setDevinApiKey("key-1");
        p.setDevinPlaybookId("pb-workflow");
        p.setDevinProviderPlaybookId("pb-provider");
        p.setPlatformTokenSecretId("sec-platform");
        return p;
    }

    static DevinAssistant agent(FakeDevin fake, Duration timeout) {
        WebClient client = WebClient.builder().baseUrl("https://devin.test/v3").exchangeFunction(fake).build();
        return new DevinAssistant(props(), client, CLOCK, Duration.ofMillis(1), timeout);
    }

    static AssistantRequest request(String text, String externalId, String currentDraft) {
        AssistantSession session = new AssistantSession("asst-1", currentDraft == null ? AssistantSession.CREATE : AssistantSession.EDIT, null, WHO.id(),
                AssistantSession.WORKING, null, List.of(), externalId, NOW, NOW);
        return new AssistantRequest(session, text, currentDraft, WHO, new Context("team-compute", "Compute", "team-compute", "http://localhost:8080", List.of()));
    }

    static List<AssistantEvent> run(DevinAssistant agent, AssistantRequest request) {
        List<AssistantEvent> events = agent.run(request).collectList().block(Duration.ofSeconds(10));
        assertThat(events).isNotNull();
        return events;
    }

    @Test
    void createsASessionWithTheWorkflowDraftPlaybookAndStreamsUntilDevinBlocks() {
        FakeDevin fake = new FakeDevin().polls(
                "{\"status_enum\":\"working\",\"messages\":[{\"type\":\"user_message\",\"message\":\"!workflow-draft\"}]}",
                "{\"status_enum\":\"working\",\"messages\":[{\"type\":\"user_message\",\"message\":\"!workflow-draft\"},{\"type\":\"devin_message\",\"message\":\"Reading the registry\"}],"
                        + "\"structured_output\":{\"status\":\"working\",\"summary\":\"drafting\"}}",
                "{\"status_enum\":\"blocked\",\"messages\":[{\"type\":\"user_message\",\"message\":\"!workflow-draft\"},{\"type\":\"devin_message\",\"message\":\"Reading the registry\"},{\"type\":\"devin_message\",\"message\":\"Here is the draft\"}],"
                        + "\"structured_output\":{\"status\":\"ready\",\"summary\":\"done\",\"workflowYaml\":\"kind: Workflow\\n\",\"validation\":{\"valid\":true,\"errors\":[]}}}");
        List<AssistantEvent> events = run(agent(fake, Duration.ofSeconds(5)), request("restart a vm with an approval", null, null));

        ClientRequest create = fake.requests.get(0);
        assertThat(create.method()).isEqualTo(HttpMethod.POST);
        assertThat(create.url().toString()).isEqualTo("https://devin.test/v3/sessions");
        assertThat(create.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer key-1");
        JsonNode body = fake.body(0);
        assertThat(body.path("prompt").asText()).startsWith("!workflow-draft\n\nDESCRIPTION:\nrestart a vm with an approval\n\nMODE: create\n\nCURRENT_DRAFT:\nnone\n\nCONTEXT:\n");
        assertThat(body.path("prompt").asText()).contains("owner: team-compute\n").contains("folder: Compute\n").contains("requester_team: team-compute\n")
                .contains("requester: u-ada\n").contains("PLATFORM_API: http://localhost:8080\n");
        assertThat(body.path("playbook_id").asText()).isEqualTo("pb-workflow");
        assertThat(body.path("secret_ids")).hasSize(1);
        assertThat(body.path("secret_ids").get(0).asText()).isEqualTo("sec-platform");
        assertThat(body.path("tags").get(0).asText()).isEqualTo("workflow-draft");
        assertThat(body.path("title").asText()).startsWith("Workflow draft: restart a vm");
        assertThat(fake.requests.get(1).method()).isEqualTo(HttpMethod.GET);
        assertThat(fake.requests.get(1).url().toString()).isEqualTo("https://devin.test/v3/sessions/devin-1");
        assertThat(fake.requests.get(1).headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer key-1");

        assertThat(events.get(0).type()).isEqualTo(AssistantEvent.STATUS);
        assertThat(events.get(0).data().path("externalId").asText()).isEqualTo("devin-1");
        assertThat(events).extracting(AssistantEvent::type).containsExactly(
                AssistantEvent.STATUS,          // session started
                AssistantEvent.STATUS,          // Devin is working
                AssistantEvent.MESSAGE,         // Reading the registry
                AssistantEvent.DRAFT,           // working output
                AssistantEvent.STATUS,          // Devin is blocked
                AssistantEvent.MESSAGE,         // Here is the draft
                AssistantEvent.DRAFT,           // ready output
                AssistantEvent.VALIDATION,
                AssistantEvent.DONE);
        assertThat(events.get(1).data().path("status").asText()).isEqualTo("Devin is working");
        assertThat(Events.textOf(events.get(2))).isEqualTo("Reading the registry");
        assertThat(events.get(6).data().path("workflowYaml").asText()).isEqualTo("kind: Workflow\n");
        assertThat(events.get(7).data().path("valid").asBoolean()).isTrue();
        assertThat(Events.isFailed(events.get(8))).isFalse();
    }

    @Test
    void followsUpOnTheExistingSessionAndIgnoresTheStaleBlockedStatus() {
        FakeDevin fake = new FakeDevin().polls(
                "{\"status_enum\":\"blocked\",\"messages\":[],\"structured_output\":{\"status\":\"ready\",\"summary\":\"old\"}}",
                "{\"status_enum\":\"working\",\"messages\":[],\"structured_output\":{\"status\":\"ready\",\"summary\":\"old\"}}",
                "{\"status_enum\":\"blocked\",\"messages\":[{\"type\":\"devin_message\",\"message\":\"Added the approval\"}],"
                        + "\"structured_output\":{\"status\":\"ready\",\"summary\":\"new\",\"changes\":[\"approval\"]}}");
        List<AssistantEvent> events = run(agent(fake, Duration.ofSeconds(5)), request("add an approval before the restart", "devin-1", "kind: Workflow\nmetadata:\n  name: x\n"));

        ClientRequest follow = fake.requests.get(0);
        assertThat(follow.method()).isEqualTo(HttpMethod.POST);
        assertThat(follow.url().toString()).isEqualTo("https://devin.test/v3/sessions/devin-1/message");
        assertThat(follow.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer key-1");
        String message = fake.body(0).path("message").asText();
        assertThat(message).doesNotContain("!workflow-draft");
        assertThat(message).startsWith("DESCRIPTION:\nadd an approval before the restart\n\nMODE: edit\n\nCURRENT_DRAFT:\n```yaml\nkind: Workflow\nmetadata:\n  name: x\n```\n");

        assertThat(events).extracting(AssistantEvent::type).containsExactly(
                AssistantEvent.STATUS,          // sent to session
                AssistantEvent.STATUS,          // Devin is blocked (stale, not an end)
                AssistantEvent.DRAFT,           // the old output, as it stands
                AssistantEvent.STATUS,          // Devin is working
                AssistantEvent.STATUS,          // Devin is blocked
                AssistantEvent.MESSAGE,
                AssistantEvent.DRAFT,
                AssistantEvent.DONE);
        assertThat(events.get(0).data().path("externalId").asText()).isEqualTo("devin-1");
        assertThat(events.get(6).data().path("summary").asText()).isEqualTo("new");
        assertThat(fake.requests.stream().filter(r -> r.method() == HttpMethod.GET).count()).isEqualTo(3);
    }

    @Test
    void aSessionThatNeverEndsTimesOutWithAFailedDone() {
        FakeDevin fake = new FakeDevin().polls("{\"status_enum\":\"working\",\"messages\":[]}");
        StepVerifier.create(agent(fake, Duration.ofMillis(60)).run(request("restart a vm", null, null)))
                .expectNextMatches(e -> AssistantEvent.STATUS.equals(e.type()))
                .expectNextMatches(e -> AssistantEvent.STATUS.equals(e.type()) && "Devin is working".equals(e.data().path("status").asText()))
                .expectNextMatches(e -> AssistantEvent.MESSAGE.equals(e.type()) && Events.textOf(e).contains("did not finish"))
                .expectNextMatches(e -> AssistantEvent.DONE.equals(e.type()) && Events.isFailed(e))
                .verifyComplete();
    }

    @Test
    void anApiErrorEndsWithAMessageAndAFailedDone() {
        FakeDevin fake = new FakeDevin();
        fake.createStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        fake.createBody = "{\"detail\":\"boom\"}";
        List<AssistantEvent> events = run(agent(fake, Duration.ofSeconds(5)), request("restart a vm", null, null));
        assertThat(events).extracting(AssistantEvent::type).containsExactly(AssistantEvent.MESSAGE, AssistantEvent.DONE);
        assertThat(Events.textOf(events.get(0))).contains("Devin could not be reached");
        assertThat(Events.isFailed(events.get(1))).isTrue();
        assertThat(events.get(1).data().path("error").asText()).contains("500");
    }

    @Test
    void anExpiredSessionFails() {
        FakeDevin fake = new FakeDevin().polls("{\"status_enum\":\"expired\",\"messages\":[]}");
        List<AssistantEvent> events = run(agent(fake, Duration.ofSeconds(5)), request("restart a vm", null, null));
        assertThat(events).extracting(AssistantEvent::type).containsExactly(AssistantEvent.STATUS, AssistantEvent.STATUS, AssistantEvent.MESSAGE, AssistantEvent.DONE);
        assertThat(Events.textOf(events.get(2))).contains("expired");
        assertThat(Events.isFailed(events.get(3))).isTrue();
    }

    @Test
    void aSessionWithoutAnIdFails() {
        FakeDevin fake = new FakeDevin();
        fake.createBody = "{\"status_enum\":\"working\"}";
        List<AssistantEvent> events = run(agent(fake, Duration.ofSeconds(5)), request("restart a vm", null, null));
        assertThat(events).extracting(AssistantEvent::type).containsExactly(AssistantEvent.MESSAGE, AssistantEvent.DONE);
        assertThat(Events.isFailed(events.get(1))).isTrue();
        assertThat(events.get(1).data().path("error").asText()).contains("no session id");
    }

    @Test
    void draftsAProviderThroughTheProviderPlaybook() {
        FakeDevin fake = new FakeDevin().polls(
                "{\"status_enum\":\"working\",\"messages\":[]}",
                "{\"status_enum\":\"finished\",\"messages\":[],\"structured_output\":{\"status\":\"ready\",\"summary\":\"The backup service\",\"providerYaml\":\"kind: Provider\\n\","
                        + "\"assumptions\":[\"bearer auth\"],\"questions\":[{\"question\":\"Which credential?\"},\"Is prod reachable?\"]}}");
        ProviderDraft draft = agent(fake, Duration.ofSeconds(5)).draftProvider("the backup service at https://backup.example.com", WHO).block(Duration.ofSeconds(10));

        JsonNode body = fake.body(0);
        assertThat(body.path("prompt").asText()).startsWith("!provider-draft\n\nDESCRIPTION:\nthe backup service at https://backup.example.com\n\nCONTEXT:\nowner: team-compute\n");
        assertThat(body.path("playbook_id").asText()).isEqualTo("pb-provider");
        assertThat(body.path("tags").get(0).asText()).isEqualTo("provider-draft");
        assertThat(body.path("secret_ids").get(0).asText()).isEqualTo("sec-platform");

        assertThat(draft).isNotNull();
        assertThat(draft.yaml()).isEqualTo("kind: Provider\n");
        assertThat(draft.summary()).isEqualTo("The backup service");
        assertThat(draft.assumptions()).containsExactly("bearer auth");
        assertThat(draft.questions()).containsExactly("Which credential?", "Is prod reachable?");
    }

    @Test
    void aProviderDraftWithoutStructuredOutputIsAnError() {
        FakeDevin fake = new FakeDevin().polls("{\"status_enum\":\"finished\",\"messages\":[]}");
        StepVerifier.create(agent(fake, Duration.ofSeconds(5)).draftProvider("x", WHO))
                .expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("no provider draft"))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void thePromptCarriesTheCurrentDraftInEditMode() {
        String prompt = DevinAssistant.prompt(request("rename the title", "devin-1", "kind: Workflow\n"));
        assertThat(prompt).startsWith("!workflow-draft\n\n");
        assertThat(prompt).contains("MODE: edit\n\nCURRENT_DRAFT:\n```yaml\nkind: Workflow\n```\n\nCONTEXT:\nowner: team-compute\nfolder: Compute\n");
        assertThat(DevinAssistant.followUp(request("x", "devin-1", null))).startsWith("DESCRIPTION:\nx\n\nMODE: create\n\nCURRENT_DRAFT:\nnone\n");
    }

    @Test
    void modeIsDevin() {
        assertThat(agent(new FakeDevin(), Duration.ofSeconds(1)).mode()).isEqualTo("devin");
    }
}
