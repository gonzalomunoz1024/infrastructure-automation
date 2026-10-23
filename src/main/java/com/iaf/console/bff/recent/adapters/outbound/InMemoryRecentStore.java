package com.iaf.console.bff.recent.adapters.outbound;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.recent.domain.RecentItem;
import com.iaf.console.bff.recent.domain.RecentList;
import com.iaf.console.bff.recent.ports.outbound.RecentStore;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** Recent pages of one instance, in memory. */
@Component
@Profile("!mongo")
public class InMemoryRecentStore implements RecentStore {

    private final Map<String, List<RecentItem>> byPerson = new ConcurrentHashMap<>();

    @Override
    public Mono<List<RecentItem>> list(AccountScope scope, int max) {
        return Mono.fromSupplier(() -> byPerson.getOrDefault(key(scope), List.of()).stream().sorted(RecentList.NEWEST_FIRST).limit(max).toList());
    }

    @Override
    public Mono<Void> visit(AccountScope scope, RecentItem visit, int max) {
        return Mono.fromRunnable(() -> byPerson.compute(key(scope), (k, current) -> RecentList.visit(current == null ? List.of() : current, visit, max)));
    }

    private static String key(AccountScope scope) {
        return scope.accountId() + ":" + scope.principal();
    }
}
