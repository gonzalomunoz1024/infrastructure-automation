package com.iaf.console.bff.iam.application;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.identity.application.IdentityClient;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * The organization admins' pages (hub decision 34): every account, closing one, break-glass
 * access, and the organization's guardrails, served by the identity service to the
 * console-internal principal only. The BFF calls it with its own service token for a session whose
 * sign-in made the person an organization admin, naming them in {@code X-On-Behalf-Of} (the signed
 * assertion of the person, as the platform reads it) and in the {@code console.audit} log; anyone
 * else gets a 404, as the identity service answers callers that are not the internal principal.
 * Every write needs a fresh sign-in (the step-up 403), and break-glass access names the admin
 * calling as its {@code approvedBy}, whatever the body said.
 */
public class OrganizationProxy {

    static final String ON_BEHALF_OF = "X-On-Behalf-Of";

    private static final Logger audit = LoggerFactory.getLogger("console.audit");

    /** What an organization call is: its action name (for the step-up and the audit line) and what it does to cached decisions. */
    public enum Call {
        LIST_ACCOUNTS("account:list", false),
        CLOSE_ACCOUNT("account:close", true),
        BREAK_GLASS("account:break-glass", true),
        LIST_GUARDRAILS("guardrail:list", false),
        GET_GUARDRAIL("guardrail:get", false),
        CREATE_GUARDRAIL("guardrail:create", true),
        UPDATE_GUARDRAIL("guardrail:update", true),
        DELETE_GUARDRAIL("guardrail:delete", true);

        private final String action;
        private final boolean write;

        Call(String action, boolean write) {
            this.action = action;
            this.write = write;
        }

        public String action() {
            return action;
        }

        public boolean write() {
            return write;
        }
    }

    private final IdentityClient identity;
    private final Access access;
    private final Sessions sessions;
    private final Trust trust;
    private final ObjectMapper json;

    public OrganizationProxy(IdentityClient identity, Access access, Sessions sessions, Trust trust, ObjectMapper json) {
        this.identity = identity;
        this.access = access;
        this.sessions = sessions;
        this.trust = trust;
        this.json = json;
    }

    /**
     * Sends {@code call} to the identity service's {@code path} (an account id or a guardrail id is
     * its {@code target}, for the audit line and the cache).
     */
    public Mono<ProxiedResponse> forward(Session session, Call call, HttpMethod method, String path, String target, String rawQuery,
            HttpHeaders inbound, byte[] body) {
        if (!session.organizationAdmin()) {
            return Mono.error(new NotFoundException("not found"));
        }
        Mono<Void> fresh = call.write() ? sessions.requireFresh(session, call.action()) : Mono.empty();
        return fresh.then(Mono.defer(() -> {
            byte[] sent = call == Call.BREAK_GLASS ? approvedBy(body, session.user().userId()) : body;
            WebClient.RequestBodySpec request = identity.asService(method).uri(IamProxy.uri(identity, path, rawQuery));
            Map<String, String> extra = Map.of(ON_BEHALF_OF, OnBehalfOf.assertion(trust, session));
            return IamProxy.exchange(identity, request, inbound, extra, sent, bytes -> bytes);
        })).doOnNext(response -> {
            if (call.write()) {
                audit.info("organization action={} target={} admin={} account={} status={}", call.action(), target == null ? "-" : target,
                        session.user().userId(), session.accountId(), response.status().value());
            }
            if (call.write() && response.status().is2xxSuccessful()) {
                if (call == Call.CLOSE_ACCOUNT || call == Call.BREAK_GLASS) {
                    access.invalidate(target);
                } else {
                    // a guardrail may target any account, now and later
                    access.invalidateAll();
                }
            }
        });
    }

    /** The break-glass body with {@code approvedBy} set to the admin calling; a body that is not a JSON object goes as it came, for the identity service's 400. */
    byte[] approvedBy(byte[] body, String adminUserId) {
        try {
            JsonNode parsed = body == null || body.length == 0 ? json.createObjectNode() : json.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                return body;
            }
            ((ObjectNode) parsed).put("approvedBy", adminUserId);
            return json.writeValueAsBytes(parsed);
        } catch (IOException e) {
            return body;
        }
    }
}
