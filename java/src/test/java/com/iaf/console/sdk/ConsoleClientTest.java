package com.iaf.console.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.sdk.StubBff.Reply;
import com.iaf.console.sdk.StubBff.Request;
import com.iaf.console.sdk.api.CommandsApi;
import com.iaf.console.sdk.api.JobsApi;
import com.iaf.console.sdk.client.ApiException;
import com.iaf.console.sdk.model.CommandAccepted;
import com.iaf.console.sdk.model.CommandInput;
import com.iaf.console.sdk.model.Denial;
import com.iaf.console.sdk.model.JobDetail;
import com.iaf.console.sdk.model.JobSummary;
import com.iaf.console.sdk.model.ListJobs200Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The client against a stub BFF: auth and refresh, idempotency keys, retries, errors, paging, requests by path, events. */
class ConsoleClientTest {
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Pattern UUID_V4 = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final String NO_CSRF = ConsoleClient.NO_CSRF;
    private static final String JOB = "{\"jobId\":\"job-1\",\"workflow\":\"vm.create\",\"status\":\"running\"}";
    private static final String ACCEPTED = "{\"wfrn\":\"wfrn:iaf:compute:eu-west-1:111111111111:vm/web-9\",\"jobId\":\"job-7\",\"requestId\":\"req-1\"}";
    private static final String WFRN = "wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1";
    private static final String INVALID_TOKEN = "{\"error\":\"auth.invalid-token\"}";
    private static final String TOKENS_2 = "{\"access_token\":\"at-2\",\"token_type\":\"Bearer\",\"expires_in\":900,"
            + "\"refresh_token\":\"rt-2\",\"refresh_expires_in\":43200,\"scope\":\"console\"}";

    private static StubBff bff;
    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void start() throws IOException {
        bff = StubBff.start();
    }

    @AfterAll
    static void stop() {
        bff.close();
    }

    @BeforeEach
    void reset() {
        bff.reset();
        sleeps.clear();
    }

    private ConsoleClient.Builder client(TokenProvider tokens) {
        return ConsoleClient.builder().endpoint(bff.url()).credentials(tokens).sleeper(sleeps::add).clock(CLOCK);
    }

    private RefreshingCredentials refreshing(List<TokenSet> saved) {
        return RefreshingCredentials.builder()
                .auth(ConsoleAuth.builder(bff.url()).clock(CLOCK).build())
                .tokens(new TokenSet("at-1", "Bearer", "rt-1", NOW.plusSeconds(600), null, "console", "console-sdk"))
                .onRefresh(saved::add)
                .clock(CLOCK)
                .build();
    }

    // --- auth -----------------------------------------------------------------------------------

