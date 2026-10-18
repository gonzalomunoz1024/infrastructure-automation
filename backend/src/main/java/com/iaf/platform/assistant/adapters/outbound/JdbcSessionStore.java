package com.iaf.platform.assistant.adapters.outbound;

import java.time.Instant;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.ports.outbound.SessionStore;
import com.iaf.platform.common.adapters.jdbc.Jdbc;

/**
 * The builder sessions of the {@code postgres} profile: one row per session in
 * {@code assistant_sessions}, so a conversation and its draft survive a restart. An update locks
 * the row while it changes the session, so concurrent turns never lose one another; a change
 * that returns null removes the session, as in memory. {@code SessionRetention} deletes the rows
 * not updated within {@code platform.assistant.session-retention}.
 */
@Component
@Primary
@Profile("postgres")
public class JdbcSessionStore implements SessionStore {

    private final Jdbc jdbc;

    public JdbcSessionStore(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(AssistantSession session) {
        String doc = jdbc.json(session);
        jdbc.upsert("UPDATE assistant_sessions SET requester = ?, status = ?, updated_at = ?, doc = ? WHERE id = ?",
                new Object[] {session.requester(), session.status(), Jdbc.time(session.updatedAt()), doc, session.id()},
                "INSERT INTO assistant_sessions (id, requester, status, updated_at, doc) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                new Object[] {session.id(), session.requester(), session.status(), Jdbc.time(session.updatedAt()), doc});
    }

    @Override
    public Optional<AssistantSession> get(String id) {
        return id == null ? Optional.empty()
                : jdbc.first("SELECT doc FROM assistant_sessions WHERE id = ?", (rs, i) -> jdbc.read(rs.getString(1), AssistantSession.class), id);
    }

    @Override
    public Optional<AssistantSession> update(String id, UnaryOperator<AssistantSession> change) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.inTransaction(() -> {
            Optional<AssistantSession> current = jdbc.first("SELECT doc FROM assistant_sessions WHERE id = ? FOR UPDATE",
                    (rs, i) -> jdbc.read(rs.getString(1), AssistantSession.class), id);
            if (current.isEmpty()) {
                return Optional.<AssistantSession>empty();
            }
            AssistantSession next = change.apply(current.get());
            if (next == null) {
                jdbc.update("DELETE FROM assistant_sessions WHERE id = ?", id);
                return Optional.<AssistantSession>empty();
            }
            jdbc.update("UPDATE assistant_sessions SET requester = ?, status = ?, updated_at = ?, doc = ? WHERE id = ?",
                    next.requester(), next.status(), Jdbc.time(next.updatedAt()), jdbc.json(next), id);
            return Optional.of(next);
        });
    }

    @Override
    public int deleteUpdatedBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM assistant_sessions WHERE updated_at < ?", Jdbc.time(cutoff));
    }
}
