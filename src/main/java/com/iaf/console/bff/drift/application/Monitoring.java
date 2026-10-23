package com.iaf.console.bff.drift.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.drift.domain.Alarm;
import com.iaf.console.bff.drift.domain.AlarmInput;
import com.iaf.console.bff.drift.domain.DriftCommands;
import com.iaf.console.bff.drift.domain.DriftQueries;
import com.iaf.console.bff.drift.domain.DriftResults;
import com.iaf.console.bff.drift.domain.ResourceMetrics;
import com.iaf.console.bff.drift.ports.outbound.DriftPort;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * Monitoring (WP 9.21) through the drift service: a resource's metric series for its Monitoring
 * tab ({@code <service>:observe} on the wfrn), its alarms ({@code <service>:get}), the account's
 * alarms (an alarm on a type the person may not list is left out), and creating, changing and
 * deleting an alarm, each {@code <service>:update} on the alarm's wfrn. A change that moves an
 * alarm to another resource needs the update on both, since it takes the alarm off the first.
 */
public class Monitoring {

    /** The most alarms a resource's tab shows: one page of the drift service's largest. */
    static final int RESOURCE_PAGE = CursorPaging.MAX_PAGE_SIZE;

    private final DriftPort drift;
    private final DriftPolicies policies;

    public Monitoring(DriftPort drift, DriftPolicies policies) {
        this.drift = drift;
        this.policies = policies;
    }

    /** A resource's series over a range: the last 24 hours, one point per 5 minutes, unless the page asks otherwise. */
    public Mono<ResourceMetrics> metrics(Session session, String type, String wfrn, String from, String to, String step) {
        return Mono.defer(() -> {
            Instant start = DriftInputs.instant("from", from);
            Instant end = DriftInputs.instant("to", to);
            Duration every = DriftInputs.step("step", step);
            if (start != null && end != null && !start.isBefore(end)) {
                throw DriftInputs.invalid("range", "from", "from comes before to");
            }
            return policies.resource(session, type, wfrn).flatMap(t -> policies.require(session, t.service(), DriftPolicies.OBSERVE, wfrn)
                    .then(Mono.defer(() -> drift.getResourceMetrics(DriftPolicies.scope(session), DriftPolicies.caller(session),
                            new DriftQueries.Metrics(wfrn, start, end, every)))));
        });
    }

    /** A resource's alarms, firing ones first, as {@code <service>:get} on the wfrn allows. */
    public Mono<List<Alarm>> ofResource(Session session, String type, String wfrn) {
        return policies.resource(session, type, wfrn).flatMap(t -> policies.require(session, t.service(), DriftPolicies.GET, wfrn)
                .then(Mono.defer(() -> drift.listAlarms(DriftPolicies.scope(session), DriftPolicies.caller(session),
                        new DriftQueries.Alarms(wfrn, null, RESOURCE_PAGE, null))))
                .map(page -> page.items().stream().filter(a -> wfrn.equals(a.wfrn())).toList()));
    }

    /** One page of the account's alarms in the session's region, firing ones first. */
    public Mono<Page<Alarm>> list(Session session, String state, String wfrn, Integer limit, String after) {
        return Mono.defer(() -> {
            String named = DriftInputs.blankToNull(wfrn);
            if (named != null && DriftPolicies.parse(named) == null) {
                throw DriftInputs.invalid("format", "wfrn", "wfrn is a resource name, wfrn:<partition>:<service>:<region>:<account>:<type>/<id>");
            }
            DriftQueries.Alarms query = new DriftQueries.Alarms(named, DriftInputs.oneOf("state", state, DriftInputs.ALARM_STATES),
                    CursorPaging.pageSize(limit), DriftInputs.blankToNull(after));
            return drift.listAlarms(DriftPolicies.scope(session), DriftPolicies.caller(session), query)
                    .flatMap(page -> visible(session, page.items()).map(items -> new Page<>(items, page.next())));
        });
    }

