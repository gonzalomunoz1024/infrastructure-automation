package com.iaf.console.bff.demo.identity.adapters.inbound;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.PATCH;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.ManagedRoles;
import com.iaf.console.bff.demo.directory.application.DemoDirectory;
import com.iaf.console.bff.demo.directory.application.DemoGovernance;
import com.iaf.console.bff.demo.directory.domain.DemoElevation;
import com.iaf.console.bff.demo.directory.domain.DemoPerson;
import com.iaf.console.bff.demo.identity.application.DemoCaller;
import com.iaf.console.bff.demo.identity.application.DemoTokens;
import com.iaf.console.bff.identity.application.IdentityConfiguration;
import com.iaf.console.bff.identity.application.IdentityProperties;
import com.iaf.console.bff.session.domain.RoleConventions;
import com.iaf.console.wfrn.Ulid;

import reactor.core.publisher.Mono;

/**
 * The identity service's phase 7 and 9 operations, served in process for the demo under
 * {@code /demo-identity} (decision 34): the organization's guardrails (the internal principal
 * only) and the ones of an account, the policy simulator (over the managed roles and the
 * guardrails; unsaved policies are not laid over in the demo), access reviews, elevations and
 * their rules, break-glass access and closing an account (the internal principal only), and the
 * quota rules. Refusals carry the identity service's Denial, its {@code scope} naming what refused.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoGovernanceRoutes {

    private static final String PREFIX = IdentityConfiguration.DEMO_PATH;
    private static final int BREAK_GLASS_MAX_MINUTES = 240;
    private static final int DEFAULT_MINUTES = 60;
    private static final int MAX_MINUTES = 480;
    /** The demo's one rule: a viewer may hold operator for up to two hours at once. */
    private static final Map<String, Object> RULE = Map.of("name", "viewer-to-operator", "roles", List.of(ManagedRoles.OPERATOR), "requesterRoles",
            List.of(ManagedRoles.VIEWER), "maxMinutes", 120);

    private final DemoDirectory directory;
    private final DemoGovernance governance;
    private final DemoTokens tokens;
    private final ObjectProvider<Access> access;
    private final ObjectMapper json;
    /** The audience every token sent here must name (decision 35): the identity service's. */
    private final String audience;

    public DemoGovernanceRoutes(DemoDirectory directory, DemoGovernance governance, DemoTokens tokens, ObjectProvider<Access> access, ObjectMapper json,
            IdentityProperties identity) {
        this.audience = identity.serviceName();
        this.directory = directory;
        this.governance = governance;
        this.tokens = tokens;
        this.access = access;
        this.json = json;
    }

    @Bean
    public RouterFunction<ServerResponse> demoGovernanceRouter() {
        String a = PREFIX + "/v1/accounts/{accountId}";
        return route(GET(PREFIX + "/v1/guardrails"), r -> internal(r, this::listGuardrails))
                .andRoute(POST(PREFIX + "/v1/guardrails"), r -> internal(r, this::createGuardrail))
                .andRoute(GET(PREFIX + "/v1/guardrails/{guardrailId}"), r -> internal(r, this::getGuardrail))
                .andRoute(PATCH(PREFIX + "/v1/guardrails/{guardrailId}"), r -> internal(r, this::updateGuardrail))
                .andRoute(DELETE(PREFIX + "/v1/guardrails/{guardrailId}"), r -> internal(r, this::deleteGuardrail))
                .andRoute(POST(a + ":close"), r -> internal(r, this::closeAccount))
                .andRoute(POST(a + "/break-glass"), r -> internal(r, this::breakGlass))
                .andRoute(GET(PREFIX + "/v1/quota-rules"), r -> signedIn(r, this::quotaRules))
                .andRoute(GET(PREFIX + "/v1/elevation-rules"), r -> signedIn(r, this::elevationRules))
                .andRoute(GET(a + "/guardrails"), r -> member(r, this::accountGuardrails))
                .andRoute(POST(a + "/policy-simulations"), r -> member(r, this::simulate))
                .andRoute(GET(a + "/access-reviews"), r -> member(r, this::listReviews))
                .andRoute(POST(a + "/access-reviews"), r -> member(r, this::startReview))
                .andRoute(GET(a + "/access-reviews/{reviewId}"), r -> member(r, this::getReview))
                .andRoute(GET(a + "/access-reviews/{reviewId}/items"), r -> member(r, this::listItems))
                .andRoute(POST(a + "/access-reviews/{reviewId}/items/{itemId}:decide"), r -> member(r, this::decideItem))
                .andRoute(POST(a + "/access-reviews/{reviewId}:cancel"), r -> member(r, this::cancelReview))
                .andRoute(GET(a + "/elevations"), r -> member(r, this::listElevations))
                .andRoute(POST(a + "/elevations"), r -> member(r, this::requestElevation))
                .andRoute(GET(a + "/elevations/{elevationId}"), r -> member(r, this::getElevation))
                .andRoute(POST(a + "/elevations/{elevationId}:decide"), r -> member(r, this::decideElevation))
                .andRoute(POST(a + "/elevations/{elevationId}:revoke"), r -> member(r, this::revokeElevation));
    }

    // ---- who calls

    private interface Handler {
        Mono<ServerResponse> handle(ServerRequest request, DemoCaller caller);
    }

    private interface Scoped {
        Mono<ServerResponse> handle(ServerRequest request, DemoCaller caller, String accountId);
    }

    private Mono<ServerResponse> signedIn(ServerRequest request, Handler handler) {
        Optional<DemoCaller> caller = tokens.verify(request.headers().firstHeader(HttpHeaders.AUTHORIZATION), audience);
        return caller.isEmpty() ? error(HttpStatus.UNAUTHORIZED, "invalid or missing token") : handler.handle(request, caller.get());
    }

    /** The console-internal principal only: anyone else gets a 404. */
    private Mono<ServerResponse> internal(ServerRequest request, Handler handler) {
        return signedIn(request, (r, caller) -> caller.internal() ? handler.handle(r, caller) : error(HttpStatus.NOT_FOUND, "not found"));
    }

    /** Someone acting in the account (a member, or holding a role there for a while), or the internal principal. */
    private Mono<ServerResponse> member(ServerRequest request, Scoped handler) {
        return signedIn(request, (r, caller) -> {
            String accountId = r.pathVariable("accountId");
            if (directory.account(accountId).isEmpty() || directory.isClosed(accountId)
                    || !caller.internal() && (!accountId.equals(caller.accountId()) || !directory.inAccount(caller.personSubject(), accountId))) {
                return error(HttpStatus.NOT_FOUND, "account " + accountId + " not found");
            }
            return handler.handle(r, caller, accountId);
        });
    }

    private boolean allows(DemoCaller caller, String accountId, String action) {
        return caller.internal() || ManagedRoles.anyAllows(directory.rolesOf(caller.personSubject(), accountId), action);
    }

    /** The identity service's 403: a Denial whose scope names the policy that refused it. */
    private Mono<ServerResponse> denied(DemoCaller caller, String accountId, String action, String resource, String reason, boolean requestable) {
        String role = RoleConventions.strongest(directory.rolesOf(caller.personSubject(), accountId));
        Map<String, Object> denial = new LinkedHashMap<>();
        denial.put("layer", "console-iam");
        denial.put("action", action);
        denial.put("resource", resource);
        denial.put("reason", reason == null ? "No statement allows " + action + " for role " + role : reason);
        denial.put("role", role);
        denial.put("policyId", "managed/" + role);
        denial.put("statement", ManagedRoles.statementFor(role, action));
        denial.put("scope", "policy");
        denial.put("requestable", requestable);
        if (requestable) {
            denial.put("suggestedRole", ManagedRoles.suggestedFor(action));
        }
        return respond(HttpStatus.FORBIDDEN, Map.of("error", denial.get("reason"), "denied", denial));
    }

    private Mono<ServerResponse> refuseUnless(DemoCaller caller, String accountId, String action, String resource, Mono<ServerResponse> then) {
        return allows(caller, accountId, action) ? then : denied(caller, accountId, action, resource, null, true);
    }

    // ---- guardrails

    private Mono<ServerResponse> listGuardrails(ServerRequest request, DemoCaller caller) {
        return respond(HttpStatus.OK, page(governance.guardrails().stream().map(DemoGovernanceRoutes::guardrail).toList()));
    }

    private Mono<ServerResponse> getGuardrail(ServerRequest request, DemoCaller caller) {
        return governance.guardrail(request.pathVariable("guardrailId")).map(g -> respond(HttpStatus.OK, guardrail(g)))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "guardrail " + request.pathVariable("guardrailId") + " not found"));
    }

    private Mono<ServerResponse> createGuardrail(ServerRequest request, DemoCaller caller) {
        return request.bodyToMono(JsonNode.class).defaultIfEmpty(json.createObjectNode()).flatMap(body -> {
            String name = body.path("name").asText("");
            Optional<String> problem = guardrailProblem(name, body.path("statements"), body.path("accounts"), true);
            if (problem.isPresent()) {
                return error(HttpStatus.BAD_REQUEST, problem.get());
            }
            if (governance.guardrailNamed(name).isPresent()) {
                return error(HttpStatus.CONFLICT, "a guardrail named " + name + " exists");
            }
            DemoGovernance.Guardrail saved = governance.saveGuardrail(null, name, body.path("description").asText(null), statements(body.path("statements")),
                    strings(body.path("accounts")));
            invalidateAll();
            return respond(HttpStatus.CREATED, guardrail(saved));
        });
    }

    private Mono<ServerResponse> updateGuardrail(ServerRequest request, DemoCaller caller) {
        Optional<DemoGovernance.Guardrail> existing = governance.guardrail(request.pathVariable("guardrailId"));
        if (existing.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "guardrail " + request.pathVariable("guardrailId") + " not found");
        }
        String ifMatch = request.headers().firstHeader(HttpHeaders.IF_MATCH);
        if (ifMatch != null && !ifMatch.replace("\"", "").equals(String.valueOf(existing.get().version()))) {
            return respond(HttpStatus.CONFLICT, Map.of("error", "the guardrail moved on", "detail", Map.of("version", existing.get().version())));
        }
        return request.bodyToMono(JsonNode.class).defaultIfEmpty(json.createObjectNode()).flatMap(body -> {
            DemoGovernance.Guardrail g = existing.get();
            String name = body.has("name") ? body.path("name").asText("") : g.name();
            Optional<String> problem = guardrailProblem(name, body.path("statements"), body.path("accounts"), false);
            if (problem.isPresent()) {
                return error(HttpStatus.BAD_REQUEST, problem.get());
            }
            DemoGovernance.Guardrail saved = governance.saveGuardrail(g.guardrailId(), name,
                    body.has("description") ? body.path("description").asText(null) : g.description(),
                    body.has("statements") ? statements(body.path("statements")) : g.statements(),
                    body.has("accounts") ? strings(body.path("accounts")) : g.accounts());
            invalidateAll();
            return respond(HttpStatus.OK, guardrail(saved));
        });
    }

    private Mono<ServerResponse> deleteGuardrail(ServerRequest request, DemoCaller caller) {
        if (!governance.deleteGuardrail(request.pathVariable("guardrailId"))) {
            return error(HttpStatus.NOT_FOUND, "guardrail " + request.pathVariable("guardrailId") + " not found");
        }
        invalidateAll();
        return ServerResponse.noContent().build();
    }

    private Optional<String> guardrailProblem(String name, JsonNode statements, JsonNode accounts, boolean create) {
        if (!name.matches("^[a-z0-9-]{2,64}$")) {
            return Optional.of("name is 2 to 64 lowercase letters, digits or dashes");
        }
        if ((create || !statements.isMissingNode()) && (!statements.isArray() || statements.isEmpty() || statements.size() > 200)) {
            return Optional.of("statements are 1 to 200 policy statements");
        }
        for (JsonNode s : statements) {
            String effect = s.path("effect").asText("");
            if ((!"allow".equals(effect) && !"deny".equals(effect)) || !s.path("actions").isArray() || !s.path("resources").isArray()) {
                return Optional.of("a statement has an effect (allow or deny), actions and resources");
            }
        }
        if ((create || !accounts.isMissingNode()) && (!accounts.isArray() || accounts.isEmpty())) {
            return Optional.of("accounts is [\"*\"] or account ids");
        }
        for (JsonNode a : accounts) {
            if (!"*".equals(a.asText()) && directory.account(a.asText()).isEmpty()) {
                return Optional.of("no account " + a.asText());
            }
        }
        return Optional.empty();
    }

    private Mono<ServerResponse> accountGuardrails(ServerRequest request, DemoCaller caller, String accountId) {
        return refuseUnless(caller, accountId, "iam:list", "wfrn:wf:iam::" + accountId + ":guardrail/*",
                respond(HttpStatus.OK, Map.of("items", governance.guardrailsOf(accountId).stream().map(DemoGovernanceRoutes::guardrail).toList())));
    }

    // ---- the simulator

    private Mono<ServerResponse> simulate(ServerRequest request, DemoCaller caller, String accountId) {
        return request.bodyToMono(JsonNode.class).defaultIfEmpty(json.createObjectNode()).flatMap(body -> {
            JsonNode principal = body.path("principal");
            JsonNode checks = body.path("checks");
            if (!checks.isArray() || checks.isEmpty() || checks.size() > 100) {
                return error(HttpStatus.BAD_REQUEST, "checks are 1 to 100 actions and resources");
            }
            List<String> roles;
            String action;
            String resource;
            if (principal.hasNonNull("userId")) {
                Optional<DemoPerson> person = directory.byUserId(principal.path("userId").asText());
                if (person.isEmpty() || !directory.inAccount(person.get().subject(), accountId)) {
                    return error(HttpStatus.NOT_FOUND, "user " + principal.path("userId").asText() + " not found");
                }
                roles = directory.rolesOf(person.get().subject(), accountId);
                action = "iam:get";
                resource = "wfrn:wf:iam::" + accountId + ":user/" + person.get().userId();
            } else if (principal.hasNonNull("role")) {
                String role = normalRole(principal.path("role").asText());
                if (!ManagedRoles.isManaged(role)) {
                    return error(HttpStatus.NOT_FOUND, "role " + role + " not found");
                }
                roles = List.of(role);
                action = "iam:get";
                resource = "wfrn:wf:iam::" + accountId + ":role/" + role;
            } else if (principal.path("groups").isArray()) {
                roles = strings(principal.path("groups")).stream().flatMap(g -> ManagedRoles.ALL.stream().filter(r -> ManagedRoles.groupOf(r).equals(g)))
                        .distinct().toList();
                action = "iam:list";
                resource = "wfrn:wf:iam::" + accountId + ":group/*";
            } else {
                return error(HttpStatus.BAD_REQUEST, "the principal is a userId, a role or groups");
            }
            if (!allows(caller, accountId, action)) {
                return denied(caller, accountId, action, resource, null, true);
            }
            String strongest = roles.isEmpty() ? "none" : RoleConventions.strongest(roles);
            List<Map<String, Object>> results = new ArrayList<>();
            for (JsonNode check : checks) {
                String checked = check.path("action").asText("");
                String on = check.path("resource").asText("");
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("action", checked);
                d.put("resource", on);
                boolean allowed = ManagedRoles.anyAllows(roles, checked);
                Optional<DemoGovernance.Refusal> refusal = allowed ? governance.refusal(accountId, checked, on) : Optional.empty();
                if (refusal.isPresent()) {
                    d.put("allowed", false);
                    d.put("reason", "The organization's guardrail " + refusal.get().guardrail().name() + " refuses " + checked);
                    d.put("scope", "guardrail");
                    d.put("guardrailId", refusal.get().guardrail().guardrailId());
                    d.put("requestable", false);
                } else if (allowed) {
                    d.put("allowed", true);
                    d.put("role", roles.stream().filter(r -> ManagedRoles.allows(r, checked)).findFirst().orElse(strongest));
                    d.put("scope", "policy");
                } else {
                    d.put("allowed", false);
                    d.put("reason", "No statement allows " + checked + " for role " + strongest);
                    d.put("role", strongest);
                    d.put("scope", "policy");
                    d.put("requestable", true);
                    d.put("suggestedRole", ManagedRoles.suggestedFor(checked));
                }
                results.add(d);
            }
            return respond(HttpStatus.OK, Map.of("results", results, "version", directory.version(accountId)));
        });
    }

    // ---- access reviews

    private static String reviewWfrn(String accountId, String reviewId) {
        return "wfrn:wf:iam::" + accountId + ":access-review/" + reviewId;
    }

    private Mono<ServerResponse> listReviews(ServerRequest request, DemoCaller caller, String accountId) {
        return refuseUnless(caller, accountId, "access-review:list", reviewWfrn(accountId, "*"),
                Mono.defer(() -> respond(HttpStatus.OK, page(governance.reviews(accountId).stream().map(this::review).toList()))));
    }

    private Mono<ServerResponse> startReview(ServerRequest request, DemoCaller caller, String accountId) {
        if (!allows(caller, accountId, "access-review:create")) {
            return denied(caller, accountId, "access-review:create", reviewWfrn(accountId, "*"), null, true);
        }
        return request.bodyToMono(JsonNode.class).defaultIfEmpty(json.createObjectNode()).flatMap(body -> {
            int due = body.path("dueInDays").asInt(14);
            String name = body.path("name").asText(null);
            if (due < 1 || due > 90 || name != null && name.length() > 200) {
                return error(HttpStatus.BAD_REQUEST, "dueInDays is 1 to 90, name at most 200 characters");
            }
            Optional<DemoGovernance.Review> open = governance.openReview(accountId);
            if (open.isPresent()) {
                return respond(HttpStatus.CONFLICT, Map.of("error", "the account has an open access review", "detail",
                        Map.of("reviewId", open.get().reviewId())));
            }
            return respond(HttpStatus.CREATED, review(governance.startReview(accountId, name, due, caller.subject())));
        });
    }

    private Mono<ServerResponse> getReview(ServerRequest request, DemoCaller caller, String accountId) {
        String reviewId = request.pathVariable("reviewId");
        return refuseUnless(caller, accountId, "access-review:get", reviewWfrn(accountId, reviewId),
                Mono.defer(() -> governance.review(accountId, reviewId).map(r -> respond(HttpStatus.OK, review(r)))
                        .orElseGet(() -> error(HttpStatus.NOT_FOUND, "access review " + reviewId + " not found"))));
    }

    private Mono<ServerResponse> listItems(ServerRequest request, DemoCaller caller, String accountId) {
        String reviewId = request.pathVariable("reviewId");
        String decision = request.queryParam("decision").filter(d -> !d.isBlank()).orElse(null);
        if (decision != null && !List.of("pending", "keep", "revoke", "expired").contains(decision)) {
            return error(HttpStatus.BAD_REQUEST, "decision is pending, keep, revoke or expired");
        }
        return refuseUnless(caller, accountId, "access-review:get", reviewWfrn(accountId, reviewId),
                Mono.defer(() -> governance.review(accountId, reviewId)
                        .map(r -> respond(HttpStatus.OK, page(governance.items(reviewId, decision).stream().map(DemoGovernanceRoutes::item).toList())))
                        .orElseGet(() -> error(HttpStatus.NOT_FOUND, "access review " + reviewId + " not found"))));
    }

    private Mono<ServerResponse> decideItem(ServerRequest request, DemoCaller caller, String accountId) {
        String reviewId = request.pathVariable("reviewId");
        String itemId = request.pathVariable("itemId");
        if (!allows(caller, accountId, "access-review:decide")) {
            return denied(caller, accountId, "access-review:decide", reviewWfrn(accountId, reviewId), null, true);
        }
        return request.bodyToMono(JsonNode.class).defaultIfEmpty(json.createObjectNode()).flatMap(body -> {
            String decision = body.path("decision").asText("");
            String comment = body.hasNonNull("comment") ? body.path("comment").asText() : null;
            if (!"keep".equals(decision) && !"revoke".equals(decision) || comment != null && comment.length() > 2000) {
                return error(HttpStatus.BAD_REQUEST, "decision is keep or revoke, comment at most 2000 characters");
            }
            Optional<DemoGovernance.Review> review = governance.review(accountId, reviewId);
            Optional<DemoGovernance.Item> item = governance.item(reviewId, itemId);
            if (review.isEmpty() || item.isEmpty()) {
                return error(HttpStatus.NOT_FOUND, "access review item " + itemId + " not found");
            }
            if (!caller.internal() && caller.subject().equals(item.get().userId())) {
                return denied(caller, accountId, "access-review:decide", reviewWfrn(accountId, reviewId), "nobody reviews their own access", false);
            }
            if (!"open".equals(review.get().status()) || !"pending".equals(item.get().decision())) {
                return error(HttpStatus.CONFLICT, "the campaign is " + review.get().status() + " and the item " + item.get().decision());
            }
            if ("revoke".equals(decision) && "owners".equals(item.get().groupName()) && governance.lastOwner(accountId, item.get().userId())) {
                return error(HttpStatus.CONFLICT, "the account's last owner keeps the owners group");
            }
            DemoGovernance.Item decided = governance.decide(review.get(), item.get(), decision, caller.subject(), comment);
            if ("revoke".equals(decision)) {
                invalidate(accountId);
            }
            return respond(HttpStatus.OK, item(decided));
        });
    }

    private Mono<ServerResponse> cancelReview(ServerRequest request, DemoCaller caller, String accountId) {
        String reviewId = request.pathVariable("reviewId");
        if (!allows(caller, accountId, "access-review:cancel")) {
            return denied(caller, accountId, "access-review:cancel", reviewWfrn(accountId, reviewId), null, true);
        }
        Optional<DemoGovernance.Review> review = governance.review(accountId, reviewId);
        if (review.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "access review " + reviewId + " not found");
        }
        if (!"open".equals(review.get().status())) {
            return error(HttpStatus.CONFLICT, "the campaign is " + review.get().status());
        }
        return respond(HttpStatus.OK, review(governance.cancel(review.get())));
    }

    // ---- elevations

    private static String elevationWfrn(String accountId, String elevationId) {
        return "wfrn:wf:iam::" + accountId + ":elevation/" + elevationId;
    }

    private Mono<ServerResponse> elevationRules(ServerRequest request, DemoCaller caller) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("defaultMinutes", DEFAULT_MINUTES);
        rules.put("maxMinutes", MAX_MINUTES);
        rules.put("pendingFor", "PT24H");
        rules.put("breakGlassMaxMinutes", BREAK_GLASS_MAX_MINUTES);
        rules.put("items", List.of(RULE));
        return respond(HttpStatus.OK, rules);
    }

    private Mono<ServerResponse> quotaRules(ServerRequest request, DemoCaller caller) {
        return respond(HttpStatus.OK, Map.of("items", List.of(Map.of("name", "small-increases", "keys", List.of("resources.*", "access-keys"),
                "maxFactor", 2, "maxLimit", 500, "cooldown", "PT720H")), "approver", "platform-team"));
    }

    private Mono<ServerResponse> listElevations(ServerRequest request, DemoCaller caller, String accountId) {
        boolean all = allows(caller, accountId, "elevation:list");
        String status = request.queryParam("status").filter(s -> !s.isBlank()).orElse(null);
        String kind = request.queryParam("kind").filter(s -> !s.isBlank()).orElse(null);
        String userId = request.queryParam("userId").filter(s -> !s.isBlank()).orElse(null);
        List<Map<String, Object>> items = directory.elevations(accountId).stream()
                .filter(e -> all || e.userId().equals(caller.subject()))
                .filter(e -> status == null || status.equals(e.status()))
                .filter(e -> kind == null || kind.equals(e.kind()))
                .filter(e -> userId == null || userId.equals(e.userId()))
                .map(DemoGovernanceRoutes::elevation).toList();
        return respond(HttpStatus.OK, page(items));
    }

    private Mono<ServerResponse> getElevation(ServerRequest request, DemoCaller caller, String accountId) {
        String elevationId = request.pathVariable("elevationId");
        Optional<DemoElevation> e = directory.elevation(elevationId).filter(x -> x.accountId().equals(accountId));
        if (e.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "elevation " + elevationId + " not found");
        }
        if (!e.get().userId().equals(caller.subject()) && !allows(caller, accountId, "elevation:get")) {
            return denied(caller, accountId, "elevation:get", elevationWfrn(accountId, elevationId), null, true);
        }
        return respond(HttpStatus.OK, elevation(e.get()));
    }

    private Mono<ServerResponse> requestElevation(ServerRequest request, DemoCaller caller, String accountId) {
        if (caller.internal()) {
            return error(HttpStatus.BAD_REQUEST, "a person asks for an elevation");
        }
        if (!allows(caller, accountId, "elevation:create")) {
            return denied(caller, accountId, "elevation:create", elevationWfrn(accountId, "*"), null, true);
        }
        return request.bodyToMono(JsonNode.class).defaultIfEmpty(json.createObjectNode()).flatMap(body -> {
            String role = normalRole(body.path("role").asText(""));
            String reason = body.path("reason").asText("");
            int minutes = body.path("durationMinutes").asInt(DEFAULT_MINUTES);
            if (!ManagedRoles.isManaged(role) || reason.isBlank() || reason.length() > 2000 || minutes < 5 || minutes > MAX_MINUTES) {
                return error(HttpStatus.BAD_REQUEST, "role is a role of the account, reason 1 to 2000 characters, durationMinutes 5 to " + MAX_MINUTES);
            }
            DemoPerson person = directory.byUserId(caller.subject()).orElse(null);
            if (person == null) {
                return error(HttpStatus.NOT_FOUND, "user not found");
            }
            List<String> groupRoles = directory.groupRolesOf(person.subject(), accountId);
            if (groupRoles.contains(role)) {
                return error(HttpStatus.BAD_REQUEST, "you hold " + role + " through a group already");
            }
            Optional<DemoElevation> open = directory.openElevation(accountId, person.userId(), role, DemoElevation.ELEVATION);
            if (open.isPresent()) {
                return respond(HttpStatus.CONFLICT, elevation(open.get()));
            }
            Instant now = directory.now();
            List<String> approvers = directory.members(accountId).stream().filter(m -> m.getValue().roles().contains(ManagedRoles.ADMIN))
                    .map(m -> m.getKey().userId()).filter(id -> !id.equals(person.userId())).sorted().toList();
            DemoElevation asked = new DemoElevation("elev_" + Ulid.next().toLowerCase(), accountId, DemoElevation.ELEVATION, person.userId(), person.email(),
                    person.name(), role, reason, null, minutes, "pending", now, person.userId(), null, null, null, null, null, null, null, null, approvers);
            boolean ruled = ((List<?>) RULE.get("roles")).contains(role) && groupRoles.stream().anyMatch(((List<?>) RULE.get("requesterRoles"))::contains)
                    && minutes <= (Integer) RULE.get("maxMinutes");
            DemoElevation saved = directory.saveElevation(ruled ? asked.approved("rule:" + RULE.get("name"), null, now) : asked);
            if (ruled) {
                invalidate(accountId);
            }
            return respond(HttpStatus.CREATED, elevation(saved));
        });
    }

    private Mono<ServerResponse> decideElevation(ServerRequest request, DemoCaller caller, String accountId) {
        String elevationId = request.pathVariable("elevationId");
        Optional<DemoElevation> asked = directory.elevation(elevationId).filter(x -> x.accountId().equals(accountId));
        if (asked.isPresent() && !caller.internal() && asked.get().userId().equals(caller.subject())) {
            return denied(caller, accountId, "elevation:decide", elevationWfrn(accountId, elevationId), "nobody decides their own elevation", false);
        }
        if (!allows(caller, accountId, "elevation:decide")) {
            return denied(caller, accountId, "elevation:decide", elevationWfrn(accountId, elevationId), null, true);
        }
        return request.bodyToMono(JsonNode.class).defaultIfEmpty(json.createObjectNode()).flatMap(body -> {
            String decision = body.path("decision").asText("");
            String comment = body.hasNonNull("comment") ? body.path("comment").asText() : null;
            if (!"approve".equals(decision) && !"reject".equals(decision) || comment != null && comment.length() > 2000) {
                return error(HttpStatus.BAD_REQUEST, "decision is approve or reject, comment at most 2000 characters");
            }
            Optional<DemoElevation> e = directory.elevation(elevationId).filter(x -> x.accountId().equals(accountId));
            if (e.isEmpty()) {
                return error(HttpStatus.NOT_FOUND, "elevation " + elevationId + " not found");
            }
            if (!"pending".equals(e.get().status())) {
                return error(HttpStatus.CONFLICT, "the elevation is " + e.get().status());
            }
            Instant now = directory.now();
            DemoElevation decided = directory.saveElevation("approve".equals(decision) ? e.get().approved(caller.subject(), comment, now)
                    : e.get().rejected(caller.subject(), comment, now));
            invalidate(accountId);
            return respond(HttpStatus.OK, elevation(decided));
        });
    }

    private Mono<ServerResponse> revokeElevation(ServerRequest request, DemoCaller caller, String accountId) {
        String elevationId = request.pathVariable("elevationId");
        Optional<DemoElevation> e = directory.elevation(elevationId).filter(x -> x.accountId().equals(accountId));
        if (e.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "elevation " + elevationId + " not found");
        }
        if (!e.get().userId().equals(caller.subject()) && !allows(caller, accountId, "elevation:revoke")) {
            return denied(caller, accountId, "elevation:revoke", elevationWfrn(accountId, elevationId), null, true);
        }
        return request.bodyToMono(JsonNode.class).defaultIfEmpty(json.createObjectNode()).flatMap(body -> {
            if (!e.get().open(directory.now())) {
                return error(HttpStatus.CONFLICT, "the elevation is " + e.get().status());
            }
            String by = caller.internal() ? "svc:" + caller.service() : caller.subject();
            DemoElevation ended = directory.saveElevation(e.get().ended(by, body.path("comment").asText(null), directory.now()));
            invalidate(accountId);
            return respond(HttpStatus.OK, elevation(ended));
        });
    }

    // ---- the internal principal's account operations

    private Mono<ServerResponse> breakGlass(ServerRequest request, DemoCaller caller) {
        String accountId = request.pathVariable("accountId");
        if (directory.account(accountId).isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "account " + accountId + " not found");
        }
        return request.bodyToMono(JsonNode.class).defaultIfEmpty(json.createObjectNode()).flatMap(body -> {
            String userId = body.path("userId").asText("");
            String approvedBy = body.path("approvedBy").asText("");
            String role = body.hasNonNull("role") ? normalRole(body.path("role").asText()) : ManagedRoles.ADMIN;
            String reason = body.path("reason").asText("");
            int minutes = body.path("durationMinutes").asInt(DEFAULT_MINUTES);
            Optional<DemoPerson> person = directory.byUserId(userId);
            if (person.isEmpty()) {
                return error(HttpStatus.NOT_FOUND, "user " + userId + " not found");
            }
            if (approvedBy.isBlank() || approvedBy.equals(userId) || directory.byUserId(approvedBy).isEmpty()) {
                return error(HttpStatus.BAD_REQUEST, "approvedBy is a console user other than the person given access");
            }
            if (!ManagedRoles.isManaged(role) || reason.isBlank() || reason.length() > 2000 || minutes < 5 || minutes > BREAK_GLASS_MAX_MINUTES) {
                return error(HttpStatus.BAD_REQUEST, "role is a role of the account, reason 1 to 2000 characters, durationMinutes 5 to "
                        + BREAK_GLASS_MAX_MINUTES);
            }
            if (directory.isClosed(accountId)) {
                return error(HttpStatus.CONFLICT, "account " + accountId + " is closed");
            }
            Optional<DemoElevation> open = directory.openElevation(accountId, userId, role, DemoElevation.BREAK_GLASS);
            if (open.isPresent()) {
                return respond(HttpStatus.CONFLICT, elevation(open.get()));
            }
            Instant now = directory.now();
            DemoPerson p = person.get();
            DemoElevation given = new DemoElevation("elev_" + Ulid.next().toLowerCase(), accountId, DemoElevation.BREAK_GLASS, userId, p.email(), p.name(),
                    role, reason, body.path("ticket").asText(null), minutes, "pending", now, "svc:" + caller.service(), null, null, null, null, null, null,
                    null, null, List.of()).approved(approvedBy, null, now);
            directory.saveElevation(given);
            invalidate(accountId);
            return respond(HttpStatus.CREATED, elevation(given));
        });
    }

    private Mono<ServerResponse> closeAccount(ServerRequest request, DemoCaller caller) {
        String accountId = request.pathVariable("accountId");
        if (directory.account(accountId).isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "account " + accountId + " not found");
        }
        return request.bodyToMono(JsonNode.class).defaultIfEmpty(json.createObjectNode()).flatMap(body -> {
            String reason = body.path("reason").asText("");
            if (reason.isBlank() || reason.length() > 2000) {
                return error(HttpStatus.BAD_REQUEST, "reason is 1 to 2000 characters");
            }
            if (!directory.close(accountId, reason)) {
                return error(HttpStatus.CONFLICT, "account " + accountId + " is closed already");
            }
            governance.openReview(accountId).ifPresent(governance::cancel);
            invalidate(accountId);
            var account = directory.account(accountId).orElseThrow();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", accountId);
            m.put("name", account.name());
            m.put("status", "closed");
            m.put("owningTeam", account.owningTeam());
            m.put("defaultRegion", account.defaultRegion());
            m.put("version", 2);
            m.put("createdAt", Instant.parse("2026-10-23T00:00:00Z"));
            directory.closure(accountId).ifPresent(c -> {
                m.put("closedAt", c.at());
                m.put("closeReason", c.reason());
            });
            return respond(HttpStatus.ACCEPTED, m);
        });
    }

    // ---- shapes and helpers

    private static String normalRole(String role) {
        String r = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        return "admin".equals(r) ? ManagedRoles.ADMIN : r;
    }

    private static Map<String, Object> guardrail(DemoGovernance.Guardrail g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("guardrailId", g.guardrailId());
        m.put("name", g.name());
        if (g.description() != null) {
            m.put("description", g.description());
        }
        m.put("statements", g.statements());
        m.put("accounts", g.accounts());
        m.put("version", g.version());
        m.put("createdAt", g.createdAt());
        m.put("updatedAt", g.updatedAt());
        return m;
    }

    private Map<String, Object> review(DemoGovernance.Review r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reviewId", r.reviewId());
        m.put("name", r.name());
        m.put("status", r.status());
        m.put("trigger", r.trigger());
        m.put("startedBy", r.startedBy());
        m.put("startedAt", r.startedAt());
        m.put("dueAt", r.dueAt());
        if (r.closedAt() != null) {
            m.put("closedAt", r.closedAt());
        }
        m.put("counts", governance.counts(r.reviewId()));
        m.put("version", r.version());
        return m;
    }

    private static Map<String, Object> item(DemoGovernance.Item i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", i.itemId());
        m.put("reviewId", i.reviewId());
        m.put("userId", i.userId());
        m.put("email", i.email());
        m.put("name", i.name());
        m.put("groupId", i.groupId());
        m.put("groupName", i.groupName());
        m.put("roles", i.roles());
        m.put("decision", i.decision());
        if (i.decidedBy() != null) {
            m.put("decidedBy", i.decidedBy());
            m.put("decidedAt", i.decidedAt());
        }
        if (i.comment() != null) {
            m.put("comment", i.comment());
        }
        return m;
    }

    private static Map<String, Object> elevation(DemoElevation e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("elevationId", e.elevationId());
        m.put("accountId", e.accountId());
        m.put("kind", e.kind());
        m.put("user", Map.of("userId", e.userId(), "email", e.email(), "name", e.name()));
        m.put("role", e.role());
        m.put("reason", e.reason());
        if (e.ticket() != null) {
            m.put("ticket", e.ticket());
        }
        m.put("durationMinutes", e.durationMinutes());
        m.put("status", e.status());
        m.put("requestedAt", e.requestedAt());
        m.put("requestedBy", e.requestedBy());
        putIf(m, "decidedAt", e.decidedAt());
        putIf(m, "decidedBy", e.decidedBy());
        putIf(m, "comment", e.comment());
        putIf(m, "startsAt", e.startsAt());
        putIf(m, "until", e.until());
        putIf(m, "endedAt", e.endedAt());
        putIf(m, "endedBy", e.endedBy());
        putIf(m, "endComment", e.endComment());
        if ("pending".equals(e.status())) {
            m.put("approvers", e.approvers());
        }
        return m;
    }

    private static void putIf(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private List<Map<String, Object>> statements(JsonNode array) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode s : array) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = json.convertValue(s, Map.class);
            out.add(m);
        }
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static Map<String, Object> page(List<Map<String, Object>> items) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("next", null);
        return page;
    }

    private void invalidate(String accountId) {
        Access a = access.getIfAvailable();
        if (a != null) {
            a.invalidate(accountId);
        }
    }

    private void invalidateAll() {
        Access a = access.getIfAvailable();
        if (a != null) {
            a.invalidateAll();
        }
    }

    private static Mono<ServerResponse> respond(HttpStatus status, Object body) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private static Mono<ServerResponse> error(HttpStatus status, String message) {
        return respond(status, Map.of("error", message));
    }
}
