package com.iaf.console.bff.live.application;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.http.codec.ServerSentEvent;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.cost.application.Costs;
import com.iaf.console.bff.cost.domain.CostNames;
import com.iaf.console.bff.live.domain.LiveEvent;
import com.iaf.console.bff.live.domain.LiveHub;
import com.iaf.console.bff.live.domain.Sequenced;
import com.iaf.console.bff.session.domain.Session;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The live stream of a session's account as server-sent events, narrowed to a resource or to event
 * types, with heartbeats. A {@code budget.alert} reaches only the sessions whose person may read
 * the budget ({@code budgets:get} on it).
 */
public class Live {

    private final LiveHub hub;
    private final LiveProperties properties;
    private final Access access;
    private final String partition;

    public Live(LiveHub hub, LiveProperties properties, Access access, String partition) {
        this.hub = hub;
        this.properties = properties;
        this.access = access;
        this.partition = partition;
    }

    public LiveHub hub() {
        return hub;
    }

    public Flux<ServerSentEvent<LiveEvent>> stream(Session session, Long lastEventId, String wfrn, Set<String> types) {
        Flux<ServerSentEvent<LiveEvent>> events = hub.subscribe(session.accountId(), lastEventId)
                .filter(s -> matches(s, wfrn, types))
                .concatMap(s -> mayRead(session, s.event()).filter(Boolean::booleanValue).map(ok -> s))
                .map(s -> ServerSentEvent.<LiveEvent>builder().id(Long.toString(s.seq())).event(s.event().type()).data(s.event()).build());
        Flux<ServerSentEvent<LiveEvent>> heartbeat = Flux.interval(properties.heartbeat(), properties.heartbeat())
                .map(tick -> ServerSentEvent.<LiveEvent>builder().comment("keepalive").build());
        return Flux.merge(events, heartbeat);
    }

    /** Whether the person may see an event: every event of the account, a budget alert as {@code budgets:get} on the budget allows. */
    Mono<Boolean> mayRead(Session session, LiveEvent event) {
        if (!LiveEvent.BUDGET_ALERT.equals(event.type())) {
            return Mono.just(true);
        }
        if (access == null) {
            return Mono.just(false);
        }
        String budgetId = event.data() == null ? null : event.data().path("budgetId").asText(null);
        String name = CostNames.isBudgetId(budgetId) ? CostNames.budget(partition, session.accountId(), budgetId)
                : CostNames.budgetsPattern(partition, session.accountId());
        return access.decide(session, List.of(new AccessCheck(Costs.BUDGETS_GET, name))).map(d -> d.get(0).allowed())
                .onErrorReturn(false);
    }

    private static boolean matches(Sequenced s, String wfrn, Set<String> types) {
        LiveEvent event = s.event();
        if (LiveEvent.RESYNC.equals(event.type())) {
            return true;
        }
        if (wfrn != null && !wfrn.isBlank() && !s.global() && !wfrn.equals(event.wfrn())) {
            return false;
        }
        return types == null || types.isEmpty() || types.contains(event.type());
    }

    public Duration heartbeat() {
        return properties.heartbeat();
    }
}
