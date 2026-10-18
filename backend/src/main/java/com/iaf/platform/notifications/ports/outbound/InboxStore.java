package com.iaf.platform.notifications.ports.outbound;

import java.util.List;
import java.util.Optional;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.notifications.domain.Notification;

/**
 * The in-app inbox: notifications kept for the people and teams they are addressed to, with who
 * has read each one. The demo adapter keeps the newest ones in memory.
 */
public interface InboxStore {

    void save(Notification notification);

    Optional<Notification> get(String id);

    /** What {@code who} can see (addressed to them or to one of their teams), newest first. */
    List<Notification> visibleTo(Identity who);

    /** Marks it read by {@code userId}; false when it is not kept. */
    boolean markRead(String id, String userId);

    boolean readBy(String id, String userId);
}
