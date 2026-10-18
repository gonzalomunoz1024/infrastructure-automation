package com.iaf.platform.assistant.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.assistant.adapters.outbound.PlaybookFiles.PlaybookFile;
import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.domain.Events;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.AssistantRequest;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.Context;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.PlaybookSync;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.ProviderRequest;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.testing.LocalHttp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import reactor.test.StepVerifier;

/** The Devin adapter against a local stand-in for the Devin API v3: request shapes, polling, follow-ups, endings, playbook sync. */
class DevinAssistantTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Identity WHO = new Identity("u-ada", "Ada", "ada@example.com", List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    static final Path REPO_PLAYBOOKS = Path.of("..", "devin");
    static final String ORG = "/v3/organizations/org-test";

    record Hit(String method, String path, String query, String auth, String body) {
        JsonNode json() {
            try {
                return Events.mapper().readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Devin's API on a local port: one create answer, then session and message bodies in order (the last one repeats). */
    static final class FakeDevin {
        final List<Hit> hits = new CopyOnWriteArrayList<>();
        final Deque<String> sessions = new ArrayDeque<>();
        final Deque<String> messages = new ArrayDeque<>();
        int createStatus = 200;
        int deleteStatus = 200;
        String createBody = "{\"session_id\":\"devin-1\",\"status\":\"new\"}";
        String playbooks = "{\"items\":[],\"has_next_page\":false}";

        FakeDevin sessions(String... bodies) {
            sessions.addAll(List.of(bodies));
            return this;
        }

        FakeDevin messages(String... bodies) {
            messages.addAll(List.of(bodies));
            return this;
        }

        synchronized String next(Deque<String> q, String empty) {
            String body = q.size() > 1 ? q.poll() : q.peek();
            return body == null ? empty : body;
        }

        void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            hits.add(new Hit(method, path, ex.getRequestURI().getRawQuery(), ex.getRequestHeaders().getFirst("Authorization"), LocalHttp.body(ex)));
            if (method.equals("POST") && path.endsWith("/sessions")) {
                LocalHttp.respond(ex, createStatus, createBody);
            } else if (method.equals("POST") && path.endsWith("/messages")) {
                LocalHttp.respond(ex, 200, "{\"session_id\":\"devin-1\",\"status\":\"resuming\"}");
            } else if (method.equals("DELETE") && path.contains("/sessions/")) {
                LocalHttp.respond(ex, deleteStatus, "{\"session_id\":\"" + path.substring(path.lastIndexOf('/') + 1) + "\",\"status\":\"exit\"}");
            } else if (method.equals("GET") && path.endsWith("/messages")) {
                LocalHttp.respond(ex, 200, next(messages, "{\"items\":[],\"has_next_page\":false}"));
            } else if (method.equals("GET") && path.contains("/sessions/")) {
                LocalHttp.respond(ex, 200, next(sessions, "{\"status\":\"running\",\"status_detail\":\"working\"}"));
            } else if (method.equals("GET") && path.endsWith("/playbooks")) {
                LocalHttp.respond(ex, 200, playbooks);
            } else if (method.equals("POST") && path.endsWith("/playbooks")) {
                LocalHttp.respond(ex, 200, "{\"playbook_id\":\"playbook-new\"}");
            } else if (method.equals("PUT") && path.contains("/playbooks/")) {
                LocalHttp.respond(ex, 200, "{\"playbook_id\":\"" + path.substring(path.lastIndexOf('/') + 1) + "\"}");
            } else {
                LocalHttp.respond(ex, 404, "{\"title\":\"Not Found\",\"status\":404}");
            }
        }

        List<Hit> hits(String method, String pathSuffix) {
            return hits.stream().filter(h -> h.method().equals(method) && h.path().endsWith(pathSuffix)).toList();
        }

        /** The hits once at least one arrived (the session is ended in the background), or none after a while. */
        List<Hit> await(String method, String pathSuffix) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (hits(method, pathSuffix).isEmpty() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return hits(method, pathSuffix);
        }

        /** No hit of the kind within a short wait. */
        List<Hit> settle(String method, String pathSuffix) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return hits(method, pathSuffix);
        }
    }

    FakeDevin fake;
    HttpServer server;

    @BeforeEach
    void start() throws IOException {
        fake = new FakeDevin();
        server = LocalHttp.start(fake::handle);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static String messages(String... items) {
        return "{\"items\":[" + String.join(",", items) + "],\"has_next_page\":false,\"end_cursor\":null}";
    }

    static String msg(String id, String source, String text) {
        return "{\"event_id\":\"" + id + "\",\"source\":\"" + source + "\",\"message\":\"" + text + "\",\"created_at\":1760695200}";
    }

    PlatformProperties.Assistant props() {
        PlatformProperties.Assistant p = new PlatformProperties.Assistant();
        p.setDevinUrl(LocalHttp.url(server) + "/v3/");
        p.setDevinOrg("org-test");
        p.setDevinApiKey("key-1");
        p.setDevinPlaybookId("pb-workflow");
        p.setDevinProviderPlaybookId("pb-provider");
        p.setPlatformTokenSecretId("sec-platform");
        p.setDevinRepos(List.of("acme/platform-content"));
        return p;
    }

    DevinAssistant agent(PlatformProperties.Assistant props, Path playbooks, Duration timeout) {
        WebClient client = WebClient.builder().baseUrl(DevinAssistant.baseUrl(props)).build();
        return new DevinAssistant(props, playbooks, client, CLOCK, Duration.ofMillis(5), timeout);
    }

    DevinAssistant agent(Duration timeout) {
        return agent(props(), REPO_PLAYBOOKS, timeout);
    }

    static AssistantRequest request(String text, String externalId, String currentDraft) {
        AssistantSession session = new AssistantSession("asst-1", currentDraft == null ? AssistantSession.CREATE : AssistantSession.EDIT, null, WHO.id(),
                AssistantSession.WORKING, null, List.of(), externalId, NOW, NOW);
        return new AssistantRequest(session, text, currentDraft, WHO, new Context("team-compute", "Compute", "team-compute", "https://platform.example.com", List.of()));
    }

    static List<AssistantEvent> run(DevinAssistant agent, AssistantRequest request) {
        List<AssistantEvent> events = agent.run(request).collectList().block(Duration.ofSeconds(10));
        assertThat(events).isNotNull();
        return events;
    }

    @Test
    void createsAnOrganizationSessionWithThePlaybookSchemaReposTagsAndAcuLimitThenStreamsUntilDevinWaits() {
        fake.sessions(
                "{\"status\":\"running\",\"status_detail\":\"working\"}",
                "{\"status\":\"running\",\"status_detail\":\"working\",\"structured_output\":{\"status\":\"working\",\"summary\":\"drafting\"}}",
                "{\"status\":\"running\",\"status_detail\":\"waiting_for_user\",\"structured_output\":{\"status\":\"ready\",\"summary\":\"done\",\"workflowYaml\":\"kind: Workflow\\n\","
                        + "\"validation\":{\"valid\":true,\"errors\":[]}}}")
                .messages(
                        messages(msg("e1", "user", "!workflow-draft")),
                        messages(msg("e1", "user", "!workflow-draft"), msg("e2", "devin", "Reading the registry")),
                        messages(msg("e1", "user", "!workflow-draft"), msg("e2", "devin", "Reading the registry"), msg("e3", "devin", "Here is the draft")));
        List<AssistantEvent> events = run(agent(Duration.ofSeconds(5)), request("restart a vm with an approval", null, null));

        Hit create = fake.hits.get(0);
        assertThat(create.method()).isEqualTo("POST");
        assertThat(create.path()).isEqualTo(ORG + "/sessions");
        assertThat(create.auth()).isEqualTo("Bearer key-1");
        JsonNode body = create.json();
        assertThat(body.path("prompt").asText()).startsWith("!workflow-draft\n\nDESCRIPTION:\nrestart a vm with an approval\n\nMODE: create\n\nCURRENT_DRAFT:\nnone\n\nCONTEXT:\n");
        assertThat(body.path("prompt").asText()).contains("owner: team-compute\n").contains("folder: Compute\n").contains("requester_team: team-compute\n")
                .contains("requester: u-ada\n").contains("PLATFORM_API: https://platform.example.com\n");
        assertThat(body.path("playbook_id").asText()).isEqualTo("pb-workflow");
        assertThat(body.path("structured_output_schema").path("title").asText()).isEqualTo("WorkflowDraft");
        assertThat(body.path("structured_output_required").asBoolean()).isTrue();
        assertThat(body.path("secret_ids")).extracting(JsonNode::asText).containsExactly("sec-platform");
        assertThat(body.path("repos")).extracting(JsonNode::asText).containsExactly("acme/platform-content");
        assertThat(body.path("tags")).extracting(JsonNode::asText).containsExactly("kind:workflow-draft", "draft:asst-1", "user:u-ada");
        assertThat(body.path("title").asText()).startsWith("Workflow draft: restart a vm");
        assertThat(body.path("max_acu_limit").asInt()).isEqualTo(10);
        assertThat(fake.hits("GET", "/sessions/devin-1")).isNotEmpty().allSatisfy(h -> assertThat(h.auth()).isEqualTo("Bearer key-1"));
        assertThat(fake.hits("GET", "/sessions/devin-1/messages")).isNotEmpty()
                .allSatisfy(h -> assertThat(h.path()).isEqualTo(ORG + "/sessions/devin-1/messages"))
                .allSatisfy(h -> assertThat(h.query()).isEqualTo("first=200"));

        assertThat(events).extracting(AssistantEvent::type).containsExactly(
                AssistantEvent.STATUS,          // session started
                AssistantEvent.STATUS,          // Devin is working
                AssistantEvent.MESSAGE,         // Reading the registry
                AssistantEvent.DRAFT,           // working output
                AssistantEvent.STATUS,          // Devin is waiting for you
                AssistantEvent.MESSAGE,         // Here is the draft
                AssistantEvent.DRAFT,           // ready output
                AssistantEvent.DONE);
        assertThat(events.get(0).data().path("externalId").asText()).isEqualTo("devin-1");
        assertThat(events.get(1).data().path("status").asText()).isEqualTo("Devin is working");
        assertThat(Events.textOf(events.get(2))).isEqualTo("Reading the registry");
        assertThat(events.get(4).data().path("status").asText()).isEqualTo("Devin is waiting for you");
        assertThat(events.get(6).data().path("workflowYaml").asText()).isEqualTo("kind: Workflow\n");
        assertThat(Events.isFailed(events.get(7))).isFalse();
        assertThat(fake.settle("DELETE", "/sessions/devin-1")).as("a session that finished is left for follow-ups").isEmpty();
    }

    @Test
    void followUpsGoToTheSessionsMessagesAndWaitUntilDevinHasMoved() {
        String old = "\"structured_output\":{\"status\":\"ready\",\"summary\":\"old\"}";
        fake.sessions(
                "{\"status\":\"running\",\"status_detail\":\"waiting_for_user\"," + old + "}",   // before the message
                "{\"status\":\"running\",\"status_detail\":\"waiting_for_user\"," + old + "}",   // stale: not an end
                "{\"status\":\"running\",\"status_detail\":\"working\"," + old + "}",
                "{\"status\":\"running\",\"status_detail\":\"waiting_for_user\",\"structured_output\":{\"status\":\"ready\",\"summary\":\"new\",\"changes\":[\"approval\"]}}")
                .messages(
                        messages(msg("e1", "user", "!workflow-draft"), msg("e2", "devin", "old words")),
                        messages(msg("e1", "user", "!workflow-draft"), msg("e2", "devin", "old words")),
                        messages(msg("e1", "user", "!workflow-draft"), msg("e2", "devin", "old words"), msg("e3", "user", "add an approval")),
                        messages(msg("e1", "user", "!workflow-draft"), msg("e2", "devin", "old words"), msg("e3", "user", "add an approval"),
                                msg("e4", "devin", "Added the approval")));
        List<AssistantEvent> events = run(agent(Duration.ofSeconds(5)), request("add an approval before the restart", "devin-1", "kind: Workflow\nmetadata:\n  name: x\n"));

        assertThat(fake.hits("POST", "/sessions")).isEmpty();
        List<Hit> sent = fake.hits("POST", "/sessions/devin-1/messages");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).path()).isEqualTo(ORG + "/sessions/devin-1/messages");
        assertThat(sent.get(0).auth()).isEqualTo("Bearer key-1");
        String message = sent.get(0).json().path("message").asText();
        assertThat(message).doesNotContain("!workflow-draft");
        assertThat(message).startsWith("DESCRIPTION:\nadd an approval before the restart\n\nMODE: edit\n\nCURRENT_DRAFT:\n```yaml\nkind: Workflow\nmetadata:\n  name: x\n```\n");

        assertThat(events).extracting(AssistantEvent::type).containsExactly(
                AssistantEvent.STATUS,          // sent to session
                AssistantEvent.STATUS,          // Devin is working
                AssistantEvent.STATUS,          // Devin is waiting for you
                AssistantEvent.MESSAGE,         // only the new words
                AssistantEvent.DRAFT,           // only the new output
                AssistantEvent.DONE);
        assertThat(events.get(0).data().path("externalId").asText()).isEqualTo("devin-1");
        assertThat(Events.textOf(events.get(3))).isEqualTo("Added the approval");
        assertThat(events.get(4).data().path("summary").asText()).isEqualTo("new");
    }

    @Test
    void thePlatformsChecksGoToTheSessionWordForWord() {
        fake.sessions("{\"status\":\"running\",\"status_detail\":\"waiting_for_user\",\"structured_output\":{\"status\":\"ready\",\"summary\":\"old\"}}",
                "{\"status\":\"running\",\"status_detail\":\"waiting_for_user\",\"structured_output\":{\"status\":\"ready\",\"summary\":\"fixed\"}}");
        AssistantRequest r = request("x", "devin-1", "kind: Workflow\n");
        AssistantRequest checks = new AssistantRequest(r.session(), "PLATFORM_CHECKS (round 1 of 3):\n- provider.unknown at step restart: no provider\n",
                r.currentDraft(), WHO, r.context(), true);
        List<AssistantEvent> events = run(agent(Duration.ofSeconds(5)), checks);

        assertThat(fake.hits("POST", "/sessions/devin-1/messages").get(0).json().path("message").asText())
                .isEqualTo("PLATFORM_CHECKS (round 1 of 3):\n- provider.unknown at step restart: no provider\n");
        assertThat(events.get(0).data().path("status").asText()).isEqualTo("Sent the platform's checks to Devin session devin-1");
        assertThat(events).extracting(AssistantEvent::type).endsWith(AssistantEvent.DRAFT, AssistantEvent.DONE);
    }

    @Test
    void messagesArePagedWithTheEndCursor() {
        HttpServer paged = null;
        try {
            List<String> queries = new CopyOnWriteArrayList<>();
            paged = LocalHttp.start(ex -> {
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/messages")) {
                    String q = ex.getRequestURI().getRawQuery();
                    queries.add(q);
                    LocalHttp.respond(ex, 200, q.contains("after=")
                            ? messages(msg("e2", "devin", "second page"))
                            : "{\"items\":[" + msg("e1", "devin", "first page") + "],\"has_next_page\":true,\"end_cursor\":\"c+1=\"}");
                } else if (path.endsWith("/sessions")) {
                    LocalHttp.respond(ex, 200, "{\"session_id\":\"devin-1\"}");
                } else {
                    LocalHttp.respond(ex, 200, "{\"status\":\"running\",\"status_detail\":\"waiting_for_user\"}");
                }
            });
            PlatformProperties.Assistant p = props();
            p.setDevinUrl(LocalHttp.url(paged) + "/v3");
            List<AssistantEvent> events = run(agent(p, REPO_PLAYBOOKS, Duration.ofSeconds(5)), request("x", null, null));
            assertThat(events).filteredOn(e -> AssistantEvent.MESSAGE.equals(e.type())).extracting(Events::textOf).containsExactly("first page", "second page");
            assertThat(queries).contains("first=200", "first=200&after=c%2B1%3D");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (paged != null) {
                paged.stop(0);
            }
        }
    }

    @Test
    void aSessionThatNeverEndsTimesOutWithAFailedDoneAndIsEndedAndForgotten() {
        fake.sessions("{\"status\":\"running\",\"status_detail\":\"working\"}");
        StepVerifier.create(agent(Duration.ofSeconds(2)).run(request("restart a vm", null, null)))
                .expectNextMatches(e -> AssistantEvent.STATUS.equals(e.type()))
                .expectNextMatches(e -> AssistantEvent.STATUS.equals(e.type()) && "Devin is working".equals(e.data().path("status").asText()))
                .expectNextMatches(e -> AssistantEvent.MESSAGE.equals(e.type()) && Events.textOf(e).contains("did not finish")
                        && Events.textOf(e).contains("your next message starts a new one"))
                .expectNextMatches(e -> AssistantEvent.STATUS.equals(e.type()) && e.data().path("externalId").asText("x").isEmpty())
                .expectNextMatches(e -> AssistantEvent.DONE.equals(e.type()) && Events.isFailed(e))
                .verifyComplete();

        List<Hit> ended = fake.await("DELETE", "/sessions/devin-1");
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).path()).isEqualTo(ORG + "/sessions/devin-1");
        assertThat(ended.get(0).query()).isEqualTo("archive=true");
        assertThat(ended.get(0).auth()).isEqualTo("Bearer key-1");
    }

    @Test
    void aBuilderThatStopsListeningEndsTheSessionBestEffort() {
        fake.sessions("{\"status\":\"running\",\"status_detail\":\"working\"}");

        List<AssistantEvent> seen = agent(Duration.ofSeconds(30)).run(request("restart a vm", null, null)).take(2).collectList().block(Duration.ofSeconds(10));

        assertThat(seen).hasSize(2);
        assertThat(fake.await("DELETE", "/sessions/devin-1")).singleElement().satisfies(h -> assertThat(h.query()).isEqualTo("archive=true"));

        // a Devin that refuses to end it only costs a log line
        fake.hits.clear();
        fake.deleteStatus = 500;
        List<AssistantEvent> again = agent(Duration.ofSeconds(30)).run(request("restart a vm", null, null)).take(2).collectList().block(Duration.ofSeconds(10));
        assertThat(again).hasSize(2);
        assertThat(fake.await("DELETE", "/sessions/devin-1")).hasSize(1);
    }

    @Test
    void aProviderDraftThatTimesOutEndsItsSession() {
        fake.sessions("{\"status\":\"running\",\"status_detail\":\"working\"}");
        StepVerifier.create(agent(Duration.ofMillis(300)).draftProvider("the backup service", WHO))
                .expectErrorMatches(e -> e.getMessage().contains("no provider draft"))
                .verify(Duration.ofSeconds(10));
        assertThat(fake.await("DELETE", "/sessions/devin-1")).hasSize(1);
    }

    @Test
    void eachPollReadsOnFromTheLastEndCursorInsteadOfTheWholeHistory() {
        HttpServer cursored = null;
        try {
            List<String> queries = new CopyOnWriteArrayList<>();
            java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
            cursored = LocalHttp.start(ex -> {
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/messages")) {
                    String q = ex.getRequestURI().getRawQuery();
                    queries.add(q);
                    if (!q.contains("after=")) {
                        LocalHttp.respond(ex, 200, "{\"items\":[" + msg("e1", "devin", "Reading the registry") + "],\"has_next_page\":false,\"end_cursor\":\"c1\"}");
                    } else if (q.contains("after=c1") && polls.get() < 3) {
                        LocalHttp.respond(ex, 200, "{\"items\":[],\"has_next_page\":false,\"end_cursor\":null}");
                    } else if (q.contains("after=c1")) {
                        LocalHttp.respond(ex, 200, "{\"items\":[" + msg("e2", "devin", "Here is the draft") + "],\"has_next_page\":false,\"end_cursor\":\"c2\"}");
                    } else {
                        LocalHttp.respond(ex, 200, "{\"items\":[],\"has_next_page\":false,\"end_cursor\":null}");
                    }
                } else if (path.endsWith("/sessions")) {
                    LocalHttp.respond(ex, 200, "{\"session_id\":\"devin-1\"}");
                } else {
                    int n = polls.incrementAndGet();
                    LocalHttp.respond(ex, 200, n < 5 ? "{\"status\":\"running\",\"status_detail\":\"working\"}" : "{\"status\":\"running\",\"status_detail\":\"waiting_for_user\"}");
                }
            });
            PlatformProperties.Assistant p = props();
            p.setDevinUrl(LocalHttp.url(cursored) + "/v3");

            List<AssistantEvent> events = run(agent(p, REPO_PLAYBOOKS, Duration.ofSeconds(5)), request("x", null, null));

            assertThat(events).filteredOn(e -> AssistantEvent.MESSAGE.equals(e.type())).extracting(Events::textOf)
                    .containsExactly("Reading the registry", "Here is the draft");
            assertThat(queries.get(0)).isEqualTo("first=200");
            assertThat(queries.subList(1, queries.size())).as("every later poll reads on from the cursor").isNotEmpty()
                    .allSatisfy(q -> assertThat(q).startsWith("first=200&after=c"));
            assertThat(queries).contains("first=200&after=c2");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (cursored != null) {
                cursored.stop(0);
            }
        }
    }

    @Test
    void aCursorDevinRefusesSendsTheReadBackToTheStartWithoutRepeatingMessages() {
        HttpServer refusing = null;
        try {
            List<String> queries = new CopyOnWriteArrayList<>();
            java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
            refusing = LocalHttp.start(ex -> {
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/messages")) {
                    String q = ex.getRequestURI().getRawQuery();
                    queries.add(q);
                    if (q.contains("after=stale")) {
                        LocalHttp.respond(ex, 400, "{\"title\":\"invalid cursor\",\"status\":400}");
                    } else if (!q.contains("after=")) {
                        String items = polls.get() < 2 ? msg("e1", "devin", "first") : msg("e1", "devin", "first") + "," + msg("e2", "devin", "second");
                        LocalHttp.respond(ex, 200, "{\"items\":[" + items + "],\"has_next_page\":false,\"end_cursor\":\"stale\"}");
                    } else {
                        LocalHttp.respond(ex, 200, "{\"items\":[],\"has_next_page\":false,\"end_cursor\":null}");
                    }
                } else if (path.endsWith("/sessions")) {
                    LocalHttp.respond(ex, 200, "{\"session_id\":\"devin-1\"}");
                } else {
                    int n = polls.incrementAndGet();
                    LocalHttp.respond(ex, 200, n < 3 ? "{\"status\":\"running\",\"status_detail\":\"working\"}" : "{\"status\":\"running\",\"status_detail\":\"waiting_for_user\"}");
                }
            });
            PlatformProperties.Assistant p = props();
            p.setDevinUrl(LocalHttp.url(refusing) + "/v3");

            List<AssistantEvent> events = run(agent(p, REPO_PLAYBOOKS, Duration.ofSeconds(5)), request("x", null, null));

            assertThat(events).filteredOn(e -> AssistantEvent.MESSAGE.equals(e.type())).extracting(Events::textOf).containsExactly("first", "second");
            assertThat(queries).containsSubsequence("first=200", "first=200&after=stale", "first=200");
            assertThat(events).last().satisfies(e -> assertThat(Events.isFailed(e)).isFalse());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (refusing != null) {
                refusing.stop(0);
            }
        }
    }

    @Test
    void anApiErrorEndsWithAMessageAndAFailedDone() {
        fake.createStatus = 500;
        fake.createBody = "{\"title\":\"boom\",\"status\":500}";
        List<AssistantEvent> events = run(agent(Duration.ofSeconds(5)), request("restart a vm", null, null));
        assertThat(events).extracting(AssistantEvent::type).containsExactly(AssistantEvent.MESSAGE, AssistantEvent.DONE);
        assertThat(Events.textOf(events.get(0))).contains("Devin could not be reached");
        assertThat(Events.isFailed(events.get(1))).isTrue();
        assertThat(events.get(1).data().path("error").asText()).contains("500");
        assertThat(fake.settle("DELETE", "/sessions")).as("no session, nothing to end").isEmpty();
    }

    @Test
    void anErroredOrOutOfCreditsSessionFailsAndAnExitedOrIdleOneEnds() {
        fake.sessions("{\"status\":\"error\",\"status_detail\":\"error\"}");
        List<AssistantEvent> errored = run(agent(Duration.ofSeconds(5)), request("restart a vm", null, null));
        assertThat(errored).extracting(AssistantEvent::type).containsExactly(AssistantEvent.STATUS, AssistantEvent.STATUS, AssistantEvent.MESSAGE, AssistantEvent.DONE);
        assertThat(errored.get(1).data().path("status").asText()).isEqualTo("Devin stopped with an error");
        assertThat(Events.isFailed(errored.get(3))).isTrue();

        fake.sessions.clear();
        fake.sessions("{\"status\":\"suspended\",\"status_detail\":\"out_of_credits\"}");
        List<AssistantEvent> broke = run(agent(Duration.ofSeconds(5)), request("restart a vm", null, null));
        assertThat(Events.textOf(broke.get(2))).contains("out of credits");
        assertThat(Events.isFailed(broke.get(3))).isTrue();

        fake.sessions.clear();
        fake.sessions("{\"status\":\"exit\"}");
        List<AssistantEvent> exited = run(agent(Duration.ofSeconds(5)), request("restart a vm", null, null));
        assertThat(exited).extracting(AssistantEvent::type).containsExactly(AssistantEvent.STATUS, AssistantEvent.STATUS, AssistantEvent.DONE);
        assertThat(Events.isFailed(exited.get(2))).isFalse();

        fake.sessions.clear();
        fake.sessions("{\"status\":\"suspended\",\"status_detail\":\"inactivity\"}");
        List<AssistantEvent> idle = run(agent(Duration.ofSeconds(5)), request("restart a vm", null, null));
        assertThat(idle.get(1).data().path("status").asText()).isEqualTo("Devin is suspended (inactivity)");
        assertThat(Events.isFailed(idle.get(2))).isFalse();
    }

    @Test
    void aSessionWithoutAnIdFails() {
        fake.createBody = "{\"status\":\"new\"}";
        List<AssistantEvent> events = run(agent(Duration.ofSeconds(5)), request("restart a vm", null, null));
        assertThat(events).extracting(AssistantEvent::type).containsExactly(AssistantEvent.MESSAGE, AssistantEvent.DONE);
        assertThat(Events.isFailed(events.get(1))).isTrue();
        assertThat(events.get(1).data().path("error").asText()).contains("no session id");
    }

    @Test
    void aMissingOrganizationFailsWithAClearMessage() {
        PlatformProperties.Assistant p = props();
        p.setDevinOrg("");
        List<AssistantEvent> events = run(agent(p, REPO_PLAYBOOKS, Duration.ofSeconds(5)), request("restart a vm", null, null));
        assertThat(events.get(1).data().path("error").asText()).contains("devin-org is not set");
        assertThat(fake.hits).isEmpty();
    }

    @Test
    void draftsAProviderThroughTheProviderPlaybookAndSchemaWithoutReadingMessages() {
        fake.sessions(
                "{\"status\":\"running\",\"status_detail\":\"working\"}",
                "{\"status\":\"running\",\"status_detail\":\"finished\",\"structured_output\":{\"status\":\"ready\",\"summary\":\"The backup service\",\"providerYaml\":\"kind: Provider\\n\","
                        + "\"assumptions\":[\"bearer auth\"],\"questions\":[\"Which credential?\"]}}");
        ProviderRequest request = new ProviderRequest("pdraft-1", "the backup service at https://backup.example.com", WHO,
                new Context("team-compute", null, "team-compute", "https://platform.example.com", List.of()));
        ProviderDraft draft = agent(Duration.ofSeconds(5)).draftProvider(request).block(Duration.ofSeconds(10));

        JsonNode body = fake.hits("POST", "/sessions").get(0).json();
        assertThat(body.path("prompt").asText()).startsWith("!provider-draft\n\nDESCRIPTION:\nthe backup service at https://backup.example.com\n\nCONTEXT:\nowner: team-compute\n")
                .contains("PLATFORM_API: https://platform.example.com\n");
        assertThat(body.path("playbook_id").asText()).isEqualTo("pb-provider");
        assertThat(body.path("structured_output_schema").path("title").asText()).isEqualTo("ProviderDraft");
        assertThat(body.path("tags")).extracting(JsonNode::asText).containsExactly("kind:provider-draft", "draft:pdraft-1", "user:u-ada");
        assertThat(body.path("secret_ids").get(0).asText()).isEqualTo("sec-platform");
        assertThat(fake.hits("GET", "/messages")).isEmpty();

        assertThat(draft).isNotNull();
        assertThat(draft.yaml()).isEqualTo("kind: Provider\n");
        assertThat(draft.summary()).isEqualTo("The backup service");
        assertThat(draft.assumptions()).containsExactly("bearer auth");
        assertThat(draft.questions()).containsExactly("Which credential?");
    }

    @Test
    void aProviderDraftWithoutStructuredOutputIsAnError() {
        fake.sessions("{\"status\":\"exit\"}");
        StepVerifier.create(agent(Duration.ofSeconds(5)).draftProvider("x", WHO))
                .expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("no provider draft"))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void syncCreatesMissingPlaybooksUpdatesChangedOnesAndLeavesTheRest(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.md"), "---\ntitle: Draft a workflow\nmacro: \"!workflow-draft\"\nstructured_output_schema: a.schema.json\n---\n\n## Overview\n\nSame.\n");
        Files.writeString(dir.resolve("a.schema.json"), "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}}}");
        Files.writeString(dir.resolve("b.md"), "---\ntitle: Draft a provider\nmacro: \"!provider-draft\"\n---\n\n## Overview\n\nNew words.\n");
        Files.writeString(dir.resolve("c.md"), "---\ntitle: Something new\nmacro: \"!something-new\"\n---\nBody.\n");
        Files.writeString(dir.resolve("README.md"), "# Not a playbook\n");
        fake.playbooks = "{\"items\":["
                + "{\"playbook_id\":\"playbook-a\",\"title\":\"Draft a workflow\",\"body\":\"## Overview\\n\\nSame.\\n\",\"macro\":\"!workflow-draft\",\"access_type\":\"org\","
                + "\"structured_output_schema\":{\"properties\":{\"status\":{\"type\":\"string\"}},\"type\":\"object\"}},"
                + "{\"playbook_id\":\"playbook-b\",\"title\":\"Draft a provider\",\"body\":\"Old words.\",\"macro\":\"!provider-draft\",\"access_type\":\"org\"},"
                + "{\"playbook_id\":\"playbook-ent\",\"title\":\"Theirs\",\"body\":\"x\",\"macro\":\"!something-new\",\"access_type\":\"enterprise\"}"
                + "],\"has_next_page\":false}";
        PlatformProperties.Assistant p = props();
        p.setDevinPlaybookId("");
        DevinAssistant agent = agent(p, dir, Duration.ofSeconds(5));

        List<PlaybookSync> result = agent.syncPlaybooks().block(Duration.ofSeconds(10));

        assertThat(result).containsExactly(
                new PlaybookSync("a.md", "!workflow-draft", "playbook-a", "unchanged"),
                new PlaybookSync("b.md", "!provider-draft", "playbook-b", "updated"),
                new PlaybookSync("c.md", "!something-new", "playbook-new", "created"));
        assertThat(fake.hits("GET", "/playbooks").get(0).query()).isEqualTo("first=200");
        JsonNode put = fake.hits("PUT", "/playbooks/playbook-b").get(0).json();
        assertThat(put.path("title").asText()).isEqualTo("Draft a provider");
        assertThat(put.path("body").asText()).isEqualTo("## Overview\n\nNew words.\n");
        assertThat(put.path("macro").asText()).isEqualTo("!provider-draft");
        JsonNode post = fake.hits("POST", "/playbooks").get(0).json();
        assertThat(post.path("macro").asText()).isEqualTo("!something-new");
        assertThat(fake.hits("PUT", "/playbooks/playbook-a")).isEmpty();

        // with no playbook id configured, sessions use the one the sync found
        fake.sessions("{\"status\":\"exit\"}");
        run(agent, request("x", null, null));
        assertThat(fake.hits("POST", "/sessions").get(0).json().path("playbook_id").asText()).isEqualTo("playbook-a");
    }

    @Test
    void thePlaybookFilesInTheRepoParseWithinDevinsLimits() throws IOException {
        List<PlaybookFile> files = PlaybookFiles.read(REPO_PLAYBOOKS);
        assertThat(files).extracting(PlaybookFile::macro).containsExactly("!workflow-draft", "!provider-draft");
        assertThat(files).allSatisfy(f -> {
            assertThat(f.title()).isNotBlank();
            assertThat(f.body()).startsWith("## Overview").doesNotContain("structured_output_schema: ");
            assertThat(f.schema()).isNotNull();
        });
        assertThat(files.get(0).schema().path("title").asText()).isEqualTo("WorkflowDraft");
        assertThat(files.get(1).schema().path("title").asText()).isEqualTo("ProviderDraft");
    }

    @Test
    void playbookFilesOutsideDevinsLimitsAreRefused() {
        assertThatThrownBy(() -> PlaybookFiles.parse("x.md", "---\ntitle: X\nmacro: \"no-bang\"\n---\nbody\n", n -> null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("macro");
        assertThatThrownBy(() -> PlaybookFiles.parse("x.md", "---\ntitle: X\nmacro: \"!x\"\nstructured_output_schema: s.json\n---\nbody\n",
                n -> "{\"properties\":{\"a\":{\"$ref\":\"https://example.com/other.json\"}}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("external $ref");
        assertThatThrownBy(() -> PlaybookFiles.schema("big.json", "{\"description\":\"" + "x".repeat(70_000) + "\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64 KB");
        assertThat(PlaybookFiles.parse("note.md", "# just notes\n", n -> null)).isNull();
        assertThat(PlaybookFiles.parse("x.md", "---\ntitle: X\nmacro: \"!x\"\nstructured_output_schema: s.json\n---\nbody\n",
                n -> "{\"definitions\":{\"a\":{}},\"properties\":{\"b\":{\"$ref\":\"#/definitions/a\"}}}").schema()).isNotNull();
    }

    @Test
    void pollingIsKeptBetweenTenAndThirtySeconds() {
        assertThat(DevinAssistant.pollEvery(null)).isEqualTo(Duration.ofSeconds(15));
        assertThat(DevinAssistant.pollEvery(Duration.ofSeconds(3))).isEqualTo(Duration.ofSeconds(10));
        assertThat(DevinAssistant.pollEvery(Duration.ofSeconds(45))).isEqualTo(Duration.ofSeconds(30));
        assertThat(DevinAssistant.pollEvery(Duration.ofSeconds(20))).isEqualTo(Duration.ofSeconds(20));
        PlatformProperties.Assistant p = new PlatformProperties.Assistant();
        p.setDevinOrg("org-abc");
        assertThat(DevinAssistant.baseUrl(p)).isEqualTo("https://api.devin.ai/v3/organizations/org-abc");
        assertThat(p.getDevinPollEvery()).isEqualTo(Duration.ofSeconds(15));
        assertThat(p.getMaxRounds()).isEqualTo(3);
    }

    @Test
    void thePromptCarriesTheCurrentDraftInEditMode() {
        String prompt = DevinAssistant.prompt(request("rename the title", "devin-1", "kind: Workflow\n"));
        assertThat(prompt).startsWith("!workflow-draft\n\n");
        assertThat(prompt).contains("MODE: edit\n\nCURRENT_DRAFT:\n```yaml\nkind: Workflow\n```\n\nCONTEXT:\nowner: team-compute\nfolder: Compute\n");
        assertThat(DevinAssistant.followUp(request("x", "devin-1", null))).startsWith("DESCRIPTION:\nx\n\nMODE: create\n\nCURRENT_DRAFT:\nnone\n");
    }

    @Test
    void askingWhatTheDraftDoesSendsTheExplainMode() {
        assertThat(DevinAssistant.followUp(request("Explain what this workflow does", "devin-1", "kind: Workflow\n")))
                .startsWith("DESCRIPTION:\nExplain what this workflow does\n\nMODE: explain\n\nCURRENT_DRAFT:\n```yaml\nkind: Workflow\n```\n");
        assertThat(DevinAssistant.prompt(request("Explain what this workflow does", null, null))).as("nothing to explain yet").contains("MODE: create\n");
    }

    @Test
    void modeIsDevinAndItWantsThePlatformsChecks() {
        DevinAssistant agent = agent(Duration.ofSeconds(1));
        assertThat(agent.mode()).isEqualTo("devin");
        assertThat(agent.needsPlatformChecks()).isTrue();
    }
}
