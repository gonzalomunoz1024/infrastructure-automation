package com.iaf.platform.notifications.adapters.outbound;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.notifications.application.NotificationProperties;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.outbound.InboxStore;

/** The demo inbox: the newest {@code platform.notifications.keep} notifications in memory, with their readers. */
@Component
public class InMemoryInboxStore implements InboxStore {

    private final int keep;
    private final Deque<Notification> newestFirst = new ArrayDeque<>();
    private final Map<String, Notification> byId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> readers = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryInboxStore(NotificationProperties props) {
        this(props.getKeep());
    }

    public InMemoryInboxStore(int keep) {
        this.keep = Math.max(1, keep);
    }

    @Override
    public synchronized void save(Notification n) {
        if (byId.putIfAbsent(n.id(), n) != null) {
            return;
        }
        newestFirst.addFirst(n);
        while (newestFirst.size() > keep) {
            Notification old = newestFirst.removeLast();
            byId.remove(old.id());
            readers.remove(old.id());
        }
    }

    @Override
    public Optional<Notification> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public synchronized List<Notification> visibleTo(Identity who) {
        return newestFirst.stream().filter(n -> n.addressedTo(who)).toList();
    }

    @Override
    public boolean markRead(String id, String userId) {
        if (!byId.containsKey(id)) {
            return false;
        }
        readers.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(userId);
        return true;
    }

    @Override
    public boolean readBy(String id, String userId) {
        Set<String> r = readers.get(id);
        return r != null && r.contains(userId);
    }
}
