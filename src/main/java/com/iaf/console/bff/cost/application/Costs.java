package com.iaf.console.bff.cost.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.application.OrganizationScope;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.common.relay.RelayCall;
import com.iaf.console.bff.common.relay.RelayClient;
import com.iaf.console.bff.common.relay.Relayed;
import com.iaf.console.bff.cost.domain.CostNames;
import com.iaf.console.bff.cost.domain.Money;
import com.iaf.console.bff.cost.ports.outbound.CostPort;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.wfrn.Wfrn;
import com.iaf.console.wfrn.WfrnException;

import reactor.core.publisher.Mono;

/**
 * Cost Explorer, price lists, the chargeback, a resource's cost and the account's budgets, from
 * the cost service (decision 25). The account's policies decide first: {@code cost:list} reads,
 * {@code cost:export} the chargeback, {@code budgets:<verb>} the budgets. A person's own account
 * is read as the person; the organization view asks {@code cost:list} (or {@code cost:export}) on
 * every account of the person's memberships and reads the allowed ones, at most 500, in one call
 * with the BFF's service token, naming the rest in {@code skippedAccounts}. The service's answer
 * passes back as it was: its status, ETag and body; each budget gains {@code can}.
 */
public class Costs {

    public static final String COST_LIST = "cost:list";
    public static final String COST_EXPORT = "cost:export";
    public static final String BUDGETS_LIST = "budgets:list";
    public static final String BUDGETS_GET = "budgets:get";
    public static final String BUDGETS_CREATE = "budgets:create";
    public static final String BUDGETS_UPDATE = "budgets:update";
    public static final String BUDGETS_DELETE = "budgets:delete";
    /** The most accounts one organization read names, as the cost service takes them. */
    public static final int MAX_ACCOUNTS = 500;
    public static final String SKIPPED_HEADER = "X-Console-Skipped-Accounts";
    static final String ACCOUNT = "account";
    static final String ORGANIZATION = "organization";
    /** Parameters the browser may not set: the BFF names the accounts itself. */
    private static final Set<String> RESERVED = Set.of("scope", "accountid");
    private static final Logger log = LoggerFactory.getLogger(Costs.class);

    private final Access access;
    private final Sessions sessions;
    private final PlatformProperties platform;
    private final CostPort cost;
    private final ObjectMapper json;

    public Costs(Access access, Sessions sessions, PlatformProperties platform, CostPort cost, ObjectMapper json) {
        this.access = access;
        this.sessions = sessions;
        this.platform = platform;
        this.cost = cost;
        this.json = json;
    }

    // ---- Cost Explorer, price lists, chargeback

    /** {@code GET /console/v1/cost}: the cost service's report for the session's account or the organization. */
    public Mono<Relayed> report(Session session, String scope, List<Map.Entry<String, String>> params) {
        return read(session, scope, COST_LIST, "/v1/cost", params);
    }

    /** {@code GET /console/v1/cost/chargeback} and {@code .csv}: a month's chargeback, as {@code cost:export} allows. */
    public Mono<Relayed> chargeback(Session session, String scope, List<Map.Entry<String, String>> params, boolean csv) {
        return read(session, scope, COST_EXPORT, csv ? "/v1/chargeback.csv" : "/v1/chargeback", params);
    }

    /** {@code GET /console/v1/cost/price-lists}: what prices the account's resources, as {@code cost:list} allows. */
    public Mono<Relayed> priceLists(Session session) {
        return access.require(session, COST_LIST, costPattern(session.accountId()))
                .then(Mono.defer(() -> cost.callAsPerson(CurrentSession.scope(session), caller(session), RelayCall.get("/v1/price-lists", List.of()))));
    }

    /** {@code GET /console/v1/r/{type}/{wfrn}/cost}: one resource's rate, month to date and price, as {@code cost:list} on its wfrn allows. */
    public Mono<Relayed> resourceCost(Session session, String type, String wfrn) {
        return Mono.defer(() -> {
            Wfrn name = ownName(session, type, wfrn);
            return access.require(session, COST_LIST, name.format())
                    .then(Mono.defer(() -> cost.callAsPerson(CurrentSession.scope(session), caller(session), resourceCall(name.format()))));
        });
    }

