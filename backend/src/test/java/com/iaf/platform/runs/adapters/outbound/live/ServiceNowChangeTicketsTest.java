package com.iaf.platform.runs.adapters.outbound.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.runs.adapters.outbound.ChangeProperties;
import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.ports.outbound.ChangeTicketPort.OpenChange;
import com.iaf.platform.testing.LocalHttp;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ServiceNowChangeTicketsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String SECRET = "svc-iaf:s3cret-pw";

    record Call(String method, String path, String query, String authorization, JsonNode body) {
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    final CredentialsPort credentials = mock(CredentialsPort.class);
    HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A ServiceNow whose answer to each call is {@code answer(call)} as {status, body}. */
    ServiceNowChangeTickets serviceNow(Function<Call, Object[]> answer, ChangeProperties props) throws IOException {
        server = LocalHttp.start(exchange -> {
            String body = LocalHttp.body(exchange);
            Call call = new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body.isBlank() ? null : JSON.readTree(body));
            calls.add(call);
            Object[] a = answer.apply(call);
            LocalHttp.respond(exchange, (Integer) a[0], (String) a[1]);
        });
        props.getServicenow().setInstance(LocalHttp.url(server) + "/");
        when(credentials.resolve("vault:servicenow/api")).thenReturn(Mono.just(SECRET));
        return new ServiceNowChangeTickets(WebClient.builder(), props, credentials);
    }

    static ChangeProperties props() {
        ChangeProperties p = new ChangeProperties();
        p.setEvidenceUrl("https://iaf.example/audit/{jobId}");
        p.getServicenow().setCredential("vault:servicenow/api");
        p.getServicenow().setAssignmentGroup("Platform Engineering");
        p.getServicenow().setTimeout(Duration.ofSeconds(5));
        return p;
    }

    static Object[] created(Call c) {
        return new Object[] {201, "{\"result\":{\"sys_id\":\"a1b2c3\",\"number\":\"CHG0030001\",\"state\":\"-5\",\"correlation_id\":\"job_1\"}}"};
    }

    static Object[] ok(Call c) {
        return new Object[] {200, "{\"result\":{\"sys_id\":\"a1b2c3\",\"number\":\"CHG0030001\",\"state\":\"3\"}}"};
    }

    static OpenChange change() {
        Preview preview = new Preview("vm-create", "1.2.0",
                List.of(new Preview.PreviewStep("create", "Create the VM", Effect.CREATE, "web-01 in dev")),
                List.of(new Preview.AffectedResource("vm-1", "vm", "web-01", "create")),
                new Preview.Cost(45, "USD", "2 vCPU"), null);
        return new OpenChange("job_1", "vm-create", "1.2.0", "ana", "Create VM web-01 requested by ana", preview);
    }

    @Test
    void opensAChangeWithTheJobAsCorrelationAndTheEvidenceLinkAsAWorkNote() throws IOException {
        ServiceNowChangeTickets sn = serviceNow(ServiceNowChangeTicketsTest::created, props());

        ChangeTicket ticket = sn.open(change()).block();

        assertThat(ticket.id()).isEqualTo("CHG0030001");
        assertThat(ticket.url()).isEqualTo(LocalHttp.url(server) + "/change_request.do?sys_id=a1b2c3");
        assertThat(ticket.state()).isEqualTo("new");
        assertThat(ticket.evidenceUrl()).isEqualTo("https://iaf.example/audit/job_1");
        Call call = calls.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/api/now/table/change_request");
        assertThat(call.query()).isEqualTo("sysparm_fields=sys_id,number,state,correlation_id");
        assertThat(call.authorization()).isEqualTo("Basic " + Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.UTF_8)));
        JsonNode body = call.body();
        assertThat(body.get("short_description").asText()).isEqualTo("Create VM web-01 requested by ana");
        assertThat(body.get("correlation_id").asText()).isEqualTo("job_1");
        assertThat(body.get("correlation_display").asText()).isEqualTo("iaf:vm-create@1.2.0");
        assertThat(body.get("type").asText()).isEqualTo("normal");
        assertThat(body.get("assignment_group").asText()).isEqualTo("Platform Engineering");
        assertThat(body.get("work_notes").asText()).isEqualTo("Evidence of job job_1: https://iaf.example/audit/job_1");
        assertThat(body.get("description").asText())
                .contains("workflow vm-create 1.2.0, requested by ana")
                .contains("- Create the VM (create): web-01 in dev")
                .contains("- vm web-01")
                .contains("45.00 USD per month")
                .contains("Evidence: https://iaf.example/audit/job_1");
        assertThat(body.has("state")).isFalse();
    }

    @Test
    void closesWithTheCloseCodeAndRepeatsTheEvidenceLink() throws IOException {
        ServiceNowChangeTickets sn = serviceNow(c -> c.method().equals("POST") ? created(c) : ok(c), props());
        sn.open(change()).block();

        ChangeTicket closed = sn.close("CHG0030001", true).block();

        assertThat(closed.state()).isEqualTo("closed");
        assertThat(closed.evidenceUrl()).isEqualTo("https://iaf.example/audit/job_1");
        Call call = calls.get(1);
        assertThat(call.method()).isEqualTo("PATCH");
        assertThat(call.path()).isEqualTo("/api/now/table/change_request/a1b2c3");
        assertThat(call.body().get("state").asText()).isEqualTo("3");
        assertThat(call.body().get("close_code").asText()).isEqualTo("successful");
        assertThat(call.body().get("close_notes").asText()).isEqualTo("Job job_1 succeeded. Evidence: https://iaf.example/audit/job_1");
    }

    @Test
    void closesAChangeOpenedBeforeARestartByFindingItsNumber() throws IOException {
        ServiceNowChangeTickets sn = serviceNow(c -> c.method().equals("GET")
                ? new Object[] {200, "{\"result\":[{\"sys_id\":\"z9\",\"number\":\"CHG0030007\",\"correlation_id\":\"job_9\"}]}"}
                : ok(c), props());

        ChangeTicket closed = sn.close("CHG0030007", false).block();

        assertThat(calls.get(0).method()).isEqualTo("GET");
        assertThat(calls.get(0).query()).contains("sysparm_query=number%3DCHG0030007").contains("sysparm_limit=1");
        assertThat(calls.get(1).path()).isEqualTo("/api/now/table/change_request/z9");
        assertThat(calls.get(1).body().get("close_code").asText()).isEqualTo("unsuccessful");
        assertThat(calls.get(1).body().get("close_notes").asText()).isEqualTo("Job job_9 did not succeed. Evidence: https://iaf.example/audit/job_9");
        assertThat(closed.state()).isEqualTo("closed-unsuccessful");
        assertThat(closed.evidenceUrl()).isEqualTo("https://iaf.example/audit/job_9");
    }

    @Test
    void anUnknownNumberIsNotFound() throws IOException {
        ServiceNowChangeTickets sn = serviceNow(c -> new Object[] {200, "{\"result\":[]}"}, props());

        StepVerifier.create(sn.close("CHG0099999", true)).expectError(NotFoundException.class).verify(Duration.ofSeconds(5));
    }

    @Test
    void aRefusalNamesTheStatusAndServiceNowsMessageButNeverTheCredential() throws IOException {
        ServiceNowChangeTickets sn = serviceNow(c -> new Object[] {401,
                "{\"error\":{\"message\":\"User Not Authenticated\",\"detail\":\"Required to provide Auth information\"},\"status\":\"failure\"}"}, props());

        StepVerifier.create(sn.open(change()))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("401").hasMessageContaining("User Not Authenticated")
                        .hasMessageNotContaining("s3cret").hasMessageNotContaining(Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.UTF_8))))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void bearerAuthAndACustomEvidenceFieldAreConfigurable() throws IOException {
        ChangeProperties p = props();
        p.getServicenow().setAuth("bearer");
        p.getServicenow().setEvidenceField("u_evidence_url");
        p.getServicenow().setOpenState("-1");
        p.getServicenow().setType("standard");
        ServiceNowChangeTickets sn = serviceNow(ServiceNowChangeTicketsTest::created, p);

        sn.open(change()).block();

        Call call = calls.get(0);
        assertThat(call.authorization()).isEqualTo("Bearer " + SECRET);
        assertThat(call.body().get("u_evidence_url").asText()).isEqualTo("https://iaf.example/audit/job_1");
        assertThat(call.body().has("work_notes")).isFalse();
        assertThat(call.body().get("state").asText()).isEqualTo("-1");
        assertThat(call.body().get("type").asText()).isEqualTo("standard");
    }

    @Test
    void withoutAnInstanceChangeManagedJobsAreRefused() {
        ChangeProperties p = new ChangeProperties();
        ServiceNowChangeTickets sn = new ServiceNowChangeTickets(WebClient.builder(), p, credentials);

        StepVerifier.create(sn.open(change()))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalStateException.class).hasMessageContaining("not configured"))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void theCredentialMustBeAVaultReferenceAndNumbersMustBeNumbers() throws IOException {
        ChangeProperties plain = props();
        plain.getServicenow().setCredential("admin:password");

        assertThatThrownBy(() -> new ServiceNowChangeTickets(WebClient.builder(), plain, credentials))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("vault:").hasMessageNotContaining("password");
        ServiceNowChangeTickets sn = serviceNow(ServiceNowChangeTicketsTest::ok, props());
        StepVerifier.create(sn.close("CHG1^ORactive=true", true)).expectError(IllegalArgumentException.class).verify(Duration.ofSeconds(5));
        assertThat(calls).isEmpty();
    }
}
