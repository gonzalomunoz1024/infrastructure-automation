package com.iaf.platform.notifications.adapters.outbound;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.notifications.application.NotificationProperties;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.outbound.InboxStore;

/**
 * The inbox of the {@code postgres} profile: one row per notification in
 * {@code inbox_notifications}, its audience in {@code inbox_audience} (one row per user or team,
 * so the inbox of a caller is one query) and its readers in {@code inbox_reads}. The newest
 * {@code platform.notifications.keep} are kept: a save deletes the oldest beyond that, with
 * their audience and their readers. Shared by every replica and kept across restarts.
 */
@Component
@Primary
@Profile("postgres")
public class JdbcInboxStore implements InboxStore {

    private final Jdbc jdbc;
    private final int keep;
    private final Clock clock;

    @Autowired
    public JdbcInboxStore(Jdbc jdbc, NotificationProperties props, Clock clock) {
        this(jdbc, props.getKeep(), clock);
    }

    public JdbcInboxStore(Jdbc jdbc, int keep, Clock clock) {
        this.jdbc = jdbc;
        this.keep = Math.max(1, keep);
        this.clock = clock;
    }

    @Override
    public void save(Notification n) {
        jdbc.inTransaction(() -> {
            if (!jdbc.insertIfAbsent("INSERT INTO inbox_notifications (id, at, kind, doc) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", n.id(),
                    Jdbc.time(n.at()), n.kind(), jdbc.json(n))) {
                return;
            }
            List<Object[]> audience = new ArrayList<>();
            n.users().forEach(u -> audience.add(new Object[] {n.id(), "user:" + u}));
            n.teams().forEach(t -> audience.add(new Object[] {n.id(), "team:" + t}));
            jdbc.batch("INSERT INTO inbox_audience (notification_id, member) VALUES (?, ?)", audience);
            jdbc.update("DELETE FROM inbox_notifications WHERE seq < (SELECT MIN(seq) FROM (SELECT seq FROM inbox_notifications ORDER BY seq DESC"
                    + " LIMIT ?) AS newest)", keep);
        });
    }

    @Override
    public Optional<Notification> get(String id) {
        return id == null ? Optional.empty()
                : jdbc.first("SELECT doc FROM inbox_notifications WHERE id = ?", (rs, i) -> jdbc.read(rs.getString(1), Notification.class), id);
    }

    @Override
    public List<Notification> visibleTo(Identity who) {
        if (who == null) {
            return List.of();
        }
        List<Object> members = new ArrayList<>();
        members.add("user:" + who.id());
        if (who.teams() != null) {
            who.teams().stream().filter(t -> t != null).forEach(t -> members.add("team:" + t));
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(members.size(), "?"));
        return jdbc.query("SELECT n.doc FROM inbox_notifications n WHERE EXISTS (SELECT 1 FROM inbox_audience a WHERE a.notification_id = n.id"
                + " AND a.member IN (" + placeholders + ")) ORDER BY n.seq DESC", (rs, i) -> jdbc.read(rs.getString(1), Notification.class),
                members.toArray());
    }

    @Override
    public boolean markRead(String id, String userId) {
        if (id == null || userId == null) {
            return false;
        }
        try {
            return jdbc.inTransaction(() -> {
                if (jdbc.first("SELECT 1 FROM inbox_notifications WHERE id = ?", (rs, i) -> rs.getInt(1), id).isEmpty()) {
                    return false;
                }
                jdbc.insertIfAbsent("INSERT INTO inbox_reads (notification_id, user_id, read_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", id, userId,
                        Jdbc.time(clock.instant()));
                return true;
            });
        } catch (DataIntegrityViolationException e) {
            return false;   // dropped from the inbox in between
        }
    }

    @Override
    public boolean readBy(String id, String userId) {
        if (id == null || userId == null) {
            return false;
        }
        return jdbc.first("SELECT 1 FROM inbox_reads WHERE notification_id = ? AND user_id = ?", (rs, i) -> rs.getInt(1), id, userId).isPresent();
    }

    /** For tests: how many notifications are kept. */
    int size() {
        return jdbc.first("SELECT COUNT(*) FROM inbox_notifications", (rs, i) -> rs.getInt(1)).orElse(0);
    }
}
