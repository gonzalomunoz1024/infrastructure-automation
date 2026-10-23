package com.iaf.console.bff.drift.adapters.inbound;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.drift.application.Monitoring;
import com.iaf.console.bff.drift.domain.Alarm;
import com.iaf.console.bff.drift.domain.AlarmInput;
import com.iaf.console.bff.drift.domain.ResourceMetrics;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * Monitoring (WP 9.21): a resource's Monitoring tab ({@code /console/v1/r/{type}/{wfrn}/metrics}
 * and {@code /alarms}, the wfrn checked like every resource sub-route) and the account's alarms
 * ({@code /console/v1/alarms}, created and changed under an id the web app picks), all through the
 * drift service.
 */
@RestController
public class MonitoringController {

    private static final String TYPE = "{type:[a-z0-9-]+}";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final Monitoring monitoring;

    public MonitoringController(Monitoring monitoring) {
        this.monitoring = monitoring;
    }

    /** A resource's alarms, firing ones first. */
    public record AlarmList(List<Alarm> items) {
    }

    @GetMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}/metrics", produces = "application/json")
    public Mono<ResourceMetrics> metrics(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "step", required = false) String step,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> monitoring.metrics(session, type, wfrn, from, to, step));
    }

    @GetMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}/alarms", produces = "application/json")
    public Mono<AlarmList> ofResource(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> monitoring.ofResource(session, type, wfrn)).map(AlarmList::new);
    }

    @GetMapping(path = "/console/v1/alarms", produces = "application/json")
    public Mono<Page<Alarm>> list(@RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "wfrn", required = false) String wfrn,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> monitoring.list(session, state, wfrn, limit, after));
    }

    @PutMapping(path = "/console/v1/alarms/{alarmId}", produces = "application/json")
    public Mono<ResponseEntity<Alarm>> put(@PathVariable("alarmId") String alarmId, @RequestBody(required = false) AlarmInput body,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> monitoring.put(session, alarmId, body, requestId(exchange), idempotencyKey))
                .map(saved -> ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK).body(saved.alarm()));
    }

    @DeleteMapping(path = "/console/v1/alarms/{alarmId}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable("alarmId") String alarmId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> monitoring.delete(session, alarmId, requestId(exchange), idempotencyKey))
                .then(Mono.fromSupplier(() -> ResponseEntity.noContent().<Void>build()));
    }

    private static String requestId(ServerWebExchange exchange) {
        return OnBehalfOf.requestId(Correlation.of(exchange));
    }
}
