package com.iaf.console.bff.scorecards.application;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.application.OrganizationScope;
import com.iaf.console.bff.common.errors.CodedConflictException;
import com.iaf.console.bff.common.relay.RelayCall;
import com.iaf.console.bff.common.relay.RelayClient;
import com.iaf.console.bff.common.relay.Relayed;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.scorecards.domain.Fix;
import com.iaf.console.bff.scorecards.domain.ScorecardNames;
import com.iaf.console.bff.scorecards.ports.outbound.ScorecardsPort;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.wfrn.Wfrn;
import com.iaf.console.wfrn.WfrnException;

import reactor.core.publisher.Mono;

/**
 * Compliance scorecards (WP 9.6), passed through from the drift service as its contract answers
 * them. Every read asks {@code scorecards:list} in the account; the heat map asks it in each of the
 * person's accounts and reads the allowed ones (at most 500) with the BFF's service token, naming
 * each row's account. A one-click fix asks {@code scorecards:remediate} on the resource and the
 * fix's own action ({@code <service>:tag} for a tags fix, {@code <service>:<verb>} for a workflow,
 * with its step-up rule), then relays the person's {@code X-On-Behalf-Of} so the job names them;
 * the drift service's 200, 202, 400 and 409 come back as they were.
 */
public class Scorecards {

    public static final int MAX_ACCOUNTS = 500;
    public static final int MAX_DAYS = 400;
    static final String ON_BEHALF_OF = "X-On-Behalf-Of";
    static final String REQUEST_ID = "X-Console-Request-Id";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final Access access;
    private final Sessions sessions;
    private final Trust trust;
    private final PlatformProperties platform;
    private final ScorecardsPort drift;
    private final ObjectMapper json;

    public Scorecards(Access access, Sessions sessions, Trust trust, PlatformProperties platform, ScorecardsPort drift, ObjectMapper json) {
        this.access = access;
        this.sessions = sessions;
        this.trust = trust;
        this.platform = platform;
        this.drift = drift;
        this.json = json;
    }

    /** {@code GET /console/v1/scorecards?days=}: the session account's scorecard. */
    public Mono<Relayed> scorecard(Session session, Integer days) {
        return Mono.defer(() -> {
            if (days != null && (days < 1 || days > MAX_DAYS)) {
                throw invalid("range", "days", "days is 1 to " + MAX_DAYS);
            }
            return list(session).then(person(session, RelayCall.get("/v1/scorecards/account", RelayClient.params("days", days == null ? null : days.toString()))));
        });
    }

    /** {@code GET /console/v1/scorecards/packs}: the rule packs, their rules and their fixes. */
    public Mono<Relayed> packs(Session session) {
        return list(session).then(person(session, RelayCall.get("/v1/scorecards/packs", List.of())));
    }

    /**
     * {@code GET /console/v1/scorecards/heatmap}: one row per account of the person's where
     * {@code scorecards:list} is allowed, with the account's name; the others are left out, never
     * shown empty, and no allowed account is an empty map.
     */
    public Mono<Relayed> heatmap(Session session) {
        return OrganizationScope.decide(access, session, ScorecardNames.LIST, a -> ScorecardNames.pattern(platform.partition(), a), MAX_ACCOUNTS)
                .flatMap(split -> {
                    if (split.allowed().isEmpty()) {
                        ObjectNode empty = json.createObjectNode();
                        empty.putArray("packs");
                        empty.putArray("rows");
                        return Mono.just(new Relayed(200, "application/json", Map.of(), write(empty)));
                    }
                    return drift.callAsService(RelayCall.get("/v1/scorecards/heatmap", RelayClient.params("accountIds", String.join(",", split.allowed()))))
                            .map(answer -> withAccountNames(session, answer));
                });
    }

    /** {@code GET /console/v1/scorecards/packs/{pack}/rules/{ruleId}/failing}: the account's resources that fail a rule. */
    public Mono<Relayed> failing(Session session, String pack, String ruleId, String type, Integer limit, String after) {
        return Mono.defer(() -> {
            String path = rulePath(pack, ruleId) + "/failing";
            return list(session).then(person(session, RelayCall.get(path, RelayClient.params("type", blankToNull(type),
                    "limit", limit == null ? null : limit.toString(), "after", blankToNull(after)))));
        });
    }

    /** {@code GET /console/v1/r/{type}/{wfrn}/compliance}: the rules that apply to a resource, failing first; {@code scorecards:list} and the resource's own get. */
    public Mono<Relayed> compliance(Session session, String type, String wfrn) {
        return Mono.defer(() -> {
            Wfrn name = ownName(session, type, wfrn);
            return list(session).then(access.require(session, name.service() + ":get", name.format()))
                    .then(person(session, RelayCall.get("/v1/scorecards/resources", RelayClient.params("wfrn", name.format()))));
        });
    }