    /**
     * One resource's monthly cost, for the copilot's "Explain this resource": the cost service's
     * {@code monthly} for the resource (its rate this month, what the month totals at today's
     * price), asked as the person with {@code cost:list} on the wfrn, as
     * {@code /console/v1/r/{type}/{wfrn}/cost} asks. Empty, never an error, when the policies
     * refuse, the service answers 404 (another account, or not a resource it knows) or any other
     * refusal, or the service is down or slow.
     */
    public Mono<Optional<Money>> monthlyCost(Session session, String wfrn) {
        return Mono.defer(() -> {
            Wfrn name;
            try {
                name = Wfrn.parse(wfrn);
            } catch (WfrnException e) {
                return Mono.just(Optional.<Money>empty());
            }
            if (!name.account().equals(session.accountId())) {
                return Mono.just(Optional.<Money>empty());
            }
            return access.decide(session, List.of(new AccessCheck(COST_LIST, name.format()))).flatMap(decisions -> {
                if (!decisions.get(0).allowed()) {
                    return Mono.just(Optional.<Money>empty());
                }
                return cost.callAsPerson(CurrentSession.scope(session), caller(session), resourceCall(name.format())).map(this::monthlyOf);
            });
        }).onErrorResume(e -> {
            log.debug("no monthly cost for {}: {}", wfrn, e.toString());
            return Mono.just(Optional.empty());
        });
    }

    private Optional<Money> monthlyOf(Relayed answer) {
        if (!answer.ok()) {
            return Optional.empty();
        }
        JsonNode node = parse(answer.body());
        if (node == null || !node.path("monthly").isNumber()) {
            return Optional.empty();
        }
        BigDecimal amount = node.get("monthly").decimalValue();
        return Optional.of(new Money(amount, node.path("currency").asText("USD")));
    }

    private static RelayCall resourceCall(String wfrn) {
        return RelayCall.get("/v1/wfrn/" + RelayClient.segment(wfrn) + "/cost", List.of());
    }

    /** A wfrn of the session's account whose type is the path's; anything else is a 404, as the resource pages answer. */
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

    private Mono<Relayed> read(Session session, String scope, String action, String path, List<Map.Entry<String, String>> params) {
        return Mono.defer(() -> {
            List<Map.Entry<String, String>> query = params.stream().filter(p -> !RESERVED.contains(p.getKey().toLowerCase(Locale.ROOT))).toList();
            if (ACCOUNT.equals(scopeOf(scope))) {
                return access.require(session, action, costPattern(session.accountId()))
                        .then(Mono.defer(() -> cost.callAsPerson(CurrentSession.scope(session), caller(session), RelayCall.get(path, query))));
            }
            return organization(session, action).flatMap(split -> {
                if (split.allowed().isEmpty()) {
                    return Mono.error(new DeniedException(split.firstRefusal().denial()));
                }
                List<Map.Entry<String, String>> named = new ArrayList<>(query);
                split.allowed().forEach(a -> named.add(Map.entry("accountId", a)));
                return cost.callAsService(RelayCall.get(path, named)).map(answer -> withSkipped(answer, split.skipped()));
            });
        });
    }

    /** The person's accounts on which the policies allow an action on cost, at most 500, and the others. */
    public Mono<OrganizationScope.Split> organization(Session session, String action) {
        return OrganizationScope.decide(access, session, action, this::costPattern, MAX_ACCOUNTS);
    }

    /** The organization's answer with the accounts it left out: {@code skippedAccounts} in a JSON report, a header on the CSV. */
    private Relayed withSkipped(Relayed answer, List<String> skipped) {
        if (!answer.ok()) {
            return answer;
        }
        if (answer.contentType() != null && answer.contentType().startsWith("text/csv")) {
            return skipped.isEmpty() ? answer : answer.withHeader(SKIPPED_HEADER, String.join(",", skipped));
        }
        JsonNode node = parse(answer.body());
        if (!(node instanceof ObjectNode report)) {
            return answer;
        }
        ArrayNode list = report.putArray("skippedAccounts");
        skipped.forEach(list::add);
        return answer.withJson(write(report));
    }