    /** Creates or replaces an alarm under the id the web app picked: 201 when new, 200 when replaced. */
    public Mono<DriftResults.AlarmSaved> put(Session session, String alarmId, AlarmInput input, String requestId, String idempotencyKey) {
        return Mono.defer(() -> {
            String id = alarmId(alarmId);
            if (input == null) {
                throw DriftInputs.invalid("required", "wfrn", "the body is an AlarmInput: wfrn, metric, comparison, threshold");
            }
            String target = DriftInputs.required("wfrn", input.wfrn());
            Wfrn parsed = DriftPolicies.parse(target);
            if (parsed == null) {
                throw DriftInputs.invalid("format", "wfrn", "wfrn is a resource name, wfrn:<partition>:<service>:<region>:<account>:<type>/<id>");
            }
            if (!parsed.in(session.accountId(), session.region())) {
                throw NotFoundException.of("resource", target);
            }
            DriftInputs.text("name", input.name(), 128);
            return existing(session, id)
                    .flatMap(before -> before.wfrn() == null || before.wfrn().equals(target) ? Mono.<Void>empty() : requireUpdate(session, before))
                    .then(Mono.defer(() -> policies.require(session, parsed.service(), DriftPolicies.UPDATE, target)))
                    .then(Mono.defer(() -> drift.putAlarm(DriftPolicies.scope(session), DriftPolicies.caller(session), id,
                            input.by(session.user().userId()), new DriftCommands.Meta(idempotencyKey, requestId, null))));
        });
    }

    /** Deletes an alarm: read first, since the update it needs is on the alarm's wfrn. */
    public Mono<Void> delete(Session session, String alarmId, String requestId, String idempotencyKey) {
        return Mono.defer(() -> {
            String id = alarmId(alarmId);
            return drift.getAlarm(DriftPolicies.scope(session), DriftPolicies.caller(session), id)
                    .flatMap(alarm -> requireUpdate(session, alarm))
                    .then(Mono.defer(() -> drift.deleteAlarm(DriftPolicies.scope(session), DriftPolicies.caller(session), id,
                            new DriftCommands.Meta(idempotencyKey, requestId, null))));
        });
    }

    // ---- pieces

    private static String alarmId(String alarmId) {
        String id = DriftInputs.required("alarmId", alarmId);
        if (!DriftInputs.ALARM_ID.matcher(id).matches()) {
            throw DriftInputs.invalid("format", "alarmId", "an alarm id is 1 to 64 letters, digits, '_' or '-'");
        }
        return id;
    }

    /** The alarm under an id, when there is one: a PUT that replaces it must also be allowed on the resource it was on. */
    private Mono<Alarm> existing(Session session, String alarmId) {
        return drift.getAlarm(DriftPolicies.scope(session), DriftPolicies.caller(session), alarmId)
                .onErrorResume(NotFoundException.class, e -> Mono.empty());
    }

    /** {@code <service>:update} on an alarm's wfrn; an alarm outside the session's account and region is a 404, as a stranger's would be. */
    private Mono<Void> requireUpdate(Session session, Alarm alarm) {
        Wfrn w = DriftPolicies.ownedBy(session, "alarm", alarm.alarmId(), alarm.wfrn());
        return policies.require(session, w.service(), DriftPolicies.UPDATE, alarm.wfrn());
    }

    /** The alarms the person may see: one on a type they may not list, or outside the session's account and region, is left out. */
    private Mono<List<Alarm>> visible(Session session, List<Alarm> rows) {
        List<AccessCheck> checks = new ArrayList<>();
        List<Wfrn> names = new ArrayList<>(rows.size());
        for (Alarm row : rows) {
            Wfrn w = DriftPolicies.inScope(session, row.wfrn());
            names.add(w);
            if (w != null) {
                checks.add(policies.list(session, w.service(), w.type()));
            }
        }
        return policies.ask(session, checks).map(answers -> {
            List<Alarm> kept = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                Wfrn w = names.get(i);
                if (w != null && answers.allowed(policies.list(session, w.service(), w.type()))) {
                    kept.add(rows.get(i));
                }
            }
            return kept;
        });
    }
}
