package com.iaf.console.bff.jobs.application;

import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.iaf.console.platform.client.RequestOptions;

/** The headers of a platform command for a session: the P-02 assertion, the console request id and the idempotency key. */
public final class OnBehalfOf {

    private OnBehalfOf() {
    }

    public static String assertion(Trust trust, Session session) {
        OnBehalfOfAssertions.User user = new OnBehalfOfAssertions.User(session.user().userId(), session.user().name(), session.user().email(),
                session.currentAccount().groups());
        return trust.assertionSigner().sign(user, session.accountId(), session.region());
    }

    public static RequestOptions options(Trust trust, Session session, String requestId, String idempotencyKey) {
        return RequestOptions.console(assertion(trust, session), requestId, idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey);
    }

    /** A console request id: the correlation id of the request when there is one. */
    public static String requestId(String correlationId) {
        return "req_" + (correlationId == null || correlationId.isBlank() ? com.iaf.console.wfrn.Ulid.next().toLowerCase() : correlationId);
    }
}