    /**
     * {@code POST /console/v1/scorecards/packs/{pack}/rules/{ruleId}:remediate}: starts the rule's
     * fix for one failing resource of the session's account, as the person.
     */
    public Mono<Relayed> remediate(Session session, String pack, String ruleId, byte[] body, String idempotencyKey, String requestId) {
        return Mono.defer(() -> {
            String path = rulePath(pack, ruleId) + ":remediate";
            JsonNode node = parse(body);
            if (!(node instanceof ObjectNode request)) {
                throw invalid("type", "", "the body is a JSON object");
            }
            String wfrn = request.path("wfrn").asText("");
            if (wfrn.isBlank()) {
                throw invalid("required", "/wfrn", "wfrn is required: the failing resource");
            }
            Wfrn name;
            try {
                name = Wfrn.parse(wfrn);
            } catch (WfrnException e) {
                throw invalid("wfrn", "/wfrn", e.getMessage());
            }
            if (!name.account().equals(session.accountId()) || !name.region().equals(session.region())) {
                throw new NotFoundException("no resource " + wfrn + " here");
            }
            return access.require(session, ScorecardNames.REMEDIATE, name.format())
                    .then(fixOf(session, pack, ruleId))
                    .flatMap(fix -> {
                        // fail closed: with no fix to name, no verb is checked, so nothing is started
                        Fix found = fix.orElseThrow(() -> new CodedConflictException("remediation.none",
                                "the rule " + ruleId + " of " + pack + " names no fix", Map.of("pack", pack, "ruleId", ruleId)));
                        String action = found.action(name.service());
                        if (action == null) {
                            throw new CodedConflictException("remediation.unavailable",
                                    "the fix of " + ruleId + " names no verb to check", Map.of("pack", pack, "ruleId", ruleId));
                        }
                        return access.require(session, action, name.format()).then(sessions.requireFreshFor(session, action));
                    })
                    .then(Mono.defer(() -> {
                        Map<String, String> headers = new LinkedHashMap<>();
                        headers.put(ON_BEHALF_OF, OnBehalfOf.assertion(trust, session));
                        if (requestId != null) {
                            headers.put(REQUEST_ID, requestId);
                        }
                        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                            headers.put(IDEMPOTENCY_KEY, idempotencyKey);
                        }
                        return person(session, RelayCall.send("POST", path, List.of(), headers, body));
                    }));
        });
    }

    /**
     * The fix the pack describes for the rule, read from the drift service's packs; empty when it
     * names none. A 409 {@code remediation.unavailable} when the packs cannot be read, never a guess.
     */
    Mono<Optional<Fix>> fixOf(Session session, String pack, String ruleId) {
        return person(session, RelayCall.get("/v1/scorecards/packs", List.of())).map(answer -> {
            JsonNode root = answer.ok() ? parse(answer.body()) : null;
            if (root == null) {
                throw new CodedConflictException("remediation.unavailable", "the rule packs could not be read; try again",
                        Map.of("pack", pack, "ruleId", ruleId));
            }
            for (JsonNode p : root.path("items")) {
                if (!pack.equals(p.path("name").asText())) {
                    continue;
                }
                for (JsonNode r : p.path("rules")) {
                    JsonNode spec = r.path("remediation");
                    if (ruleId.equals(r.path("id").asText()) && spec.isObject()) {
                        return Optional.of(new Fix(spec.path("kind").asText(Fix.WORKFLOW), spec.path("verb").asText(null), spec.path("workflow").asText(null)));
                    }
                }
            }
            return Optional.<Fix>empty();
        });
    }

    /** The heat map's rows with each account's name, from the person's memberships. */
    private Relayed withAccountNames(Session session, Relayed answer) {
        if (!answer.ok()) {
            return answer;
        }
        JsonNode root = parse(answer.body());
        if (root == null || !root.path("rows").isArray()) {
            return answer;
        }
        for (JsonNode row : root.get("rows")) {
            if (row instanceof ObjectNode r && !r.hasNonNull("accountName")) {
                session.membership(r.path("accountId").asText()).map(AccountMembership::name).ifPresent(n -> r.put("accountName", n));
            }
        }
        return answer.withJson(write(root));
    }

    // ---- plumbing

    private Mono<Void> list(Session session) {
        return access.require(session, ScorecardNames.LIST, ScorecardNames.pattern(platform.partition(), session.accountId()));
    }

    private Mono<Relayed> person(Session session, RelayCall call) {
        return Mono.defer(() -> drift.callAsPerson(CurrentSession.scope(session), caller(session), call));
    }

    /** A pack's rule's path; a name the contract does not allow is a 404, so nothing else reaches the drift service's path. */
    private static String rulePath(String pack, String ruleId) {
        if (!ScorecardNames.isName(pack) || !ScorecardNames.isName(ruleId)) {
            throw new NotFoundException("no rule " + pack + "/" + ruleId);
        }
        return "/v1/scorecards/packs/" + pack + "/rules/" + ruleId;
    }

    private static Wfrn ownName(Session session, String type, String wfrn) {
        try {
            Wfrn name = Wfrn.parse(wfrn);
            if (!name.account().equals(session.accountId()) || !name.type().equals(type)) {
                throw new NotFoundException("no " + type + " " + wfrn + " here");
            }
            return name;
        } catch (WfrnException e) {
            throw new NotFoundException("no " + type + " " + wfrn + " here");
        }
    }

    private static StateCaller caller(Session session) {
        return new StateCaller(session.user().userId(), session.currentAccount().groups(), session.currentAccount().roles());
    }

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] write(JsonNode node) {
        try {
            return json.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static InvalidInputException invalid(String code, String field, String message) {
        return new InvalidInputException(message, List.of(Problem.error(code, field, message)));
    }
}
