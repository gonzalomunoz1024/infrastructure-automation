package com.iaf.platform.runs.adapters.outbound.live;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.runs.adapters.outbound.ChangeProperties;
import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.ports.outbound.ChangeTicketPort;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Production change records on the firm's ServiceNow (profiles {@code servicenow} and {@code live}),
 * through the Table API on {@code change_request}:
 *
 * <ul>
 * <li>open: {@code POST /api/now/table/change_request} with the job as {@code correlation_id}, the
 * summary as {@code short_description}, the workflow version, requester and preview as
 * {@code description}, and the link to the job's evidence in {@code evidence-field} (a work note by
 * default);</li>
 * <li>close: {@code PATCH /api/now/table/change_request/{sys_id}} with {@code close-state},
 * {@code close_code} successful or unsuccessful and close notes that repeat the evidence link. The
 * {@code sys_id} of a change opened before a restart is looked up by its number.</li>
 * </ul>
 *
 * The credential is a {@code vault:} reference resolved per call ({@code user:password} for basic
 * auth, a token for bearer); it never reaches a log or an error. Until the instance and the
 * credential are configured, change-managed jobs are refused rather than run without a record.
 */
@Slf4j
@Component
@Profile({"servicenow", "live"})
@Primary
public class ServiceNowChangeTickets implements ChangeTicketPort {

    static final String TABLE = "/api/now/table/change_request";
    static final String FIELDS = "sys_id,number,state,correlation_id";
    private static final Pattern NUMBER = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Map<String, String> STATES = Map.of("-5", "new", "-4", "assess", "-3", "authorize", "-2", "scheduled",
            "-1", "implement", "0", "review", "3", "closed", "4", "canceled");

    private record Known(String sysId, String jobId) {
    }

    private final WebClient client;
    private final ChangeProperties props;
    private final CredentialsPort credentials;
    private final Map<String, Known> known = new ConcurrentHashMap<>();

    public ServiceNowChangeTickets(WebClient.Builder builder, ChangeProperties props, CredentialsPort credentials) {
        this.client = builder.clone().build();
        this.props = props;
        this.credentials = credentials;
        ChangeProperties.ServiceNow sn = props.getServicenow();
        if (!sn.getCredential().isBlank() && !sn.getCredential().startsWith("vault:")) {
            throw new IllegalArgumentException("platform.change.servicenow.credential must be a vault: reference, never the secret itself");
        }
        if (configured()) {
            log.info("Change records go to ServiceNow at {}", instance());
        } else {
            log.warn("ServiceNow is not configured (platform.change.servicenow.instance and credential): change-managed jobs will be refused");
        }
    }

