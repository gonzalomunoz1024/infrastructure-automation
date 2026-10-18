package com.iaf.platform.workflows.adapters.outbound;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

/** Change requests kept in memory and merged from the UI. With the {@code mongo} profile, {@link MongoChangeRequests} keeps them instead. */
@Profile("!mongo")
@Component
public class InMemoryChangeRequests implements ChangeRequestPort {

    private final Map<String, ChangeRequest> byId = new ConcurrentHashMap<>();

    @Override
    public List<ChangeRequest> all() {
        return byId.values().stream()
                .sorted(Comparator.comparing(ChangeRequest::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Override
    public Optional<ChangeRequest> get(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    @Override
    public void save(ChangeRequest cr) {
        byId.put(cr.id(), cr);
    }
}