    static String scopeOf(String scope) {
        String s = scope == null || scope.isBlank() ? ACCOUNT : scope.strip();
        if (!ACCOUNT.equals(s) && !ORGANIZATION.equals(s)) {
            throw new InvalidInputException("scope is account or organization", List.of(Problem.error("enum", "scope", "account or organization")));
        }
        return s;
    }

    // ---- budgets

    /** {@code GET /console/v1/budgets}: the account's budgets with this month's status, each with {@code can}. */
    public Mono<Relayed> budgets(Session session, Integer limit, String after) {
        return access.require(session, BUDGETS_LIST, CostNames.budgetsPattern(platform.partition(), session.accountId()))
                .then(Mono.defer(() -> person(session, RelayCall.get("/v1/budgets", RelayClient.params("limit", limit == null ? null : limit.toString(),
                        "after", after)))))
                .flatMap(answer -> withCan(session, answer));
    }

    public Mono<Relayed> budget(Session session, String budgetId) {
        return budgetCall(session, budgetId, BUDGETS_GET, RelayCall.get("/v1/budgets/" + budgetId, List.of())).flatMap(answer -> withCan(session, answer));
    }

    /** {@code GET /console/v1/budgets/{budgetId}/periods}: how the budget did month by month. */
    public Mono<Relayed> periods(Session session, String budgetId, Integer limit, String after) {
        return budgetCall(session, budgetId, BUDGETS_GET, RelayCall.get("/v1/budgets/" + budgetId + "/periods",
                RelayClient.params("limit", limit == null ? null : limit.toString(), "after", after)));
    }

    /**
     * {@code POST /console/v1/budgets}: a budget for the session's account, or a tag within it. The
     * scope's account is always the session's: left out, it is filled in; another is a 400.
     */
    public Mono<Relayed> createBudget(Session session, byte[] body, String idempotencyKey) {
        return access.require(session, BUDGETS_CREATE, CostNames.budgetsPattern(platform.partition(), session.accountId())).then(Mono.defer(() -> {
            ObjectNode input = object(body);
            JsonNode scope = input.get("scope");
            if (scope != null && !scope.isNull() && !scope.isObject()) {
                throw invalid("type", "/scope", "scope is an object");
            }
            ObjectNode s = scope instanceof ObjectNode o ? o : input.putObject("scope");
            String named = s.path("accountId").asText("");
            if (!named.isEmpty() && !named.equals(session.accountId())) {
                throw invalid("scope.account", "/scope/accountId", "a budget is created in the session's account, " + session.accountId());
            }
            s.put("accountId", session.accountId());
            Map<String, String> headers = idempotencyKey == null || idempotencyKey.isBlank() ? Map.of() : Map.of("Idempotency-Key", idempotencyKey);
            return person(session, RelayCall.send("POST", "/v1/budgets", List.of(), headers, write(input)));
        })).flatMap(answer -> withCan(session, answer));
    }

    /** {@code PUT /console/v1/budgets/{budgetId}}: needs {@code If-Match}, the ETag the budget was read with (400 without). */
    public Mono<Relayed> updateBudget(Session session, String budgetId, String ifMatch, byte[] body) {
        return Mono.defer(() -> {
            if (ifMatch == null || ifMatch.isBlank()) {
                throw invalid("required", "If-Match", "If-Match is required: the ETag the budget was read with");
            }
            object(body);
            return budgetCall(session, budgetId, BUDGETS_UPDATE, RelayCall.send("PUT", "/v1/budgets/" + budgetId, List.of(), Map.of("If-Match", ifMatch), body))
                    .flatMap(answer -> withCan(session, answer));
        });
    }

