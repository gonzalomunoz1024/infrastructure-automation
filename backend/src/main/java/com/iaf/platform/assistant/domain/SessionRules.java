package com.iaf.platform.assistant.domain;

import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;

/**
 * A builder session belongs to the identity that started it: only that identity reads it or
 * sends it messages, plus an admin who is a person. A service account, admin or not, only uses
 * the sessions it started itself.
 */
public final class SessionRules {

    private SessionRules() {
    }

    public static boolean mayUse(Identity who, AssistantSession session) {
        if (who == null || session == null) {
            return false;
        }
        if (who.id() != null && who.id().equals(session.requester())) {
            return true;
        }
        return who.admin() && who.kind() != Identity.Kind.SERVICE;
    }

    /** Refuses with the session id and who started it, nothing else about the session. */
    public static void requireUse(Identity who, AssistantSession session) {
        if (!mayUse(who, session)) {
            throw new ForbiddenException(who.name() + " may not use assistant session " + session.id() + " (started by " + session.requester() + ")");
        }
    }
}