    @Test
    void everyCallCarriesTheBearerTokenTheAccountAndTheRegion() {
        bff.on("GET", "/console/v1/jobs/job-1", r -> Reply.json(200, JOB));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).account("111111111111").region("eu-west-1").build()) {
            JobDetail job = console.call(() -> console.api(JobsApi.class).getJob("job-1"));
            assertEquals("job-1", job.getJobId());
            assertEquals(JobDetail.StatusEnum.RUNNING, job.getStatus());
        }
        Request r = bff.requests().get(0);
        assertEquals("Bearer at-1", r.header("Authorization"));
        assertEquals("111111111111", r.header("X-Console-Account"));
        assertEquals("eu-west-1", r.header("X-Console-Region"));
        assertTrue(r.header("User-Agent").startsWith("console-sdk-java/"), r.header("User-Agent"));
        assertNull(r.header("Idempotency-Key"), "a read takes no key");
    }

    @Test
    void a401InvalidTokenRenewsTheTokenOnceAndSendsTheCallAgainWithTheSameKey() {
        bff.on("POST", "/console/v1/jobs/job-1:cancel", r -> "Bearer at-2".equals(r.header("Authorization"))
                ? Reply.empty(204)
                : Reply.json(401, INVALID_TOKEN).with("WWW-Authenticate", "Bearer error=\"invalid_token\""));
        bff.on("POST", "/auth/token", r -> Reply.json(200, TOKENS_2));
        List<TokenSet> saved = new ArrayList<>();
        RefreshingCredentials credentials = refreshing(saved);
        try (ConsoleClient console = client(credentials).build()) {
            console.run(() -> console.api(JobsApi.class).cancelJob(NO_CSRF, "job-1"));
        }
        List<Request> attempts = bff.requests("POST", "/console/v1/jobs/job-1:cancel");
        assertEquals(List.of("Bearer at-1", "Bearer at-2"), attempts.stream().map(r -> r.header("Authorization")).toList());
        assertEquals(attempts.get(0).header("Idempotency-Key"), attempts.get(1).header("Idempotency-Key"), "one key for every attempt");
        assertEquals(Map.of("grant_type", "refresh_token", "refresh_token", "rt-1", "client_id", "console-sdk"),
                bff.requests("POST", "/auth/token").get(0).form());
        assertEquals("rt-2", saved.get(0).refreshToken(), "the rotated refresh token is handed over");
        assertEquals(NOW.plusSeconds(900), saved.get(0).expiresAt());
        assertEquals("at-2", credentials.current().accessToken());
    }

    @Test
    void aSecond401IsTheCallersAndNoThirdAttemptIsMade() {
        // invalid_token in the body, without a WWW-Authenticate challenge
        bff.on("GET", "/console/v1/jobs/job-1", r -> Reply.json(401, "{\"error\":\"invalid_token\"}"));
        bff.on("POST", "/auth/token", r -> Reply.json(200, TOKENS_2));
        try (ConsoleClient console = client(refreshing(new ArrayList<>())).build()) {
            JobsApi jobs = console.api(JobsApi.class);
            ConsoleApiException e = assertThrows(ConsoleApiException.class, () -> console.call(() -> jobs.getJob("job-1")));
            assertEquals(401, e.status());
            assertEquals("invalid_token", e.error());
        }
        assertEquals(2, bff.requests("GET", "/console/v1/jobs/job-1").size());
        assertEquals(1, bff.requests("POST", "/auth/token").size());
    }

    @Test
    void aStatic401OrOneThatIsNotInvalidTokenIsNotRefreshed() {
        bff.on("GET", "/console/v1/jobs/job-1", r -> Reply.json(401, "{\"error\":\"auth.required\"}").with("WWW-Authenticate", "Bearer realm=\"console\""));
        bff.on("GET", "/console/v1/jobs/job-2", r -> Reply.json(401, INVALID_TOKEN).with("WWW-Authenticate", "Bearer error=\"invalid_token\""));
        try (ConsoleClient console = client(refreshing(new ArrayList<>())).build();
                ConsoleClient fixed = client(TokenProvider.of("at-1")).build()) {
            assertEquals("auth.required", assertThrows(ConsoleApiException.class,
                    () -> console.call(() -> console.api(JobsApi.class).getJob("job-1"))).error());
            assertEquals(401, assertThrows(ConsoleApiException.class,
                    () -> fixed.call(() -> fixed.api(JobsApi.class).getJob("job-2"))).status());
        }
        assertTrue(bff.requests("POST", "/auth/token").isEmpty());
        assertEquals(1, bff.requests("GET", "/console/v1/jobs/job-1").size());
        assertEquals(1, bff.requests("GET", "/console/v1/jobs/job-2").size());
    }

    @Test
    void aRefreshThatFailsIsThe401CarryingWhy() {
        bff.on("GET", "/console/v1/jobs/job-1", r -> Reply.json(401, INVALID_TOKEN).with("WWW-Authenticate", "Bearer error=\"invalid_token\""));
        bff.on("POST", "/auth/token", r -> Reply.json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"the API session was revoked\"}"));
        try (ConsoleClient console = client(refreshing(new ArrayList<>())).build()) {
            ConsoleApiException e = assertThrows(ConsoleApiException.class, () -> console.call(() -> console.api(JobsApi.class).getJob("job-1")));
            assertEquals(401, e.status());
            assertEquals("getJob", e.operationId());
            assertEquals("GET", e.method());
            OAuthException why = assertInstanceOf(OAuthException.class, e.getCause());
            assertEquals("invalid_grant", why.error());
            assertEquals("the API session was revoked", why.description());
        }
    }

    // --- idempotency keys and retries ------------------------------------------------------------

    @Test
    void everyWriteCarriesAnIdempotencyKeyAndOneTheCallerGivesWins() throws Exception {
        bff.on("POST", "/console/v1/jobs/job-1:cancel", r -> Reply.empty(204));
        bff.on("POST", "/console/v1/r/vm", r -> Reply.json(202, ACCEPTED));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build()) {
            JobsApi jobs = console.api(JobsApi.class);
            console.run(() -> jobs.cancelJob(NO_CSRF, "job-1"));
            console.run(() -> jobs.cancelJob(NO_CSRF, "job-1", Map.of("Idempotency-Key", "mine-1")));
            CommandAccepted accepted = console.call(() -> console.api(CommandsApi.class)
                    .createResource(NO_CSRF, "vm", new CommandInput().input(Map.of("name", "web-9")), "mine-2"));
            assertEquals("job-7", accepted.getJobId());
        }
        List<Request> cancels = bff.requests("POST", "/console/v1/jobs/job-1:cancel");
        assertTrue(UUID_V4.matcher(cancels.get(0).header("Idempotency-Key")).matches(), cancels.get(0).header("Idempotency-Key"));
        assertNull(cancels.get(0).header("X-Csrf-Token"), "a bearer call sends no CSRF token");
        assertEquals("mine-1", cancels.get(1).header("Idempotency-Key"));
        Request create = bff.requests("POST", "/console/v1/r/vm").get(0);
        assertEquals("mine-2", create.header("Idempotency-Key"));
        assertEquals("web-9", json.readTree(create.body()).path("input").path("name").asText());
    }

    @Test
    void a429Or503OfAReadIsRetriedAfterRetryAfterSecondsOrDate() {
        bff.on("GET", "/console/v1/jobs", StubBff.sequence(
                Reply.json(429, "{\"error\":\"quota.exceeded\"}").with("Retry-After", "2"),
                Reply.json(503, "{\"error\":\"unavailable\"}").with("Retry-After", "Fri, 23 Oct 2026 12:00:03 GMT"),
                Reply.json(200, "{\"items\":[],\"next\":null}")));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build()) {
            ListJobs200Response page = console.call(() -> console.api(JobsApi.class).listJobs(null, null, null, null, null, null));
            assertTrue(page.getItems().isEmpty());
        }
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(3)), sleeps);
        assertEquals(3, bff.requests("GET", "/console/v1/jobs").size());
    }

    @Test
    void itGivesUpAfterThreeRetries() {
        bff.on("GET", "/console/v1/jobs", r -> Reply.json(429, "{\"error\":\"quota.exceeded\",\"message\":\"slow down\"}").with("Retry-After", "1"));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build()) {
            ConsoleApiException e = assertThrows(ConsoleApiException.class,
                    () -> console.call(() -> console.api(JobsApi.class).listJobs(null, null, null, null, null, null)));
            assertEquals(429, e.status());
            assertEquals(Duration.ofSeconds(1), e.retryAfter());
            assertEquals("GET /console/v1/jobs answered 429 quota.exceeded: slow down", e.getMessage());
        }
        assertEquals(4, bff.requests("GET", "/console/v1/jobs").size());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)), sleeps);
    }

    @Test
    void aWriteThatTakesNoKeyIsNotSentAgain() {
        bff.on("POST", "/console/v1/jobs/job-1:cancel", r -> Reply.json(503, "{\"error\":\"unavailable\"}").with("Retry-After", "1"));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build()) {
            JobsApi jobs = console.api(JobsApi.class);
            assertEquals(503, assertThrows(ConsoleApiException.class, () -> console.run(() -> jobs.cancelJob(NO_CSRF, "job-1"))).status());
        }
        assertEquals(1, bff.requests("POST", "/console/v1/jobs/job-1:cancel").size());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void aWriteThatTakesAKeyIsSentAgainWithItBackingOffWithoutRetryAfter() {
        // below a path prefix: the operation is matched under the endpoint's path
        bff.on("POST", "/prefix/console/v1/r/vm", StubBff.sequence(
                Reply.json(503, "{\"error\":\"unavailable\"}"),
                Reply.json(503, "{\"error\":\"unavailable\"}"),
                Reply.json(429, "{\"error\":\"quota.exceeded\"}"),
                Reply.json(202, ACCEPTED)));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).endpoint(bff.url() + "/prefix/").build()) {
            CommandAccepted accepted = console.call(() -> console.api(CommandsApi.class)
                    .createResource(NO_CSRF, "vm", new CommandInput().input(Map.of("name", "web-9")), null));
            assertEquals("job-7", accepted.getJobId());
        }
        List<Request> attempts = bff.requests("POST", "/prefix/console/v1/r/vm");
        assertEquals(4, attempts.size());
        assertEquals(1, attempts.stream().map(r -> r.header("Idempotency-Key")).distinct().count(), "one key for every attempt");
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)), sleeps);
    }

    @Test
    void aWaitLongerThanTheMostHonouredOrRetriesOffIsNotRetried() {
        bff.on("GET", "/console/v1/jobs/job-1", r -> Reply.json(503, "{\"error\":\"unavailable\"}").with("Retry-After", "120"));
        bff.on("GET", "/console/v1/jobs/job-2", r -> Reply.json(503, "{\"error\":\"unavailable\"}").with("Retry-After", "1"));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build();
                ConsoleClient once = client(TokenProvider.of("at-1")).retries(0).build()) {
            ConsoleApiException e = assertThrows(ConsoleApiException.class, () -> console.call(() -> console.api(JobsApi.class).getJob("job-1")));
            assertEquals(Duration.ofSeconds(120), e.retryAfter());
            assertThrows(ConsoleApiException.class, () -> once.call(() -> once.api(JobsApi.class).getJob("job-2")));
        }
        assertEquals(1, bff.requests("GET", "/console/v1/jobs/job-1").size());
        assertEquals(1, bff.requests("GET", "/console/v1/jobs/job-2").size());
        assertTrue(sleeps.isEmpty());
    }

    // --- errors ----------------------------------------------------------------------------------

    @Test
    void aRefusalCarriesTheDenial() {
        String path = "/console/v1/r/vm/" + Http.encode(WFRN) + ":stop";
        bff.on("POST", path, r -> Reply.json(403, "{\"error\":\"iam.denied\",\"denied\":{\"layer\":\"console-iam\",\"action\":\"vm:stop\","
                + "\"resource\":\"" + WFRN + "\",\"reason\":\"Your roles in Payments do not allow vm:stop.\",\"role\":\"viewer\","
                + "\"policyId\":\"pol-viewer\",\"statement\":\"read-only\",\"requestable\":true,\"suggestedRole\":\"operator\"}}"));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build()) {
            CommandsApi commands = console.api(CommandsApi.class);
            ConsoleApiException e = assertThrows(ConsoleApiException.class,
                    () -> console.call(() -> commands.runVerb(NO_CSRF, "vm", WFRN, "stop", new CommandInput(), null)));
            assertEquals(403, e.status());
            assertEquals("iam.denied", e.error());
            assertEquals("runVerb", e.operationId());
            assertEquals(path, e.uri().getRawPath());
            assertEquals("POST " + path + " answered 403 iam.denied: Your roles in Payments do not allow vm:stop.", e.getMessage());
            Denial denied = e.denied();
            assertEquals(Denial.LayerEnum.CONSOLE_IAM, denied.getLayer());
            assertEquals("vm:stop", denied.getAction());
            assertEquals("viewer", denied.getRole());
            assertEquals("pol-viewer", denied.getPolicyId());
            assertEquals("read-only", denied.getStatement());
            assertEquals("operator", denied.getSuggestedRole());
            assertTrue(e.isRequestable());
            assertFalse(e.isStepUp());
        }
    }

    @Test
    void problemsStepUpAndBodiesThatAreNotJson() {
        bff.on("POST", "/console/v1/r/vm", r -> Reply.json(400, "{\"error\":\"validation.failed\",\"problems\":["
                + "{\"code\":\"required\",\"field\":\"/input/name\",\"message\":\"is required\"},{\"message\":\"the region is closed\"}]}"));
        bff.on("POST", "/console/v1/jobs/job-1:cancel", r -> Reply.json(403,
                "{\"error\":\"step-up.required\",\"detail\":{\"stepUp\":true,\"action\":\"vm:delete\",\"login\":\"/auth/login?max_age=0\"}}"));
        bff.on("GET", "/console/v1/jobs/job-1", r -> Reply.text(502, "text/plain", "upstream unavailable"));
        bff.on("GET", "/console/v1/jobs/job-2", r -> Reply.json(403, "{\"error\":\"iam.denied\",\"denied\":{\"layer\":\"a-layer-to-come\",\"reason\":\"no\"},\"correlationId\":\"c-1\"}"));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build()) {
            CommandsApi commands = console.api(CommandsApi.class);
            JobsApi jobs = console.api(JobsApi.class);
            ConsoleApiException bad = assertThrows(ConsoleApiException.class,
                    () -> console.call(() -> commands.createResource(NO_CSRF, "vm", new CommandInput(), null)));
            assertEquals(2, bad.problems().size());
            assertEquals("/input/name", bad.problems().get(0).getField());
            assertEquals("POST /console/v1/r/vm answered 400 validation.failed: /input/name: is required; the region is closed", bad.getMessage());

            ConsoleApiException stepUp = assertThrows(ConsoleApiException.class, () -> console.run(() -> jobs.cancelJob(NO_CSRF, "job-1")));
            assertTrue(stepUp.isStepUp());
            assertEquals("vm:delete", stepUp.detail().getAction());

            ConsoleApiException gateway = assertThrows(ConsoleApiException.class, () -> console.call(() -> jobs.getJob("job-1")));
            assertEquals("http.502", gateway.error());
            assertEquals("GET /console/v1/jobs/job-1 answered 502 http.502: upstream unavailable", gateway.getMessage());

            // a value this SDK does not know yet costs that field only
            ConsoleApiException unknown = assertThrows(ConsoleApiException.class, () -> console.call(() -> jobs.getJob("job-2")));
            assertEquals("iam.denied", unknown.error());
            assertNull(unknown.denied());
            assertEquals("c-1", unknown.correlationId());
        }
    }

    @Test
    void whatTheGeneratedClassesThrowReadsTheSameWithoutCall() {
        bff.on("GET", "/console/v1/jobs/job-9", r -> Reply.json(404, "{\"error\":\"job.not-found\",\"message\":\"no job job-9\"}"));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build()) {
            JobsApi jobs = console.api(JobsApi.class);
            ApiException raw = assertThrows(ApiException.class, () -> jobs.getJob("job-9"));
            assertEquals(404, raw.getCode());
            ConsoleApiException e = ConsoleApiException.from(raw);
            assertEquals("job.not-found", e.error());
            assertEquals("getJob", e.operationId());
            assertEquals("getJob answered 404 job.not-found: no job job-9", e.getMessage());
        }
    }

    @Test
    void aMissingParameterOrAConnectionFailureIsNotARefusal() throws IOException {
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build();
                ConsoleClient nowhere = client(TokenProvider.of("at-1")).endpoint(StubBff.closedEndpoint()).build()) {
            assertThrows(IllegalArgumentException.class, () -> console.call(() -> console.api(JobsApi.class).getJob(null)));
            assertThrows(UncheckedIOException.class, () -> nowhere.call(() -> nowhere.api(JobsApi.class).getJob("job-1")));
        }
        assertTrue(bff.requests().isEmpty());
    }

    // --- paging ----------------------------------------------------------------------------------

    private static Reply page(List<String> ids, String next) {
        String items = ids.stream().map(id -> "{\"jobId\":\"" + id + "\"}").collect(Collectors.joining(","));
        return Reply.json(200, "{\"items\":[" + items + "],\"next\":" + (next == null ? "null" : "\"" + next + "\"") + "}");
    }

    @Test
    void itemsFollowNextUntilItIsNull() {
        bff.on("GET", "/console/v1/jobs", r -> {
            String after = r.params().get("after");
            if (after == null) return page(List.of("job-1", "job-2"), "c1");
            if (after.equals("c1")) return page(List.of("job-3"), "c2");
            return page(List.of("job-4"), null);
        });
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build()) {
            JobsApi jobs = console.api(JobsApi.class);
            List<String> ids = console.items(after -> jobs.listJobs(null, "failed", null, null, 2, after), ListJobs200Response::getItems)
                    .map(JobSummary::getJobId)
                    .toList();
            assertEquals(List.of("job-1", "job-2", "job-3", "job-4"), ids);
            List<Request> calls = bff.requests("GET", "/console/v1/jobs");
            assertEquals(Arrays.asList(null, "c1", "c2"), calls.stream().map(r -> r.params().get("after")).toList());
            assertEquals("failed", calls.get(0).params().get("status"));
            assertEquals("2", calls.get(0).params().get("limit"));

            // lazily: the first item takes the first page only
            bff.reset();
            bff.on("GET", "/console/v1/jobs", r -> page(List.of("job-1"), "c1"));
            assertEquals("job-1", console.items(after -> jobs.listJobs(null, null, null, null, null, after), ListJobs200Response::getItems)
                    .findFirst().orElseThrow().getJobId());
            assertEquals(1, bff.requests().size());
        }
    }

    @Test
    void aCursorAnsweredTwiceStopsThePaging() {
        bff.on("GET", "/console/v1/jobs", r -> page(List.of("job-1"), "c1"));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build()) {
            JobsApi jobs = console.api(JobsApi.class);
            Iterator<ListJobs200Response> pages = console.pages(after -> jobs.listJobs(null, null, null, null, null, after)).iterator();
            pages.next();
            pages.next();
            IllegalStateException e = assertThrows(IllegalStateException.class, pages::hasNext);
            assertEquals("the list answered the cursor c1 twice", e.getMessage());
        }
        assertEquals(2, bff.requests().size());
    }

    // --- requests by path, events ----------------------------------------------------------------

    @Test
    void aRequestByPathReadsWhatTheGeneratedClassesCannot() throws Exception {
        bff.on("GET", "/console/v1/cost/chargeback.csv", r -> Reply.text(200, "text/csv", "month,costCentre,amount\n2026-09,cc-1,12.50\n"));
        bff.on("POST", "/console/v1/budgets", r -> Reply.json(409, "{\"error\":\"budget.exists\",\"message\":\"a budget named ops exists\"}"));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build()) {
            ConsoleResponse csv = console.request("GET", "/console/v1/cost/chargeback.csv", Map.of("month", "2026-09"), null, Map.of("Accept", "text/csv"));
            assertEquals(200, csv.status());
            assertEquals("month,costCentre,amount\n2026-09,cc-1,12.50\n", csv.body());

            ConsoleApiException e = assertThrows(ConsoleApiException.class,
                    () -> console.request("POST", "/console/v1/budgets", null, Map.of("name", "ops")));
            assertEquals("createBudget", e.operationId());
            assertEquals("POST /console/v1/budgets answered 409 budget.exists: a budget named ops exists", e.getMessage());
        }
        Request get = bff.requests("GET", "/console/v1/cost/chargeback.csv").get(0);
        assertEquals("text/csv", get.header("Accept"));
        assertEquals("2026-09", get.params().get("month"));
        assertEquals("Bearer at-1", get.header("Authorization"));
        Request post = bff.requests("POST", "/console/v1/budgets").get(0);
        JsonNode body = json.readTree(post.body());
        assertEquals("ops", body.path("name").asText());
        assertEquals("application/json", post.header("Content-Type"));
        assertTrue(UUID_V4.matcher(post.header("Idempotency-Key")).matches());
    }

    @Test
    void eventsStreamAsTheyCome() {
        bff.on("GET", "/console/v1/events", r -> Reply.text(200, "text/event-stream",
                ": connected\n\nevent: job\nid: e-1\ndata: {\"jobId\":\"job-1\"}\n\nevent: resource\nid: e-2\ndata: {\"wfrn\":\"" + WFRN + "\"}\n\n"));
        try (ConsoleClient console = client(TokenProvider.of("at-1")).build()) {
            List<ServerSentEvent> events;
            try (Stream<ServerSentEvent> stream = console.events(Map.of("types", "job,resource"), Map.of("Last-Event-ID", "e-0"))) {
                events = stream.toList();
            }
            assertEquals(List.of("job", "resource"), events.stream().map(ServerSentEvent::event).toList());
            assertEquals("e-2", events.get(1).id());
            assertEquals("{\"jobId\":\"job-1\"}", events.get(0).data());

            bff.on("GET", "/console/v1/events", r -> Reply.json(403, "{\"error\":\"iam.denied\",\"denied\":{\"layer\":\"console-iam\",\"reason\":\"no events for you\"}}"));
            ConsoleApiException e = assertThrows(ConsoleApiException.class, () -> console.events(Map.of()));
            assertEquals("streamEvents", e.operationId());
            assertEquals("no events for you", e.denied().getReason());
        }
        Request r = bff.requests("GET", "/console/v1/events").get(0);
        assertEquals("text/event-stream", r.header("Accept"));
        assertEquals("e-0", r.header("Last-Event-ID"));
        assertEquals("job,resource", r.params().get("types"));
        assertEquals("Bearer at-1", r.header("Authorization"));
    }

    @Test
    void theClientIsBuiltFromAnEndpointAndHandsOutTheGeneratedClasses() {
        try (ConsoleClient console = ConsoleClient.create(bff.url() + "/", TokenProvider.of("at-1"))) {
            assertEquals(bff.url(), console.endpoint());
            assertSame(console.api(JobsApi.class), console.api(JobsApi.class));
            assertThrows(IllegalArgumentException.class, () -> console.api(String.class));
        }
        assertThrows(IllegalArgumentException.class, () -> ConsoleClient.builder().endpoint("console.example.com").build());
        assertThrows(NullPointerException.class, () -> ConsoleClient.builder().build());
    }
}
