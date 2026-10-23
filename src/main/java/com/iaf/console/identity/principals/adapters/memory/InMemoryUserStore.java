package com.iaf.console.identity.principals.adapters.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import com.iaf.console.identity.principals.domain.User;
import com.iaf.console.identity.principals.ports.outbound.UserStore;
import com.iaf.console.kit.errors.ConflictException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory users of the demo and the tests. */
public final class InMemoryUserStore implements UserStore {

    private final Map<String, User> users = new ConcurrentSkipListMap<>();

    @Override
    public Mono<User> insert(User user) {
        return Mono.fromCallable(() -> {
            synchronized (users) {
                if (users.containsKey(user.userId()) || taken(user)) {
                    throw new ConflictException("a user with this email or identity exists");
                }
                users.put(user.userId(), user);
                return user;
            }
        });
    }

    @Override
    public Mono<User> save(User user) {
        return Mono.fromCallable(() -> {
            synchronized (users) {
                if (taken(user)) {
                    throw new ConflictException("a user with this email or identity exists");
                }
                users.put(user.userId(), user);
                return user;
            }
        });
    }

    private boolean taken(User user) {
        return users.values().stream().anyMatch(u -> !u.userId().equals(user.userId()) && (u.email().equalsIgnoreCase(user.email())
                || user.issuer() != null && user.issuer().equals(u.issuer()) && user.subject().equals(u.subject())));
    }

    @Override
    public Mono<User> findById(String userId) {
        return Mono.justOrEmpty(users.get(userId));
    }

    @Override
    public Mono<User> findByEmail(String email) {
        return Mono.justOrEmpty(users.values().stream().filter(u -> u.email().equalsIgnoreCase(email == null ? "" : email.trim())).findFirst());
    }

    @Override
    public Mono<User> findByIssuerAndSubject(String issuer, String subject) {
        return Mono.justOrEmpty(users.values().stream().filter(u -> issuer.equals(u.issuer()) && subject.equals(u.subject())).findFirst());
    }

    @Override
    public Flux<User> findByIds(List<String> userIds) {
        return Flux.defer(() -> Flux.fromIterable(users.values().stream().filter(u -> userIds.contains(u.userId())).toList()));
    }

    @Override
    public Flux<User> findAll() {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(users.values())));
    }
}
