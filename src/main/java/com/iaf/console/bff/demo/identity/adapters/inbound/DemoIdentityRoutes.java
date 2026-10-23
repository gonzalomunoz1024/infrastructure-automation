package com.iaf.console.bff.demo.identity.adapters.inbound;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.PATCH;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.access.domain.ManagedRoles;
import com.iaf.console.bff.demo.directory.application.DemoDirectory;
import com.iaf.console.bff.demo.directory.domain.AccessRequest;
import com.iaf.console.bff.demo.directory.domain.DemoAccount;
import com.iaf.console.bff.demo.directory.domain.DemoMembership;
import com.iaf.console.bff.demo.directory.domain.DemoPerson;
import com.iaf.console.bff.demo.identity.application.DemoCaller;
import com.iaf.console.bff.demo.identity.application.DemoTokens;
import com.iaf.console.bff.identity.application.IdentityConfiguration;
import com.iaf.console.bff.identity.application.IdentityProperties;
import com.iaf.console.bff.platform.domain.DemoPlatformSeed;
import com.iaf.console.bff.session.domain.RoleConventions;
import com.iaf.console.wfrn.Ulid;

import reactor.core.publisher.Mono;

/**
 * The identity service's contract, served in process for the demo under {@code /demo-identity}:
 * principals:resolve and authorize for the sign-in and the policy checks, platform-credentials for
 * the platform clients, the account-scoped IAM resources (users, groups, roles, policies, access
 * keys, quotas) and access requests. Managed roles decide; an admin writes, a member reads, a
 * stranger gets 404. The BFF calls it over HTTP exactly as it will call the real service.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoIdentityRoutes {

    private static final String PREFIX = IdentityConfiguration.DEMO_PATH;

    private final DemoDirectory directory;
    private final DemoTokens tokens;
    private final ObjectProvider<DemoPlatformSeed> platformSeed;
    private final ObjectProvider<Access> access;
    private final String audience;
    private final Map<String, Map<String, Object>> accessKeys = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> customGroups = new ConcurrentHashMap<>();
    private final Map<String, Integer> quotaOverrides = new ConcurrentHashMap<>();
    private final Map<String, QuotaRequest> quotaRequests = new ConcurrentHashMap<>();
    private final Object quotaLock = new Object();

    public DemoIdentityRoutes(DemoDirectory directory, DemoTokens tokens, ObjectProvider<DemoPlatformSeed> platformSeed, ObjectProvider<Access> access,
            IdentityProperties identity) {
        this.directory = directory;
        this.audience = identity.serviceName();
        this.tokens = tokens;
        this.platformSeed = platformSeed;
        this.access = access;
    }

    @Bean
    public RouterFunction<ServerResponse> demoIdentityRouter() {
        String a = PREFIX + "/v1/accounts/{accountId}";
        return route(POST(PREFIX + "/v1/principals:resolve"), this::resolve)
                .andRoute(POST(PREFIX + "/v1/authorize"), this::authorize)
                .andRoute(GET(PREFIX + "/v1/accounts"), this::listAccounts)
                .andRoute(GET(a), this::getAccount)
                .andRoute(GET(a + "/platform-credentials"), this::platformCredentials)
                .andRoute(GET(a + "/users"), r -> member(r, this::listUsers))
                .andRoute(POST(a + "/users"), r -> admin(r, this::addUser))
                .andRoute(GET(a + "/users/{userId}"), r -> member(r, this::getUser))
                .andRoute(PATCH(a + "/users/{userId}"), r -> admin(r, this::updateUser))
                .andRoute(DELETE(a + "/users/{userId}"), r -> admin(r, this::removeUser))
                .andRoute(GET(a + "/groups"), r -> member(r, this::listGroups))
                .andRoute(POST(a + "/groups"), r -> admin(r, this::createGroup))
                .andRoute(GET(a + "/groups/{groupId}"), r -> member(r, this::getGroup))
                .andRoute(PATCH(a + "/groups/{groupId}"), r -> admin(r, this::updateGroup))
                .andRoute(DELETE(a + "/groups/{groupId}"), r -> admin(r, this::deleteGroup))
                .andRoute(GET(a + "/roles"), r -> member(r, this::listRoles))
                .andRoute(GET(a + "/roles/{roleId}"), r -> member(r, this::getRole))
                .andRoute(GET(a + "/policies"), r -> member(r, this::listPolicies))
                .andRoute(GET(a + "/policies/{policyId}"), r -> member(r, this::getPolicy))
                .andRoute(GET(a + "/access-keys"), r -> member(r, this::listAccessKeys))
                .andRoute(POST(a + "/access-keys"), r -> member(r, this::createAccessKey))
                .andRoute(GET(a + "/access-keys/{keyId}"), r -> member(r, this::getAccessKey))
                .andRoute(DELETE(a + "/access-keys/{keyId}"), r -> member(r, this::revokeAccessKey))
                .andRoute(POST(a + "/access-keys/{keyId}:rotate"), r -> member(r, this::rotateAccessKey))
                .andRoute(GET(a + "/quotas"), r -> member(r, this::listQuotas))
                .andRoute(GET(a + "/quotas/{quotaKey}"), r -> member(r, this::getQuota))
                .andRoute(PUT(a + "/quotas/{quotaKey}"), this::setQuota)
                .andRoute(POST(a + "/quotas/{quotaKey}:request-increase"), r -> quotaRequester(r, this::requestQuotaIncrease))
                .andRoute(GET(a + "/quota-requests/{requestId}"), r -> member(r, this::getQuotaRequest))
                .andRoute(DELETE(a + "/quota-requests/{requestId}"), r -> member(r, this::withdrawQuotaRequest))
                .andRoute(POST(a + "/quota-requests/{requestId}:decide"), this::decideQuotaRequest)
                .andRoute(GET(a + "/access-requests"), r -> member(r, this::listAccessRequests))
                .andRoute(POST(a + "/access-requests"), r -> member(r, this::createAccessRequest))
                .andRoute(GET(a + "/access-requests/{requestId}"), r -> member(r, this::getAccessRequest))
                .andRoute(POST(a + "/access-requests/{requestId}:decide"), r -> member(r, this::decideAccessRequest));
    }

    // ---- authentication and scoping

    private Optional<DemoCaller> caller(ServerRequest request) {
        return tokens.verify(request.headers().firstHeader(HttpHeaders.AUTHORIZATION), audience);
    }

    private interface Scoped {
        Mono<ServerResponse> handle(ServerRequest request, DemoCaller caller, String accountId);
    }

    /** A member of the account (or the console itself) may read; anyone else sees 404. */
    private Mono<ServerResponse> member(ServerRequest request, Scoped handler) {
        Optional<DemoCaller> caller = caller(request);
        if (caller.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "invalid or missing token");
        }
        String accountId = request.pathVariable("accountId");
        if (directory.account(accountId).isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "account " + accountId + " not found");
        }
        DemoCaller c = caller.get();
        if (!c.internal() && (!accountId.equals(c.accountId()) || directory.membership(c.personSubject(), accountId).isEmpty())) {
            return error(HttpStatus.NOT_FOUND, "account " + accountId + " not found");
        }
        return handler.handle(request, c, accountId);
    }

    /** An admin of the account (or the console itself) may write; a member who is not one gets a denial. */
    private Mono<ServerResponse> admin(ServerRequest request, Scoped handler) {
        return member(request, (r, caller, accountId) -> {
            if (!caller.internal() && !directory.isAdmin(caller.personSubject(), accountId)) {
                String action = "iam:" + verbOf(r);
                String role = RoleConventions.strongest(directory.rolesOf(caller.personSubject(), accountId));
                Denial denial = Denial.byPolicy(action, "wfrn:wf:iam:global:" + accountId + ":*",
                        "No statement allows " + action + " for role " + role, role, "managed/" + role, ManagedRoles.statementFor(role, action));
                return ServerResponse.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("error", denial.reason(), "denied", denial));
            }
            return handler.handle(r, caller, accountId);
        });
    }

    private static String verbOf(ServerRequest r) {
        String path = r.path();
        if (path.endsWith(":rotate")) {
            return "rotate";
        }
        if (path.endsWith(":decide")) {
            return "decide";
        }
        if (path.endsWith(":request-increase")) {
            return "update";
        }
        return switch (r.method().name()) {
            case "POST" -> "create";
            case "PATCH", "PUT" -> "update";
            case "DELETE" -> "delete";
            default -> "get";
        };
    }

    // ---- principals and authorization

    private Mono<ServerResponse> resolve(ServerRequest request) {
        Optional<DemoCaller> caller = caller(request);
        if (caller.isEmpty() || !caller.get().internal()) {
            return error(HttpStatus.NOT_FOUND, "not found");
        }
        return request.bodyToMono(JsonNode.class).flatMap(body -> {
            String subject = body.path("subject").asText("");
            Optional<DemoPerson> person = directory.person(subject);
            List<DemoMembership> memberships = directory.membershipsOf(subject);
            if (person.isEmpty() || memberships.isEmpty()) {
                return error(HttpStatus.NOT_FOUND, "the user belongs to no console account");
            }
            List<Map<String, Object>> accounts = new ArrayList<>();
            for (DemoMembership m : memberships) {
                DemoAccount account = directory.account(m.accountId()).orElseThrow();
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("accountId", m.accountId());
                a.put("name", account.name());
                a.put("status", "active");
                a.put("groups", m.groups());
                a.put("roles", m.roles());
                a.put("defaultRegion", account.defaultRegion());
                accounts.add(a);
            }
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("user", user(person.get(), body.path("issuer").asText(null), null));
            answer.put("accounts", accounts);
            return json(HttpStatus.OK, answer);
        });
    }

    private Mono<ServerResponse> authorize(ServerRequest request) {
        if (caller(request).isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "invalid or missing token");
        }
        return request.bodyToMono(JsonNode.class).flatMap(body -> {
            JsonNode principal = body.path("principal");
            String accountId = principal.path("accountId").asText("");
            boolean service = principal.hasNonNull("service");
            String userId = principal.path("userId").asText("");
            List<String> roles = service ? List.of(ManagedRoles.ADMIN)
                    : directory.byUserId(userId).map(p -> directory.rolesOf(p.subject(), accountId)).orElse(List.of());
            String strongest = roles.isEmpty() ? "none" : RoleConventions.strongest(roles);
            List<Map<String, Object>> decisions = new ArrayList<>();
            for (JsonNode check : body.path("checks")) {
                String action = check.path("action").asText("");
                Map<String, Object> d = new LinkedHashMap<>();
                boolean allowed = !roles.isEmpty() && (ManagedRoles.anyAllows(roles, action) || "access-request:decide".equals(action)
                        && directory.byUserId(userId).map(p -> directory.decidesAccessRequests(p.subject(), accountId)).orElse(false));
                Optional<DemoDirectory.DenyStatement> deny = directory.deniedBy(accountId, action, check.path("resource").asText(null));
                if (deny.isPresent()) {
                    d.put("allowed", false);
                    d.put("reason", "Explicitly denied: " + action + " on " + deny.get().resourcePattern());
                    d.put("role", strongest);
                    d.put("policyId", "demo/deny");
                    d.put("statement", "deny " + action + " " + deny.get().resourcePattern());
                    decisions.add(d);
                    continue;
                }
                d.put("allowed", allowed);
                if (!allowed) {
                    d.put("reason", roles.isEmpty() ? "The user is not a member of account " + accountId
                            : "No statement allows " + action + " for role " + strongest);
                    d.put("role", strongest);
                    d.put("policyId", "managed/" + strongest);
                    d.put("statement", ManagedRoles.statementFor(strongest, action));
                }
                decisions.add(d);
            }
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("decisions", decisions);
            answer.put("version", directory.version(accountId));
            return json(HttpStatus.OK, answer);
        });
    }

    private Mono<ServerResponse> listAccounts(ServerRequest request) {
        Optional<DemoCaller> caller = caller(request);
        if (caller.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "invalid or missing token");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (DemoAccount account : directory.accounts()) {
            if (caller.get().internal() || directory.membership(caller.get().personSubject(), account.accountId()).isPresent()) {
                items.add(account(account));
            }
        }
        return json(HttpStatus.OK, page(items));
    }

    private Mono<ServerResponse> getAccount(ServerRequest request) {
        return member(request, (r, caller, accountId) -> json(HttpStatus.OK, account(directory.account(accountId).orElseThrow())));
    }

    private Mono<ServerResponse> platformCredentials(ServerRequest request) {
        Optional<DemoCaller> caller = caller(request);
        String accountId = request.pathVariable("accountId");
        if (caller.isEmpty() || !caller.get().internal()) {
            return error(HttpStatus.NOT_FOUND, "not found");
        }
        DemoPlatformSeed seed = platformSeed.getIfAvailable();
        if (seed == null || seed.account(accountId).isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "account " + accountId + " has no platform credential");
        }
        var credential = seed.account(accountId).get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", accountId);
        body.put("platformServiceAccountId", credential.serviceAccountId());
        body.put("source", "local");
        body.put("token", credential.token());
        body.put("issuedAt", directory.now());
        return json(HttpStatus.OK, body);
    }

    // ---- users

    private Mono<ServerResponse> listUsers(ServerRequest request, DemoCaller caller, String accountId) {
        String q = request.queryParam("q").map(String::toLowerCase).orElse("");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<DemoPerson, DemoMembership> e : directory.members(accountId)) {
            DemoPerson p = e.getKey();
            if (q.isEmpty() || p.name().toLowerCase().contains(q) || p.email().toLowerCase().contains(q) || p.subject().contains(q)) {
                items.add(user(p, null, e.getValue()));
            }
        }
        return json(HttpStatus.OK, page(items));
    }

    private Mono<ServerResponse> getUser(ServerRequest request, DemoCaller caller, String accountId) {
        String userId = request.pathVariable("userId");
        Optional<DemoPerson> person = directory.byUserId(userId);
        Optional<DemoMembership> membership = person.flatMap(p -> directory.membership(p.subject(), accountId));
        if (person.isEmpty() || membership.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "user " + userId + " not found");
        }
        return json(HttpStatus.OK, user(person.get(), null, membership.get()));
    }

    private Mono<ServerResponse> addUser(ServerRequest request, DemoCaller caller, String accountId) {
        return request.bodyToMono(JsonNode.class).flatMap(body -> {
            String email = body.path("email").asText("");
            if (email.isBlank() || !email.contains("@")) {
                return error(HttpStatus.BAD_REQUEST, "email is required");
            }
            String subject = email.substring(0, email.indexOf('@')).toLowerCase();
            if (directory.membership(subject, accountId).isPresent()) {
                return error(HttpStatus.CONFLICT, "user " + email + " is already a member");
            }
            List<String> groups = strings(body.path("groups"));
            List<String> roles = strings(body.path("roles"));
            if (roles.isEmpty()) {
                roles = RoleConventions.fromGroups(groups);
            }
            DemoMembership m = directory.addMember(accountId, subject, email, body.path("name").asText(null), groups.isEmpty() ? null : groups, roles);
            invalidate(accountId);
            return json(HttpStatus.CREATED, user(directory.person(subject).orElseThrow(), null, m));
        });
    }

    private Mono<ServerResponse> updateUser(ServerRequest request, DemoCaller caller, String accountId) {
        String userId = request.pathVariable("userId");
        Optional<DemoPerson> person = directory.byUserId(userId);
        if (person.isEmpty() || directory.membership(person.get().subject(), accountId).isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "user " + userId + " not found");
        }
        return request.bodyToMono(JsonNode.class).flatMap(body -> {
            DemoMembership current = directory.membership(person.get().subject(), accountId).orElseThrow();
            List<String> groups = body.has("groups") ? strings(body.path("groups")) : current.groups();
            List<String> roles = body.has("roles") ? strings(body.path("roles")) : (body.has("groups") ? RoleConventions.fromGroups(groups) : current.roles());
            DemoMembership m = directory.addMember(accountId, person.get().subject(), person.get().email(), person.get().name(), groups, roles);
            invalidate(accountId);
            return json(HttpStatus.OK, user(person.get(), null, m));
        });
    }

    private Mono<ServerResponse> removeUser(ServerRequest request, DemoCaller caller, String accountId) {
        String userId = request.pathVariable("userId");
        Optional<DemoPerson> person = directory.byUserId(userId);
        if (person.isEmpty() || !directory.removeMember(accountId, person.get().subject())) {
            return error(HttpStatus.NOT_FOUND, "user " + userId + " not found");
        }
        invalidate(accountId);
        return ServerResponse.noContent().build();
    }

    // ---- groups, roles, policies

    private List<Map<String, Object>> groups(String accountId) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (String role : ManagedRoles.ALL) {
            String name = ManagedRoles.groupOf(role);
            byName.put(name, group(accountId, name, "Members hold the managed role " + role, List.of(role), true));
        }
        for (Map.Entry<DemoPerson, DemoMembership> e : directory.members(accountId)) {
            for (String g : e.getValue().groups()) {
                byName.computeIfAbsent(g, name -> group(accountId, name, "", List.of(), false));
                @SuppressWarnings("unchecked")
                List<String> members = (List<String>) byName.get(g).get("members");
                members.add(e.getKey().userId());
            }
        }
        for (Map<String, Object> custom : customGroups.values()) {
            if (accountId.equals(custom.get("accountId"))) {
                byName.putIfAbsent((String) custom.get("name"), custom);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static Map<String, Object> group(String accountId, String name, String description, List<String> roles, boolean builtIn) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("groupId", "grp_" + accountId + "_" + name);
        g.put("accountId", accountId);
        g.put("name", name);
        g.put("description", description);
        g.put("roles", new ArrayList<>(roles));
        g.put("members", new ArrayList<String>());
        g.put("idpGroups", List.of());
        g.put("builtIn", builtIn);
        g.put("version", 1);
        return g;
    }

    private Mono<ServerResponse> listGroups(ServerRequest request, DemoCaller caller, String accountId) {
        return json(HttpStatus.OK, page(groups(accountId)));
    }

    private Mono<ServerResponse> getGroup(ServerRequest request, DemoCaller caller, String accountId) {
        String groupId = request.pathVariable("groupId");
        return groups(accountId).stream().filter(g -> groupId.equals(g.get("groupId")) || groupId.equals(g.get("name"))).findFirst()
                .map(g -> json(HttpStatus.OK, g)).orElseGet(() -> error(HttpStatus.NOT_FOUND, "group " + groupId + " not found"));
    }

    private Mono<ServerResponse> createGroup(ServerRequest request, DemoCaller caller, String accountId) {
        return request.bodyToMono(JsonNode.class).flatMap(body -> {
            String name = body.path("name").asText("");
            if (!name.matches("^[a-z0-9-]{2,64}$")) {
                return error(HttpStatus.BAD_REQUEST, "a group name is 2 to 64 lowercase letters, digits or dashes");
            }
            if (groups(accountId).stream().anyMatch(g -> name.equals(g.get("name")))) {
                return error(HttpStatus.CONFLICT, "group " + name + " exists");
            }
            Map<String, Object> g = group(accountId, name, body.path("description").asText(""), strings(body.path("roles")), false);
            customGroups.put((String) g.get("groupId"), g);
            invalidate(accountId);
            return json(HttpStatus.CREATED, g);
        });
    }

    private Mono<ServerResponse> updateGroup(ServerRequest request, DemoCaller caller, String accountId) {
        String groupId = request.pathVariable("groupId");
        Map<String, Object> g = customGroups.get(groupId);
        if (g == null) {
            return error(HttpStatus.NOT_FOUND, "group " + groupId + " is not editable here");
        }
        return request.bodyToMono(JsonNode.class).flatMap(body -> {
            if (body.has("description")) {
                g.put("description", body.path("description").asText(""));
            }
            if (body.has("roles")) {
                g.put("roles", strings(body.path("roles")));
            }
            g.put("version", ((Integer) g.get("version")) + 1);
            invalidate(accountId);
            return json(HttpStatus.OK, g);
        });
    }

    private Mono<ServerResponse> deleteGroup(ServerRequest request, DemoCaller caller, String accountId) {
        String groupId = request.pathVariable("groupId");
        if (customGroups.remove(groupId) == null) {
            return error(HttpStatus.NOT_FOUND, "group " + groupId + " is not deletable here");
        }
        invalidate(accountId);
        return ServerResponse.noContent().build();
    }

    private static Map<String, Object> role(String role) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("roleId", "managed/" + role);
        r.put("name", role);
        r.put("description", switch (role) {
            case ManagedRoles.VIEWER -> "Reads every resource of the account";
            case ManagedRoles.OPERATOR -> "Reads and runs every non-destructive verb";
            case ManagedRoles.ADMIN -> "Everything, deletes and IAM included";
            default -> "Reads everything and the audit trail";
        });
        r.put("policies", List.of("managed/" + role));
        r.put("builtIn", true);
        r.put("version", 1);
        return r;
    }

    private static Map<String, Object> policy(String role) {
        List<Map<String, Object>> statements = new ArrayList<>();
        statements.add(statement("AllowReads", "allow", List.of("*:list", "*:get", "*:read", "*:preview")));
        if (!ManagedRoles.AUDITOR.equals(role)) {
            statements.add(statement("AllowSelfService", "allow", List.of("access-request:create", "quota:request-increase")));
        }
        if (!ManagedRoles.VIEWER.equals(role) && !ManagedRoles.AUDITOR.equals(role)) {
            statements.add(statement("AllowChanges", "allow", List.of("*:*")));
        }
        if (ManagedRoles.OPERATOR.equals(role)) {
            statements.add(statement("DenyDestructive", "deny", List.of("*:delete", "*:destroy", "*:purge", "*:terminate")));
            statements.add(statement("DenyIamWrites", "deny", List.of("iam:create", "iam:update", "iam:delete", "iam:rotate", "iam:decide")));
        }
        if (ManagedRoles.AUDITOR.equals(role)) {
            statements.add(statement("AllowAudit", "allow", List.of("audit:*", "jobs:list", "jobs:get")));
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("policyId", "managed/" + role);
        p.put("name", role);
        p.put("description", "The managed policy of role " + role);
        p.put("statements", statements);
        p.put("builtIn", true);
        p.put("version", 1);
        return p;
    }

    private static Map<String, Object> statement(String sid, String effect, List<String> actions) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sid", sid);
        s.put("effect", effect);
        s.put("actions", actions);
        s.put("resources", List.of("wfrn:wf:*"));
        return s;
    }

    private Mono<ServerResponse> listRoles(ServerRequest request, DemoCaller caller, String accountId) {
        return json(HttpStatus.OK, page(ManagedRoles.ALL.stream().map(DemoIdentityRoutes::role).toList()));
    }

    private Mono<ServerResponse> getRole(ServerRequest request, DemoCaller caller, String accountId) {
        String roleId = request.pathVariable("roleId").replace("managed/", "");
        return ManagedRoles.isManaged(roleId) ? json(HttpStatus.OK, role(roleId)) : error(HttpStatus.NOT_FOUND, "role " + roleId + " not found");
    }

    private Mono<ServerResponse> listPolicies(ServerRequest request, DemoCaller caller, String accountId) {
        return json(HttpStatus.OK, page(ManagedRoles.ALL.stream().map(DemoIdentityRoutes::policy).toList()));
    }

    private Mono<ServerResponse> getPolicy(ServerRequest request, DemoCaller caller, String accountId) {
        String policyId = request.pathVariable("policyId").replace("managed/", "");
        return ManagedRoles.isManaged(policyId) ? json(HttpStatus.OK, policy(policyId)) : error(HttpStatus.NOT_FOUND, "policy " + policyId + " not found");
    }

    // ---- access keys

    private Mono<ServerResponse> listAccessKeys(ServerRequest request, DemoCaller caller, String accountId) {
        Optional<String> userId = request.queryParam("userId");
        boolean admin = caller.internal() || directory.isAdmin(caller.personSubject(), accountId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> key : accessKeys.values()) {
            if (!accountId.equals(key.get("accountId"))) {
                continue;
            }
            String owner = (String) key.get("userId");
            if (userId.isPresent() && !userId.get().equals(owner)) {
                continue;
            }
            if (!admin && !owner.equals(caller.subject())) {
                continue;
            }
            items.add(withoutSecret(key));
        }
        return json(HttpStatus.OK, page(items));
    }

    private Mono<ServerResponse> createAccessKey(ServerRequest request, DemoCaller caller, String accountId) {
        return request.bodyToMono(JsonNode.class).flatMap(body -> {
            String userId = body.path("userId").asText(caller.subject());
            boolean own = userId.equals(caller.subject());
            if (!own && !caller.internal() && !directory.isAdmin(caller.personSubject(), accountId)) {
                return denied(caller, accountId, "iam:create");
            }
            if (directory.byUserId(userId).flatMap(p -> directory.membership(p.subject(), accountId)).isEmpty()) {
                return error(HttpStatus.NOT_FOUND, "user " + userId + " not found");
            }
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("keyId", "ck_" + Ulid.next().toLowerCase());
            key.put("accountId", accountId);
            key.put("userId", userId);
            key.put("description", body.path("description").asText(""));
            key.put("status", "active");
            key.put("createdAt", directory.now());
            key.put("secretPart", "cs_" + Ulid.next().toLowerCase());
            accessKeys.put((String) key.get("keyId"), key);
            return json(HttpStatus.CREATED, key);
        });
    }

    private Mono<ServerResponse> getAccessKey(ServerRequest request, DemoCaller caller, String accountId) {
        Map<String, Object> key = accessKeys.get(request.pathVariable("keyId"));
        if (key == null || !accountId.equals(key.get("accountId")) || !canSee(caller, accountId, key)) {
            return error(HttpStatus.NOT_FOUND, "access key not found");
        }
        return json(HttpStatus.OK, withoutSecret(key));
    }

    private Mono<ServerResponse> revokeAccessKey(ServerRequest request, DemoCaller caller, String accountId) {
        Map<String, Object> key = accessKeys.get(request.pathVariable("keyId"));
        if (key == null || !accountId.equals(key.get("accountId")) || !canSee(caller, accountId, key)) {
            return error(HttpStatus.NOT_FOUND, "access key not found");
        }
        key.put("status", "revoked");
        return ServerResponse.noContent().build();
    }

    private Mono<ServerResponse> rotateAccessKey(ServerRequest request, DemoCaller caller, String accountId) {
        Map<String, Object> key = accessKeys.get(request.pathVariable("keyId"));
        if (key == null || !accountId.equals(key.get("accountId")) || !canSee(caller, accountId, key)) {
            return error(HttpStatus.NOT_FOUND, "access key not found");
        }
        if (!"active".equals(key.get("status"))) {
            return error(HttpStatus.CONFLICT, "only an active key rotates");
        }
        key.put("secretPart", "cs_" + Ulid.next().toLowerCase());
        key.put("graceUntil", directory.now().plusSeconds(600));
        return json(HttpStatus.OK, key);
    }

    private boolean canSee(DemoCaller caller, String accountId, Map<String, Object> key) {
        return caller.internal() || directory.isAdmin(caller.personSubject(), accountId) || caller.subject().equals(key.get("userId"));
    }

    private static Map<String, Object> withoutSecret(Map<String, Object> key) {
        Map<String, Object> copy = new LinkedHashMap<>(key);
        copy.remove("secretPart");
        copy.remove("graceUntil");
        copy.remove("accountId");
        return copy;
    }

    // ---- quotas

    /**
     * The identity service's keys and defaults: {@code jobs-per-minute} (the platform's bucket, 60
     * a minute with a burst of 60), {@code access-keys} (50, counted here) and
     * {@code resources.<type>} for every resource type of the demo catalog, by its
     * {@code metadata.name} (100; {@code used} is the state service's to report, so 0 here as there).
     */
    private static final String JOBS_PER_MINUTE = "jobs-per-minute";
    private static final String ACCESS_KEYS = "access-keys";
    private static final String RESOURCES = "resources.";
    private static final List<String> DEMO_TYPES = List.of("database", "environment", "image", "vm", "volume");
    private static final Map<String, Integer> QUOTAS = quotaDefaults();

    private static Map<String, Integer> quotaDefaults() {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        defaults.put(JOBS_PER_MINUTE, 60);
        defaults.put(ACCESS_KEYS, 50);
        DEMO_TYPES.forEach(type -> defaults.put(RESOURCES + type, 100));
        return Map.copyOf(defaults);
    }

    private Mono<ServerResponse> listQuotas(ServerRequest request, DemoCaller caller, String accountId) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(quota(accountId, JOBS_PER_MINUTE));
        items.add(quota(accountId, ACCESS_KEYS));
        DEMO_TYPES.forEach(type -> items.add(quota(accountId, RESOURCES + type)));
        return json(HttpStatus.OK, page(items));
    }

    private Mono<ServerResponse> getQuota(ServerRequest request, DemoCaller caller, String accountId) {
        String key = request.pathVariable("quotaKey");
        return QUOTAS.containsKey(key) ? json(HttpStatus.OK, quota(accountId, key)) : error(HttpStatus.NOT_FOUND, "quota " + key + " not found");
    }

    /** Viewers, operators and admins ask for more ({@code quota:request-increase}); an auditor only reads. */
    private Mono<ServerResponse> quotaRequester(ServerRequest request, Scoped handler) {
        return member(request, (r, caller, accountId) -> {
            List<String> roles = caller.internal() ? List.of() : directory.rolesOf(caller.personSubject(), accountId);
            boolean asks = caller.internal() || roles.contains(ManagedRoles.VIEWER) || roles.contains(ManagedRoles.OPERATOR)
                    || roles.contains(ManagedRoles.ADMIN);
            if (!asks) {
                String role = RoleConventions.strongest(roles);
                Denial denial = Denial.byPolicy("quota:request-increase", "wfrn:wf:iam::" + accountId + ":quota/" + r.pathVariable("quotaKey"),
                        "No statement allows quota:request-increase for role " + role, role, "managed/" + role, null);
                return ServerResponse.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("error", denial.reason(), "denied", denial));
            }
            return handler.handle(r, caller, accountId);
        });
    }

    private Mono<ServerResponse> setQuota(ServerRequest request) {
        Optional<DemoCaller> caller = caller(request);
        if (caller.isEmpty() || !caller.get().internal()) {
            return error(HttpStatus.NOT_FOUND, "not found");
        }
        String accountId = request.pathVariable("accountId");
        String key = request.pathVariable("quotaKey");
        return request.bodyToMono(JsonNode.class).flatMap(body -> {
            int limit = body.path("limit").asInt(QUOTAS.getOrDefault(key, 0));
            synchronized (quotaLock) {
                quotaOverrides.put(accountId + "/" + key, limit);
                // a limit at or above an open request's ask makes the request moot: it is approved with that limit
                openQuotaRequest(accountId, key).filter(open -> limit >= open.requestedLimit()).ifPresent(open -> quotaRequests.put(open.requestId(),
                        open.decided("approved", limit, caller.get().subject(), "the limit was set to " + limit, directory.now())));
            }
            return json(HttpStatus.OK, quota(accountId, key));
        });
    }

    private Mono<ServerResponse> requestQuotaIncrease(ServerRequest request, DemoCaller caller, String accountId) {
        String key = request.pathVariable("quotaKey");
        if (!QUOTAS.containsKey(key)) {
            return error(HttpStatus.NOT_FOUND, "quota " + key + " not found");
        }
        return request.bodyToMono(JsonNode.class).flatMap(body -> {
            int limit = body.path("limit").asInt(0);
            String reason = body.path("reason").asText("");
            if (limit < 1 || reason.length() < 10 || reason.length() > 1000) {
                return error(HttpStatus.BAD_REQUEST, "limit (at least 1) and reason (10 to 1000 characters) are required");
            }
            QuotaRequest opened;
            synchronized (quotaLock) {
                Optional<QuotaRequest> open = openQuotaRequest(accountId, key);
                if (open.isPresent()) {
                    return error(HttpStatus.CONFLICT, "an increase of " + key + " is open already (" + open.get().requestId() + ")");
                }
                opened = new QuotaRequest("qreq_" + Ulid.next().toLowerCase(Locale.ROOT), accountId, key, limitOf(accountId, key), limit, reason, "open",
                        null, caller.subject(), directory.now(), null, null, null);
                quotaRequests.put(opened.requestId(), opened);
            }
            return json(HttpStatus.ACCEPTED, opened.body());
        });
    }

    private Mono<ServerResponse> getQuotaRequest(ServerRequest request, DemoCaller caller, String accountId) {
        return quotaRequest(accountId, request.pathVariable("requestId")).map(r -> json(HttpStatus.OK, r.body()))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "quota request " + request.pathVariable("requestId") + " not found"));
    }

    /** By its requester, an account admin or the console itself; a decided request answers 409. */
    private Mono<ServerResponse> withdrawQuotaRequest(ServerRequest request, DemoCaller caller, String accountId) {
        String requestId = request.pathVariable("requestId");
        synchronized (quotaLock) {
            Optional<QuotaRequest> found = quotaRequest(accountId, requestId);
            if (found.isEmpty()) {
                return error(HttpStatus.NOT_FOUND, "quota request " + requestId + " not found");
            }
            QuotaRequest r = found.get();
            if (!caller.internal() && !caller.subject().equals(r.requestedBy()) && !directory.isAdmin(caller.personSubject(), accountId)) {
                return denied(caller, accountId, "quota:set");
            }
            if (!r.open()) {
                return error(HttpStatus.CONFLICT, "the request is " + r.status());
            }
            quotaRequests.put(requestId, r.decided("withdrawn", null, caller.subject(), null, directory.now()));
        }
        return ServerResponse.noContent().build();
    }

    /** The platform team's decision, so the console itself only; approving sets the limit (the ask by default). */
    private Mono<ServerResponse> decideQuotaRequest(ServerRequest request) {
        Optional<DemoCaller> caller = caller(request);
        if (caller.isEmpty() || !caller.get().internal()) {
            return error(HttpStatus.NOT_FOUND, "not found");
        }
        String accountId = request.pathVariable("accountId");
        String requestId = request.pathVariable("requestId");
        return request.bodyToMono(JsonNode.class).flatMap(body -> {
            String decision = body.path("decision").asText("");
            boolean approve = "approve".equals(decision);
            Integer granted = body.hasNonNull("limit") ? Integer.valueOf(body.path("limit").asInt(0)) : null;
            String comment = body.hasNonNull("comment") ? body.path("comment").asText() : null;
            if ((!approve && !"reject".equals(decision)) || (granted != null && granted < 1) || (comment != null && comment.length() > 2000)) {
                return error(HttpStatus.BAD_REQUEST, "decision is approve or reject, limit at least 1, comment at most 2000 characters");
            }
            QuotaRequest decided;
            synchronized (quotaLock) {
                Optional<QuotaRequest> found = quotaRequest(accountId, requestId);
                if (found.isEmpty()) {
                    return error(HttpStatus.NOT_FOUND, "quota request " + requestId + " not found");
                }
                if (!found.get().open()) {
                    return error(HttpStatus.CONFLICT, "the request is " + found.get().status());
                }
                QuotaRequest r = found.get();
                if (approve) {
                    int limit = granted == null ? r.requestedLimit() : granted;
                    quotaOverrides.put(accountId + "/" + r.quotaKey(), limit);
                    decided = r.decided("approved", limit, caller.get().subject(), comment, directory.now());
                } else {
                    decided = r.decided("rejected", null, caller.get().subject(), comment, directory.now());
                }
                quotaRequests.put(requestId, decided);
            }
            return json(HttpStatus.ACCEPTED, decided.body());
        }).switchIfEmpty(Mono.defer(() -> error(HttpStatus.BAD_REQUEST, "a decision is required")));
    }

    private Optional<QuotaRequest> quotaRequest(String accountId, String requestId) {
        return Optional.ofNullable(quotaRequests.get(requestId)).filter(r -> r.accountId().equals(accountId));
    }

    private Optional<QuotaRequest> openQuotaRequest(String accountId, String key) {
        return quotaRequests.values().stream().filter(r -> r.open() && r.accountId().equals(accountId) && r.quotaKey().equals(key)).findFirst();
    }

    private int limitOf(String accountId, String key) {
        Integer override = quotaOverrides.get(accountId + "/" + key);
        return override == null ? QUOTAS.getOrDefault(key, 0) : override;
    }

    private Map<String, Object> quota(String accountId, String key) {
        Map<String, Object> q = new LinkedHashMap<>();
        Integer override = quotaOverrides.get(accountId + "/" + key);
        int limit = override == null ? QUOTAS.get(key) : override;
        q.put("key", key);
        q.put("limit", limit);
        if (JOBS_PER_MINUTE.equals(key)) {
            q.put("burst", limit);
        }
        q.put("used", ACCESS_KEYS.equals(key) ? usableKeys(accountId) : 0);
        q.put("source", override != null ? "override" : JOBS_PER_MINUTE.equals(key) ? "platform" : "default");
        openQuotaRequest(accountId, key).ifPresent(open -> q.put("pendingRequestId", open.requestId()));
        return q;
    }

    private int usableKeys(String accountId) {
        return (int) accessKeys.values().stream().filter(k -> accountId.equals(k.get("accountId")) && "active".equals(k.get("status"))).count();
    }

    /** A quota increase request, as the identity service's {@code QuotaRequest}. */
    private record QuotaRequest(String requestId, String accountId, String quotaKey, int currentLimit, int requestedLimit, String reason, String status,
            Integer grantedLimit, String requestedBy, Instant requestedAt, Instant decidedAt, String decidedBy, String comment) {

        boolean open() {
            return "open".equals(status);
        }

        QuotaRequest decided(String outcome, Integer granted, String by, String note, Instant at) {
            return new QuotaRequest(requestId, accountId, quotaKey, currentLimit, requestedLimit, reason, outcome, granted, requestedBy, requestedAt, at, by,
                    note);
        }

        Map<String, Object> body() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requestId", requestId);
            m.put("accountId", accountId);
            m.put("quotaKey", quotaKey);
            m.put("currentLimit", currentLimit);
            m.put("requestedLimit", requestedLimit);
            m.put("reason", reason);
            m.put("status", status);
            if (grantedLimit != null) {
                m.put("grantedLimit", grantedLimit);
            }
            m.put("requestedBy", requestedBy);
            m.put("requestedAt", requestedAt);
            if (decidedAt != null) {
                m.put("decidedAt", decidedAt);
                m.put("decidedBy", decidedBy);
            }
            if (comment != null) {
                m.put("comment", comment);
            }
            return m;
        }
    }

    // ---- access requests

    private static final Pattern ACTION = Pattern.compile("^[a-z0-9-]{1,64}:[a-z0-9-]{1,64}$");

    /** Whether the caller may read the account's whole queue: the console itself, or a role whose policy allows the action (admins and auditors). */
    private boolean readsTheQueue(DemoCaller caller, String accountId, String action) {
        return caller.internal() || ManagedRoles.anyAllows(directory.rolesOf(caller.personSubject(), accountId), action);
    }

    /** A person always sees their own requests; the account's queue needs {@code access-request:list}; someone else's alone is refused. */
    private Mono<ServerResponse> listAccessRequests(ServerRequest request, DemoCaller caller, String accountId) {
        String status = request.queryParam("status").filter(v -> !v.isBlank()).orElse(null);
        if (status != null && !AccessRequest.STATUSES.contains(status)) {
            return error(HttpStatus.BAD_REQUEST, "status is pending, approved, rejected or expired");
        }
        String requester = request.queryParam("requester").filter(v -> !v.isBlank()).orElse(null);
        String only;
        if (readsTheQueue(caller, accountId, "access-request:list")) {
            only = requester;
        } else if (requester == null || requester.equals(caller.subject())) {
            only = caller.subject();
        } else {
            return denied(caller, accountId, "access-request:list");
        }
        List<Map<String, Object>> items = directory.accessRequests(accountId, only, status).stream().map(DemoIdentityRoutes::accessRequest).toList();
        return json(HttpStatus.OK, page(items));
    }

    private Mono<ServerResponse> getAccessRequest(ServerRequest request, DemoCaller caller, String accountId) {
        String requestId = request.pathVariable("requestId");
        Optional<AccessRequest> found = directory.accessRequest(requestId).filter(r -> r.accountId().equals(accountId));
        if (found.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "access request " + requestId + " not found");
        }
        boolean mine = !caller.internal() && caller.subject().equals(found.get().requester().userId());
        if (!mine && !readsTheQueue(caller, accountId, "access-request:get")) {
            return denied(caller, accountId, "access-request:get");
        }
        return json(HttpStatus.OK, accessRequest(found.get()));
    }

    /**
     * Opens a request as the identity service does: the role named, else the weakest managed role
     * that allows the action (400 when the requester may already do it); one pending request per
     * requester and role, a second answers 409 with the pending one.
     */
    private Mono<ServerResponse> createAccessRequest(ServerRequest request, DemoCaller caller, String accountId) {
        return request.bodyToMono(JsonNode.class).flatMap(body -> {
            String action = body.path("action").asText("");
            String reason = body.path("reason").asText("");
            if (!ACTION.matcher(action).matches()) {
                return error(HttpStatus.BAD_REQUEST, "action is <service>:<verb>");
            }
            if (reason.isBlank() || reason.length() > 2000) {
                return error(HttpStatus.BAD_REQUEST, "reason is 1 to 2000 characters");
            }
            if (caller.internal()) {
                return error(HttpStatus.BAD_REQUEST, "only a user opens an access request");
            }
            DemoPerson person = directory.byUserId(caller.subject()).orElse(null);
            if (person == null) {
                return error(HttpStatus.NOT_FOUND, "user not found");
            }
            String named = body.path("role").asText("").toLowerCase(Locale.ROOT);
            String role;
            if (!named.isBlank()) {
                if (!ManagedRoles.isManaged(named)) {
                    return error(HttpStatus.BAD_REQUEST, "'" + named + "' is no role of this account");
                }
                role = named;
            } else {
                role = ManagedRoles.suggestedFor(action);
                if (ManagedRoles.anyAllows(directory.rolesOf(person.subject(), accountId), action) || !ManagedRoles.allows(role, action)) {
                    return error(HttpStatus.BAD_REQUEST, "no managed role would allow " + action + " (or it is allowed already); name the role to ask for");
                }
            }
            Optional<AccessRequest> pending = directory.pendingRequest(accountId, person.userId(), role);
            if (pending.isPresent()) {
                return json(HttpStatus.CONFLICT, accessRequest(pending.get()));
            }
            String resource = body.hasNonNull("resource") ? body.path("resource").asText() : "wfrn:wf:*:*:" + accountId + ":*";
            AccessRequest created = directory.requestAccess(accountId, person, action, resource, role, reason);
            return json(HttpStatus.ACCEPTED, accessRequest(created));
        });
    }

    /** Admins and the members of approver groups decide (the identity service decides on {@code access-request:decide}, not on a role name). */
    private Mono<ServerResponse> decideAccessRequest(ServerRequest request, DemoCaller caller, String accountId) {
        String requestId = request.pathVariable("requestId");
        if (!caller.internal() && !directory.decidesAccessRequests(caller.personSubject(), accountId)) {
            return denied(caller, accountId, "access-request:decide");
        }
        return request.bodyToMono(JsonNode.class).flatMap(body -> {
            String decision = body.path("decision").asText("");
            String comment = body.hasNonNull("comment") ? body.path("comment").asText() : null;
            if ((!"approve".equals(decision) && !"reject".equals(decision)) || (comment != null && comment.length() > 2000)) {
                return error(HttpStatus.BAD_REQUEST, "decision is approve or reject, comment at most 2000 characters");
            }
            Optional<AccessRequest> existing = directory.accessRequest(requestId).filter(r -> r.accountId().equals(accountId));
            if (existing.isEmpty()) {
                return error(HttpStatus.NOT_FOUND, "access request " + requestId + " not found");
            }
            if (!caller.internal() && caller.subject().equals(existing.get().requester().userId())) {
                return ownRequest(accountId, requestId);
            }
            if (!existing.get().open()) {
                return error(HttpStatus.CONFLICT, "the request is " + existing.get().status());
            }
            AccessRequest decided = directory.decide(requestId, decision, caller.subject(), comment).orElseThrow();
            if (!"approved".equals(decided.status()) && !"rejected".equals(decided.status())) {
                return error(HttpStatus.CONFLICT, "the request is " + decided.status());
            }
            invalidate(accountId);
            return json(HttpStatus.ACCEPTED, accessRequest(decided));
        });
    }

    /** Nobody decides their own access request, as the identity service refuses it; there is nothing to ask for, so it is not requestable. */
    private Mono<ServerResponse> ownRequest(String accountId, String requestId) {
        Denial denial = new Denial(Denial.CONSOLE_IAM, "access-request:decide", "wfrn:wf:iam::" + accountId + ":access-request/" + requestId,
                "nobody decides their own access request", null, null, null, null, false, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", denial.reason());
        body.put("denied", denial);
        return json(HttpStatus.FORBIDDEN, body);
    }

    private static Map<String, Object> accessRequest(AccessRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", r.requestId());
        m.put("accountId", r.accountId());
        m.put("requester", Map.of("userId", r.requester().userId(), "email", r.requester().email(), "name", r.requester().name()));
        m.put("action", r.action());
        if (r.resource() != null) {
            m.put("resource", r.resource());
        }
        m.put("role", r.role());
        m.put("reason", r.reason());
        m.put("status", r.status());
        m.put("requestedAt", r.requestedAt());
        if (r.decidedAt() != null) {
            m.put("decidedAt", r.decidedAt());
            m.put("decidedBy", r.decidedBy());
        }
        if (r.comment() != null) {
            m.put("comment", r.comment());
        }
        m.put("approvers", r.approvers());
        return m;
    }

    // ---- shapes and helpers

    private Map<String, Object> user(DemoPerson p, String issuer, DemoMembership membership) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("userId", p.userId());
        u.put("email", p.email());
        u.put("name", p.name());
        u.put("status", "active");
        if (issuer != null) {
            u.put("issuer", issuer);
        }
        u.put("subject", p.subject());
        u.put("groups", membership == null ? p.idpGroups() : membership.groups());
        if (membership != null) {
            u.put("roles", membership.roles());
        }
        u.put("version", 1);
        u.put("createdAt", Instant.parse("2026-10-23T00:00:00Z"));
        return u;
    }

    private static Map<String, Object> account(DemoAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", a.accountId());
        m.put("name", a.name());
        m.put("status", "active");
        m.put("owningTeam", a.owningTeam());
        m.put("defaultRegion", a.defaultRegion());
        m.put("version", 1);
        m.put("createdAt", Instant.parse("2026-10-23T00:00:00Z"));
        return m;
    }

    private Mono<ServerResponse> denied(DemoCaller caller, String accountId, String action) {
        String role = RoleConventions.strongest(directory.rolesOf(caller.personSubject(), accountId));
        Denial denial = Denial.byPolicy(action, "wfrn:wf:iam:global:" + accountId + ":*", "No statement allows " + action + " for role " + role, role,
                "managed/" + role, ManagedRoles.statementFor(role, action));
        return ServerResponse.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("error", denial.reason(), "denied", denial));
    }

    private void invalidate(String accountId) {
        Access a = access.getIfAvailable();
        if (a != null) {
            a.invalidate(accountId);
        }
    }

    private static Map<String, Object> page(List<Map<String, Object>> items) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("next", null);
        return page;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static Mono<ServerResponse> json(HttpStatus status, Object body) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private static Mono<ServerResponse> error(HttpStatus status, String message) {
        return json(status, Map.of("error", message));
    }
}