    /** {@code DELETE /console/v1/budgets/{budgetId}}: a fresh sign-in, as every {@code *:delete}; {@code If-Match} passes on when sent. */
    public Mono<Relayed> deleteBudget(Session session, String budgetId, String ifMatch) {
        Map<String, String> headers = ifMatch == null || ifMatch.isBlank() ? Map.of() : Map.of("If-Match", ifMatch);
        return budgetCall(session, budgetId, BUDGETS_DELETE, RelayCall.send("DELETE", "/v1/budgets/" + budgetId, List.of(), headers, null));
    }

    private Mono<Relayed> budgetCall(Session session, String budgetId, String action, RelayCall call) {
        return Mono.defer(() -> {
            if (!CostNames.isBudgetId(budgetId)) {
                throw new NotFoundException("no budget " + budgetId);
            }
            return access.require(session, action, CostNames.budget(platform.partition(), session.accountId(), budgetId))
                    .then(sessions.requireFreshFor(session, action))
                    .then(Mono.defer(() -> person(session, call)));
        });
    }

    /** Each budget of an answer with {@code can: { update, delete }}, as the policies decide on it. */
    private Mono<Relayed> withCan(Session session, Relayed answer) {
        if (!answer.ok()) {
            return Mono.just(answer);
        }
        JsonNode node = parse(answer.body());
        if (!(node instanceof ObjectNode root)) {
            return Mono.just(answer);
        }
        List<ObjectNode> budgets = new ArrayList<>();
        if (root.path("items").isArray()) {
            root.get("items").forEach(item -> {
                if (item instanceof ObjectNode o && o.hasNonNull("budgetId")) {
                    budgets.add(o);
                }
            });
        } else if (root.hasNonNull("budgetId")) {
            budgets.add(root);
        }
        if (budgets.isEmpty()) {
            return Mono.just(answer);
        }
        List<AccessCheck> checks = new ArrayList<>();
        for (ObjectNode b : budgets) {
            String wfrn = budgetName(session, b.get("budgetId").asText());
            checks.add(new AccessCheck(BUDGETS_UPDATE, wfrn));
            checks.add(new AccessCheck(BUDGETS_DELETE, wfrn));
        }
        return access.decide(session, checks).map(decisions -> {
            for (int i = 0; i < budgets.size(); i++) {
                ObjectNode can = budgets.get(i).putObject("can");
                can.put("update", decisions.get(2 * i).allowed());
                can.put("delete", decisions.get(2 * i + 1).allowed());
            }
            return answer.withJson(write(root));
        });
    }

    private String budgetName(Session session, String budgetId) {
        if (CostNames.isBudgetId(budgetId)) {
            return CostNames.budget(platform.partition(), session.accountId(), budgetId);
        }
        // an id of another shape is decided on every budget of the account
        return CostNames.budgetsPattern(platform.partition(), session.accountId());
    }

    // ---- plumbing

    private Mono<Relayed> person(Session session, RelayCall call) {
        return cost.callAsPerson(CurrentSession.scope(session), caller(session), call);
    }

    private String costPattern(String accountId) {
        return CostNames.costPattern(platform.partition(), accountId);
    }

    static StateCaller caller(Session session) {
        return new StateCaller(session.user().userId(), session.currentAccount().groups(), session.currentAccount().roles());
    }

    private ObjectNode object(byte[] body) {
        JsonNode node = body == null || body.length == 0 ? null : parse(body);
        if (!(node instanceof ObjectNode o)) {
            throw invalid("type", "", "the body is a JSON object");
        }
        return o;
    }

    private JsonNode parse(byte[] body) {
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

    private static InvalidInputException invalid(String code, String field, String message) {
        return new InvalidInputException(message, List.of(Problem.error(code, field, message)));
    }

    /** Query parameters as the browser sent them, in order (a name may repeat). */
    public static List<Map.Entry<String, String>> params(Map<String, List<String>> query) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        new LinkedHashMap<>(query).forEach((name, values) -> values.forEach(v -> out.add(Map.entry(name, v == null ? "" : v))));
        return out;
    }
}
