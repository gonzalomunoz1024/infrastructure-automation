package com.iaf.platform.providers.adapters.outbound;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.providers.domain.ReviewEvidence;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.ReviewEvidence.ConnectionTest;
import com.iaf.platform.providers.ports.outbound.ReviewEvidencePort;

/**
 * The demo evidence store: per provider name, the last {@value #MAX_CONNECTIONS} connection tests
 * and the last {@value #MAX_TRIES} tries, with a count of the older ones dropped to keep to that.
 * A response larger than {@value #MAX_RESPONSE_BYTES} bytes is kept without its body (the try
 * still counts; the schema comparison skips it). The {@code mongo} profile keeps the same in
 * MongoDB ({@link MongoReviewEvidence}).
 */
@Profile("!mongo")
@Component
public class InMemoryReviewEvidence implements ReviewEvidencePort {

    static final int MAX_CONNECTIONS = 50;
    static final int MAX_TRIES = 500;
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Deque<ConnectionTest>> connections = new ConcurrentHashMap<>();
    private final Map<String, Deque<ActionTry>> tries = new ConcurrentHashMap<>();
    private final Map<String, Integer> droppedConnections = new ConcurrentHashMap<>();
    private final Map<String, Integer> droppedTries = new ConcurrentHashMap<>();

    @Override
    public void recordConnection(String provider, ConnectionTest test) {
        if (provider == null || test == null) {
            return;
        }
        append(connections.computeIfAbsent(provider, k -> new ArrayDeque<>()), test, MAX_CONNECTIONS, droppedConnections, provider);
    }

    @Override
    public void recordTry(String provider, ActionTry tryResult) {
        if (provider == null || tryResult == null) {
            return;
        }
        append(tries.computeIfAbsent(provider, k -> new ArrayDeque<>()), bounded(tryResult), MAX_TRIES, droppedTries, provider);
    }

    @Override
    public ReviewEvidence evidence(String provider) {
        if (provider == null) {
            return ReviewEvidence.NONE;
        }
        Deque<ConnectionTest> c = connections.get(provider);
        Deque<ActionTry> t = tries.get(provider);
        return new ReviewEvidence(snapshot(c), snapshot(t), dropped(c, droppedConnections, provider), dropped(t, droppedTries, provider));
    }

    /** Appends and drops the oldest entries beyond {@code keep}, counting them. */
    private static <T> void append(Deque<T> q, T entry, int keep, Map<String, Integer> dropped, String provider) {
        synchronized (q) {
            q.addLast(entry);
            int gone = 0;
            while (q.size() > keep) {
                q.pollFirst();
                gone++;
            }
            if (gone > 0) {
                dropped.merge(provider, gone, Integer::sum);
            }
        }
    }

    private static int dropped(Deque<?> q, Map<String, Integer> dropped, String provider) {
        if (q == null) {
            return 0;
        }
        synchronized (q) {
            return dropped.getOrDefault(provider, 0);
        }
    }

    /** A try as every store keeps it: without its body when the response is over {@value #MAX_RESPONSE_BYTES} bytes. */
    static ActionTry bounded(ActionTry t) {
        return tooLarge(t) ? new ActionTry(t.action(), t.environment(), t.ok(), t.status(), t.durationMs(), t.errorClass(), null, t.verified(), t.at(), t.by(),
                t.endpoint(), t.credential()) : t;
    }

    private static <T> List<T> snapshot(Deque<T> q) {
        if (q == null) {
            return List.of();
        }
        synchronized (q) {
            return List.copyOf(q);
        }
    }

    private static boolean tooLarge(ActionTry t) {
        if (t.response() == null) {
            return false;
        }
        try {
            return JSON.writeValueAsBytes(t.response()).length > MAX_RESPONSE_BYTES;
        } catch (JsonProcessingException e) {
            return true;
        }
    }
}
