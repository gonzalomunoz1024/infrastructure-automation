package com.iaf.console.bff.resourcegroups.adapters.outbound;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.resourcegroups.domain.CreateKey;
import com.iaf.console.bff.resourcegroups.domain.CreatedGroup;
import com.iaf.console.bff.resourcegroups.domain.GroupNameTakenException;
import com.iaf.console.bff.resourcegroups.domain.ResourceGroup;
import com.iaf.console.bff.resourcegroups.ports.outbound.ResourceGroupStore;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** Resource groups of one instance, in memory; one lock makes the name check and the write one step. */
@Component
@Profile("!mongo")
public class InMemoryResourceGroupStore implements ResourceGroupStore {

    private record Stored(ResourceGroup group, CreateKey key) {
    }

    private final Map<String, Stored> byId = new HashMap<>();

    @Override
    public Mono<List<ResourceGroup>> list(AccountScope scope) {
        return Mono.fromCallable(() -> {
            synchronized (byId) {
                return byId.values().stream().map(Stored::group).filter(g -> in(scope, g)).toList();
            }
        });
    }

    @Override
    public Mono<ResourceGroup> get(AccountScope scope, String groupId) {
        return Mono.fromCallable(() -> {
            synchronized (byId) {
                Stored stored = byId.get(groupId);
                return stored != null && in(scope, stored.group()) ? stored.group() : null;
            }
        });
    }

    @Override
    public Mono<CreatedGroup> findCreated(AccountScope scope, String idempotencyKey) {
        return Mono.fromCallable(() -> {
            synchronized (byId) {
                return byId.values().stream()
                        .filter(s -> in(scope, s.group()) && s.key() != null && s.key().key().equals(idempotencyKey)
                                && s.key().principal().equals(scope.principal()))
                        .max(Comparator.comparing(s -> s.key().at()))
                        .map(s -> new CreatedGroup(s.group(), s.key()))
                        .orElse(null);
            }
        });
    }

    @Override
    public Mono<ResourceGroup> create(AccountScope scope, ResourceGroup group, CreateKey key) {
        return Mono.fromCallable(() -> {
            requireIn(scope, group);
            synchronized (byId) {
                if (byId.values().stream().anyMatch(s -> in(scope, s.group()) && s.group().name().equals(group.name()))) {
                    throw new GroupNameTakenException(group.name());
                }
                byId.put(group.groupId(), new Stored(group, key));
                return group;
            }
        });
    }

    @Override
    public Mono<ResourceGroup> update(AccountScope scope, ResourceGroup group, long expectedVersion) {
        return Mono.fromCallable(() -> {
            requireIn(scope, group);
            synchronized (byId) {
                Stored stored = byId.get(group.groupId());
                if (stored == null || !in(scope, stored.group()) || stored.group().version() != expectedVersion) {
                    return null;
                }
                byId.put(group.groupId(), new Stored(group, stored.key()));
                return group;
            }
        });
    }

    @Override
    public Mono<Boolean> delete(AccountScope scope, String groupId) {
        return Mono.fromCallable(() -> {
            synchronized (byId) {
                Stored stored = byId.get(groupId);
                if (stored == null || !in(scope, stored.group())) {
                    return false;
                }
                byId.remove(groupId);
                return true;
            }
        });
    }

    private static boolean in(AccountScope scope, ResourceGroup group) {
        return group.in(scope.accountId(), scope.region());
    }

    private static void requireIn(AccountScope scope, ResourceGroup group) {
        if (!in(scope, group)) {
            throw new IllegalStateException("the group " + group.groupId() + " is not of the scope's account and region");
        }
    }
}
