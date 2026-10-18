package com.iaf.platform.gateway.adapters.outbound;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.iaf.platform.gateway.application.GatewayProperties;
import com.iaf.platform.gateway.domain.KeyedRequest;
import com.iaf.platform.gateway.domain.StoredAnswer;
import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;

/**
 * Keys in memory, oldest first, at most {@code platform.gateway.idempotency.keep} of them (the
 * oldest go first); expired entries are dropped as new keys come in. One instance only: behind a
 * load balancer the keys belong in a shared store.
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final int keep;
    private final LinkedHashMap<String, KeyedRequest> entries = new LinkedHashMap<>();

    @Autowired
    public InMemoryIdempotencyStore(GatewayProperties props) {
        this(props.getIdempotency().getKeep());
    }

    public InMemoryIdempotencyStore(int keep) {
        this.keep = Math.max(1, keep);
    }

    @Override
    public synchronized Optional<KeyedRequest> claim(KeyedRequest inFlight, Instant now) {
        purge(now);
        String id = id(inFlight.caller(), inFlight.key());
        KeyedRequest existing = entries.get(id);
        if (existing != null && existing.liveAt(now)) {
            return Optional.of(existing);
        }
        entries.remove(id);
        entries.put(id, inFlight);
        while (entries.size() > keep) {
            Iterator<String> oldest = entries.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return Optional.empty();
    }

    @Override
    public synchronized void complete(String caller, String key, StoredAnswer answer) {
        entries.computeIfPresent(id(caller, key), (k, e) -> e.withAnswer(answer));
    }

    @Override
    public synchronized void release(String caller, String key) {
        KeyedRequest e = entries.get(id(caller, key));
        if (e != null && !e.answered()) {
            entries.remove(id(caller, key));
        }
    }

    synchronized int size() {
        return entries.size();
    }

    /** Every key lives as long, so the expired ones are the oldest: drop from the head until a key still holds. */
    private void purge(Instant now) {
        Iterator<Map.Entry<String, KeyedRequest>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (now.isBefore(it.next().getValue().expiresAt())) {
                return;
            }
            it.remove();
        }
    }

    private static String id(String caller, String key) {
        return caller + "\n" + key;
    }
}
