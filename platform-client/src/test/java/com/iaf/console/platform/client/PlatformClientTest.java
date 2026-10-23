package com.iaf.console.platform.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.platform.model.FeedEvent;
import com.iaf.console.platform.model.Identity;
import com.iaf.console.platform.model.JobAccepted;
import com.iaf.console.platform.model.Resource;
import com.iaf.console.platform.model.ResourceType;
import com.iaf.console.platform.model.StartJobRequest;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The client's request shapes and answer handling, against a scripted exchange function. */
class PlatformClientTest {

    /** The last request the client sent, with its body written out. */
    record Sent(ClientRequest request, String body) {

        String header(String name) {
            return request.headers().getFirst(name);
        }
    }

    private final AtomicReference<Sent> sent = new AtomicReference<>();

    private PlatformClient client(Function<ClientRequest, ClientResponse> answer) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            MockClientHttpRequest mock = new MockClientHttpRequest(request.method(), request.url());
            return request.writeTo(mock, ExchangeStrategies.withDefaults())
                    .then(Mono.defer(() -> mock.getBodyAsString().defaultIfEmpty("")))
                    .map(body -> {
                        sent.set(new Sent(request, body));
                        return answer.apply(request);
                    });
        });
        return PlatformClient.builder().baseUrl("http://platform").bearerToken("iaf_test_token_value").webClientBuilder(builder).build();
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build();
    }

    @Test
    void sendsTheBearerAndParsesTheIdentity() {
        PlatformClient client = client(r -> json(HttpStatus.OK,
                "{\"id\":\"console-123456789012\",\"teams\":[\"team-compute\"],\"roles\":[],\"admin\":false,\"kind\":\"service\",\"extra\":1}"));
        Identity me = client.me().block(Duration.ofSeconds(5));
        assertThat(me.id()).isEqualTo("console-123456789012");
        assertThat(me.teams()).containsExactly("team-compute");
        assertThat(sent.get().header("Authorization")).isEqualTo("Bearer iaf_test_token_value");
        assertThat(sent.get().request().url().toString()).isEqualTo("http://platform/api/v1/me");
    }

    @Test
    void listsWithFiltersAndFollowsTheLinkHeader() {
        PlatformClient client = client(r -> ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Link", "<http://platform/api/v1/resources?type=vm&limit=2&after=abc%3D>; rel=\"next\"")
                .body("[{\"id\":\"vm-1\",\"type\":\"vm\",\"status\":\"ready\",\"attributes\":{\"cpu\":2}},{\"id\":\"vm-2\",\"type\":\"vm\",\"status\":\"ready\",\"attributes\":{}}]")
                .build());
        PagedList<Resource> page = client.listResources(ResourceFilter.ofType("vm").withLimit(2).withAccount("123456789012")).block(Duration.ofSeconds(5));
        assertThat(page.items()).extracting(Resource::id).containsExactly("vm-1", "vm-2");
        assertThat(page.next()).isEqualTo("abc=");
        assertThat(page.items().get(0).attribute("cpu")).isEqualTo("2");
        assertThat(sent.get().request().url().toString()).isEqualTo("http://platform/api/v1/resources?type=vm&account=123456789012&limit=2");
    }

    @Test
    void startsAJobWithEveryContextHeader() {
        PlatformClient client = client(r -> ClientResponse.create(HttpStatus.ACCEPTED)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Idempotent-Replay", "true")
                .body("{\"id\":\"job-1\",\"status\":\"queued\",\"links\":{\"job\":\"/api/v1/jobs/job-1\"}}")
                .build());
        RequestOptions options = RequestOptions.console("jws-value", "req-9", "key-9")
                .withResourceWfrn("wfrn:wf:compute:global:123456789012:vm/01abc")
                .withLabel("tab", "console-1")
                .withTraceparent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        var accepted = client.startJob(StartJobRequest.of("create-vm", Map.of("name", "web-07", "image", "img-1", "size", "small")), options)
                .block(Duration.ofSeconds(5));
        assertThat(accepted.body().id()).isEqualTo("job-1");
        assertThat(accepted.idempotentReplay()).isTrue();
        Sent s = sent.get();
        assertThat(s.request().method().name()).isEqualTo("POST");
        assertThat(s.header(PlatformHeaders.ON_BEHALF_OF)).isEqualTo("jws-value");
        assertThat(s.header(PlatformHeaders.RESOURCE_WFRN)).isEqualTo("wfrn:wf:compute:global:123456789012:vm/01abc");
        assertThat(s.header(PlatformHeaders.CONSOLE_REQUEST_ID)).isEqualTo("req-9");
        assertThat(s.header(PlatformHeaders.IDEMPOTENCY_KEY)).isEqualTo("key-9");
        assertThat(s.header("X-Job-Label-tab")).isEqualTo("console-1");
        assertThat(s.header(PlatformHeaders.TRACEPARENT)).startsWith("00-");
        assertThat(s.body()).contains("\"workflow\":\"create-vm\"").contains("\"name\":\"web-07\"").doesNotContain("\"version\"");
    }

    @Test
    void generatedRoutesUseTheirMethodAndPath() {
        PlatformClient client = client(r -> json(HttpStatus.ACCEPTED, "{\"jobId\":\"job-2\",\"status\":\"queued\",\"links\":{\"job\":\"/api/v1/jobs/job-2\"}}"));
        Accepted<JobAccepted> deleted = client.start(PlatformOperation.DELETE_VM, Map.of("vmId", "vm-10/42"), Map.of("reason", "decommissioned after the migration"),
                RequestOptions.none().withIdempotencyKey("del-1")).block(Duration.ofSeconds(5));
        assertThat(deleted.body().jobId()).isEqualTo("job-2");
        assertThat(deleted.idempotentReplay()).isFalse();
        assertThat(sent.get().request().method().name()).isEqualTo("DELETE");
        assertThat(sent.get().request().url().toString()).isEqualTo("http://platform/api/v1/vms/vm-10%2F42");
        assertThat(sent.get().body()).contains("decommissioned");

        client.start(PlatformOperation.UPDATE_VM, Map.of("vmId", "vm-1"), Map.of("cpu", 4), RequestOptions.none()).block(Duration.ofSeconds(5));
        assertThat(sent.get().request().method().name()).isEqualTo("PATCH");
        assertThat(sent.get().request().url().getPath()).isEqualTo("/api/v1/vms/vm-1");

        assertThatThrownBy(() -> client.start(PlatformOperation.LIST_VMS, Map.of(), Map.of(), RequestOptions.none()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wfrnsTravelAsAQueryParameter() {
        PlatformClient client = client(r -> json(HttpStatus.OK, "{\"id\":\"r-1\",\"type\":\"vm\",\"status\":\"ready\",\"attributes\":{}}"));
        client.getResourceByWfrn("wfrn:wf:compute:global:123456789012:vm/01abc").block(Duration.ofSeconds(5));
        assertThat(sent.get().request().url().getPath()).isEqualTo("/api/v1/resources/by-wfrn");
        assertThat(sent.get().request().url().getQuery()).isEqualTo("wfrn=wfrn:wf:compute:global:123456789012:vm/01abc");
    }

    @Test
    void mapsErrorsToPlatformExceptions() {
        PlatformClient notFound = client(r -> json(HttpStatus.NOT_FOUND, "{\"error\":\"job 'j-1' not found\",\"correlationId\":\"c-1\"}"));
        assertThatThrownBy(() -> notFound.getJob("j-1").block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.isNotFound()).isTrue();
                    assertThat(e.errorText()).isEqualTo("job 'j-1' not found");
                    assertThat(e.correlationId()).isEqualTo("c-1");
                    assertThat(e.operation()).isEqualTo(PlatformOperation.GET_JOB);
                });

        PlatformClient tooMany = client(r -> ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).header("Retry-After", "7")
                .body("{\"error\":\"quota exceeded\",\"retryAfter\":7}").build());
        assertThatThrownBy(() -> tooMany.observeResource("r-1").block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.isTooManyRequests()).isTrue();
                    assertThat(e.retryAfterSeconds()).isEqualTo(7);
                    assertThat(e.retryable()).isTrue();
                });

        PlatformClient bad = client(r -> json(HttpStatus.BAD_REQUEST,
                "{\"error\":\"input does not match the schema\",\"problems\":[{\"code\":\"schema\",\"field\":\"$.name\",\"level\":\"error\",\"message\":\"required\"}]}"));
        assertThatThrownBy(() -> bad.startJob(StartJobRequest.of("create-vm", Map.of()), RequestOptions.none()).block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.isBadRequest()).isTrue();
                    assertThat(e.problems()).hasSize(1);
                    assertThat(e.problems().get(0).field()).isEqualTo("$.name");
                });

        PlatformClient mismatch = client(r -> json(HttpStatus.UNPROCESSABLE_ENTITY, "{\"error\":\"idempotency.mismatch\"}"));
        assertThatThrownBy(() -> mismatch.cancelJob("j-1", RequestOptions.none()).block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(PlatformException.class, e -> assertThat(e.isIdempotencyMismatch()).isTrue());

        PlatformClient text = client(r -> ClientResponse.create(HttpStatus.BAD_GATEWAY).header("Content-Type", "text/plain").body("bad gateway").build());
        assertThatThrownBy(() -> text.me().block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.status()).isEqualTo(502);
                    assertThat(e.errorText()).isEqualTo("bad gateway");
                });
    }

    @Test
    void conditionalReadsCarryTheEtag() {
        PlatformClient fresh = client(r -> ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).header("ETag", "\"abc\"")
                .body("[{\"name\":\"vm\",\"plural\":\"vms\",\"schema\":{\"type\":\"object\",\"properties\":{\"cpu\":{\"type\":\"integer\",\"x-console\":{\"mutable\":true}}}},\"states\":[\"ready\"],\"service\":\"compute\"}]")
                .build());
        Conditional<List<ResourceType>> first = fresh.listResourceTypes(null).block(Duration.ofSeconds(5));
        assertThat(first.notModified()).isFalse();
        assertThat(first.etag()).isEqualTo("\"abc\"");
        assertThat(first.body()).hasSize(1);
        assertThat(first.body().get(0).consoleHints("cpu").get("mutable").asBoolean()).isTrue();
        assertThat(first.body().get(0).fields()).containsExactly("cpu");
        assertThat(sent.get().header(PlatformHeaders.IF_NONE_MATCH)).isNull();

        PlatformClient same = client(r -> ClientResponse.create(HttpStatus.NOT_MODIFIED).header("ETag", "\"abc\"").build());
        Conditional<ResourceType> second = same.getResourceType("vm", "\"abc\"").block(Duration.ofSeconds(5));
        assertThat(second.notModified()).isTrue();
        assertThat(second.body()).isNull();
        assertThat(second.etag()).isEqualTo("\"abc\"");
        assertThat(sent.get().header(PlatformHeaders.IF_NONE_MATCH)).isEqualTo("\"abc\"");
    }

    @Test
    void readsServerSentEventsAndResumes() {
        String stream = "id: 5\nevent: job.started\ndata: {\"specVersion\":\"1.0\",\"dataVersion\":1,\"id\":\"e-5\",\"key\":\"job.started:j-1:1\",\"seq\":5,"
                + "\"type\":\"job.started\",\"at\":\"2026-10-23T10:00:00Z\",\"aggregate\":\"job\",\"aggregateId\":\"j-1\",\"actor\":\"gonzalo\",\"data\":{}}\n\n"
                + ": keep-alive\n\n"
                + "id: 6\nevent: job.finished\ndata: {\"specVersion\":\"1.0\",\"dataVersion\":1,\"id\":\"e-6\",\"key\":\"job.finished:j-1:9\",\"seq\":6,"
                + "\"type\":\"job.finished\",\"at\":\"2026-10-23T10:00:01Z\",\"aggregate\":\"job\",\"aggregateId\":\"j-1\",\"actor\":\"gonzalo\",\"data\":{\"status\":\"succeeded\"}}\n\n";
        PlatformClient client = client(r -> ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE).body(stream).build());
        StepVerifier.create(client.streamEvents(4L, 4L, List.of("job.")))
                .assertNext(e -> {
                    assertThat(e.seq()).isEqualTo(5);
                    assertThat(e.type()).isEqualTo("job.started");
                })
                .assertNext(e -> assertThat(e.data().get("status").asText()).isEqualTo("succeeded"))
                .verifyComplete();
        assertThat(sent.get().header(PlatformHeaders.LAST_EVENT_ID)).isEqualTo("4");
        assertThat(sent.get().request().url().getQuery()).isEqualTo("after=4&types=job.");
        assertThat(sent.get().header("Accept")).isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);

        List<FeedEvent> two = client.followEvents(4, List.of()).take(2).collectList().block(Duration.ofSeconds(5));
        assertThat(two).extracting(FeedEvent::seq).containsExactly(5L, 6L);
    }

    @Test
    void deletesAnswerNoBody() {
        PlatformClient client = client(r -> ClientResponse.create(HttpStatus.NO_CONTENT).build());
        client.deleteServiceAccount("sa-1", RequestOptions.none().withIdempotencyKey("rm-1")).block(Duration.ofSeconds(5));
        assertThat(sent.get().request().method().name()).isEqualTo("DELETE");
        assertThat(sent.get().request().url().getPath()).isEqualTo("/api/v1/service-accounts/sa-1");
        assertThat(sent.get().header(PlatformHeaders.IDEMPOTENCY_KEY)).isEqualTo("rm-1");
    }
}