    @Override
    public Mono<ChangeTicket> open(OpenChange change) {
        if (!configured()) {
            return Mono.error(new IllegalStateException("ServiceNow is not configured: no change can be opened for job " + change.jobId()
                    + ". Set platform.change.servicenow.instance and platform.change.servicenow.credential."));
        }
        ChangeProperties.ServiceNow sn = props.getServicenow();
        String evidence = props.evidenceUrlOf(change.jobId());
        ObjectNode body = F.objectNode()
                .put("short_description", abbreviate(change.summary(), 160))
                .put("description", description(change, evidence))
                .put("type", sn.getType())
                .put("correlation_id", change.jobId())
                .put("correlation_display", "iaf:" + change.workflow() + "@" + change.version());
        putIfSet(body, "assignment_group", sn.getAssignmentGroup());
        putIfSet(body, "category", sn.getCategory());
        putIfSet(body, "state", sn.getOpenState());
        if (evidence != null) {
            String field = sn.getEvidenceField().isBlank() ? "work_notes" : sn.getEvidenceField();
            body.put(field, field.equals("work_notes") ? "Evidence of job " + change.jobId() + ": " + evidence : evidence);
        }
        URI uri = UriComponentsBuilder.fromUriString(instance()).path(TABLE).queryParam("sysparm_fields", FIELDS).build().encode().toUri();
        return authorization()
                .flatMap(auth -> client.post().uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, r -> failure("open a change for job " + change.jobId(), r))
                        .bodyToMono(JsonNode.class)
                        .timeout(timeout()))
                .map(json -> {
                    JsonNode r = json.path("result");
                    String number = r.path("number").asText(null);
                    String sysId = r.path("sys_id").asText(null);
                    if (number == null || sysId == null) {
                        throw new IllegalStateException("ServiceNow created no change for job " + change.jobId() + ": the answer had no number or sys_id");
                    }
                    known.put(number, new Known(sysId, change.jobId()));
                    return new ChangeTicket(number, recordUrl(sysId), stateName(r.path("state").asText(null), "new"), evidence);
                });
    }

    @Override
    public Mono<ChangeTicket> close(String changeId, boolean success) {
        if (!configured()) {
            return Mono.error(new IllegalStateException("ServiceNow is not configured: change " + changeId + " cannot be closed."));
        }
        if (changeId == null || !NUMBER.matcher(changeId).matches()) {
            return Mono.error(new IllegalArgumentException("not a change number: " + changeId));
        }
        return find(changeId).flatMap(k -> {
            String evidence = props.evidenceUrlOf(k.jobId());
            ObjectNode body = F.objectNode()
                    .put("state", props.getServicenow().getCloseState())
                    .put("close_code", success ? "successful" : "unsuccessful")
                    .put("close_notes", "Job " + k.jobId() + (success ? " succeeded." : " did not succeed.")
                            + (evidence == null ? "" : " Evidence: " + evidence));
            URI uri = UriComponentsBuilder.fromUriString(instance()).path(TABLE + "/{sysId}").queryParam("sysparm_fields", FIELDS)
                    .buildAndExpand(k.sysId()).encode().toUri();
            return authorization()
                    .flatMap(auth -> client.patch().uri(uri)
                            .header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .onStatus(HttpStatusCode::isError, r -> failure("close change " + changeId, r))
                            .bodyToMono(JsonNode.class)
                            .timeout(timeout()))
                    .map(json -> new ChangeTicket(changeId, recordUrl(k.sysId()), success ? "closed" : "closed-unsuccessful", evidence));
        });
    }

    /** The sys_id and job of a change: remembered from open, else looked up by number. */
    private Mono<Known> find(String number) {
        Known k = known.get(number);
        if (k != null) {
            return Mono.just(k);
        }
        URI uri = UriComponentsBuilder.fromUriString(instance()).path(TABLE)
                .queryParam("sysparm_query", "number=" + number)
                .queryParam("sysparm_fields", FIELDS)
                .queryParam("sysparm_limit", 1)
                .build().encode().toUri();
        return authorization()
                .flatMap(auth -> client.get().uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, r -> failure("find change " + number, r))
                        .bodyToMono(JsonNode.class)
                        .timeout(timeout()))
                .flatMap(json -> {
                    JsonNode first = json.path("result").path(0);
                    if (first.isMissingNode() || !first.hasNonNull("sys_id")) {
                        return Mono.error(new NotFoundException("change", number));
                    }
                    Known found = new Known(first.get("sys_id").asText(), first.path("correlation_id").asText(null));
                    known.put(number, found);
                    return Mono.just(found);
                });
    }

    private Mono<String> authorization() {
        String ref = props.getServicenow().getCredential();
        return credentials.resolve(ref)
                .switchIfEmpty(Mono.error(new IllegalStateException("the ServiceNow credential " + ref + " resolves to nothing")))
                .map(secret -> {
                    if ("bearer".equalsIgnoreCase(props.getServicenow().getAuth())) {
                        return "Bearer " + secret;
                    }
                    if (!secret.contains(":")) {
                        throw new IllegalStateException("the ServiceNow credential " + ref + " must hold user:password for basic auth");
                    }
                    return "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
                });
    }

    /** The failure as ServiceNow words it ({@code error.message}), with the status; never the request's credential. */
    private static Mono<? extends Throwable> failure(String what, ClientResponse response) {
        return response.bodyToMono(JsonNode.class)
                .map(b -> b.path("error").path("message").asText(""))
                .onErrorReturn("")
                .defaultIfEmpty("")
                .map(message -> new IllegalStateException("ServiceNow could not " + what + ": " + response.statusCode().value()
                        + (message.isBlank() ? "" : " " + abbreviate(message, 200))));
    }

    static String description(OpenChange change, String evidence) {
        StringBuilder d = new StringBuilder();
        d.append("Job ").append(change.jobId()).append(": workflow ").append(change.workflow()).append(' ').append(change.version())
                .append(", requested by ").append(change.requester()).append('.');
        Preview p = change.preview();
        if (p != null) {
            if (p.steps() != null && !p.steps().isEmpty()) {
                d.append("\n\nWhat it will do:");
                p.steps().forEach(s -> d.append("\n- ").append(s.name() == null ? s.id() : s.name())
                        .append(s.effect() == null ? "" : " (" + s.effect().json() + ")")
                        .append(s.detail() == null || s.detail().isBlank() ? "" : ": " + s.detail()));
            }
            if (p.affectedResources() != null && !p.affectedResources().isEmpty()) {
                d.append("\n\nAffected resources:");
                p.affectedResources().forEach(r -> d.append("\n- ").append(r.type()).append(' ').append(r.name() == null ? r.id() : r.name()));
            }
            if (p.cost() != null && p.cost().monthly() > 0) {
                d.append("\n\nCost: ").append(String.format(Locale.ROOT, "%.2f %s per month", p.cost().monthly(), p.cost().currency()));
            }
        }
        if (evidence != null) {
            d.append("\n\nEvidence: ").append(evidence);
        }
        return d.toString();
    }

    private boolean configured() {
        ChangeProperties.ServiceNow sn = props.getServicenow();
        return !sn.getInstance().isBlank() && !sn.getCredential().isBlank();
    }

    private String instance() {
        String i = props.getServicenow().getInstance().trim();
        return i.endsWith("/") ? i.substring(0, i.length() - 1) : i;
    }

    private String recordUrl(String sysId) {
        return instance() + "/change_request.do?sys_id=" + sysId;
    }

    private Duration timeout() {
        Duration t = props.getServicenow().getTimeout();
        return t == null ? Duration.ofSeconds(10) : t;
    }

    private static String stateName(String state, String fallback) {
        if (state == null || state.isBlank()) {
            return fallback;
        }
        return STATES.getOrDefault(state, state);
    }

    private static void putIfSet(ObjectNode body, String field, String value) {
        if (value != null && !value.isBlank()) {
            body.put(field, value);
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
