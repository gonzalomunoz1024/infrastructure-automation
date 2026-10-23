package com.iaf.console.drift.monitoring.adapters.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.drift.auth.application.WriteGuard;
import com.iaf.console.drift.auth.domain.ConsolePrincipal;
import com.iaf.console.drift.monitoring.application.AlarmEvaluator;
import com.iaf.console.drift.monitoring.application.AlarmService;
import com.iaf.console.drift.monitoring.application.AlarmsReport;
import com.iaf.console.drift.monitoring.domain.Alarm;
import com.iaf.console.drift.monitoring.domain.AlarmInput;
import com.iaf.console.drift.monitoring.domain.AlarmQuery;
import com.iaf.console.drift.monitoring.domain.AlarmState;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * The alarms API of {@code contracts/openapi/console-drift-service.yaml}: the account's alarms with
 * the firing ones first, one alarm, create or change one under the id the caller names, delete one,
 * and for operators an evaluation of every alarm now.
 */
@RestController
public final class AlarmController {

    private final AlarmService alarms;
    private final AlarmEvaluator evaluator;
    private final WriteGuard guard;

    public AlarmController(AlarmService alarms, AlarmEvaluator evaluator, WriteGuard guard) {
        this.alarms = alarms;
        this.evaluator = evaluator;
        this.guard = guard;
    }

    @GetMapping("/v1/alarms")
    public Mono<Listing<Alarm>> list(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String wfrn,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        AccountScope scope = Callers.scope(exchange, accountId);
        String resource = wfrn == null || wfrn.isBlank() ? null : Wfrn.parse(wfrn.trim()).format();
        AlarmQuery query = new AlarmQuery(resource, state(state), CursorPaging.pageSize(limit),
                after == null || after.isBlank() ? null : CursorPaging.decode(after));
        return alarms.list(scope, query).map(Listing::of);
    }

    @GetMapping("/v1/alarms/{alarmId}")
    public Mono<Alarm> get(ServerWebExchange exchange, @PathVariable String alarmId, @RequestParam(required = false) String accountId) {
        return alarms.get(Callers.scope(exchange, accountId), alarmId);
    }

    @PutMapping("/v1/alarms/{alarmId}")
    public Mono<ResponseEntity<Alarm>> put(ServerWebExchange exchange, @PathVariable String alarmId, @RequestParam(required = false) String accountId,
            @RequestBody AlarmInput body) {
        AccountScope scope = Callers.scopeOf(exchange, accountId, body == null || body.wfrn() == null || body.wfrn().isBlank() ? null : body.wfrn());
        ConsolePrincipal principal = Callers.principal(exchange);
        AlarmInput input = body == null ? null : new AlarmInput(body.wfrn(), body.name(), body.metric(), body.comparison(), body.threshold(),
                body.windowMinutes(), WriteGuard.actor(principal, body.actor()));
        // an alarm is the resource's setting: <service>:update on the resource it watches, and on the one it watched when it moves
        Mono<Void> onNew = input != null && input.wfrn() != null && Wfrn.isValid(input.wfrn()) ? guard.require(principal, "update", input.wfrn()) : Mono.empty();
        Mono<Void> onOld = principal.internal() ? Mono.empty()
                : alarms.get(scope, alarmId).onErrorResume(NotFoundException.class, e -> Mono.empty())
                        .filter(old -> input == null || !old.wfrn().equals(input.wfrn()))
                        .flatMap(old -> guard.require(principal, "update", old.wfrn()));
        return onNew.then(onOld).then(Mono.defer(() -> alarms.put(scope, alarmId, input)))
                .map(put -> ResponseEntity.status(put.created() ? HttpStatus.CREATED : HttpStatus.OK).body(put.alarm()));
    }

    @DeleteMapping("/v1/alarms/{alarmId}")
    public Mono<ResponseEntity<Void>> delete(ServerWebExchange exchange, @PathVariable String alarmId, @RequestParam(required = false) String accountId) {
        AccountScope scope = Callers.scope(exchange, accountId);
        ConsolePrincipal principal = Callers.principal(exchange);
        Mono<Void> allowed = principal.internal() ? Mono.empty()
                : alarms.get(scope, alarmId).flatMap(alarm -> guard.require(principal, "update", alarm.wfrn()));
        return allowed.then(Mono.defer(() -> alarms.delete(scope, alarmId))).thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/internal/alarms:evaluate")
    public Mono<AlarmsReport> evaluate(ServerWebExchange exchange) {
        Callers.internal(exchange);
        return evaluator.evaluateAll();
    }

    /** The state filter; a word the API does not know is a 400, never an empty page. */
    static AlarmState state(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        AlarmState s = AlarmState.of(value.trim());
        if (s == null) {
            throw new IllegalArgumentException("state is one of: ok, alarm, insufficient");
        }
        return s;
    }
}
