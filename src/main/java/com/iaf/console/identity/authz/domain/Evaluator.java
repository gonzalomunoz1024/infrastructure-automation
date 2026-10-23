package com.iaf.console.identity.authz.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The decision procedure, the twin of {@code opa/console/authz.rego}; the conformance test runs
 * both on the same fixtures. In order: unknown account, service principal, inactive account, not
 * a member, suspended; then the organization's guardrails that apply to the account (their first
 * matching deny, then, when any of them allows something, an action none of them allows); then
 * the statements of the user's roles (the roles of their groups and the roles they hold for a
 * while, by name; policies in role order; statements in document order): the first matching
 * deny wins, else the first matching allow whose role's permission boundary also allows it, else
 * a deny naming the boundary that refused a matching allow, else a default deny that names the
 * managed role that would allow it.
 */
public final class Evaluator {

    /** The managed roles from the least to the most permissive, the order a suggestion is searched in. */
    public static final List<String> MANAGED_ORDER = List.of("viewer", "auditor", "operator", "account-admin");

    private Evaluator() {
    }

    /** A statement with the role and policy it came through. */
    record Bound(String role, String policyId, CompiledAccount.CompiledStatement statement) {
    }

    /** A statement of a guardrail, with the guardrail's id and name. */
    record Rail(String guardrailId, String name, CompiledAccount.CompiledStatement statement) {
    }

    public static Decision decide(Compiled compiled, Principal principal, Check check) {
        return decide(compiled, principal, check, Instant.now());
    }

    /** One decision at {@code now}, which says which time-boxed roles still hold. */
    public static Decision decide(Compiled compiled, Principal principal, Check check, Instant now) {
        CompiledAccount account = principal.accountId() == null ? null : compiled.accounts().get(principal.accountId());
        if (account == null) {
            return Decision.deny("account unknown");
        }
        if (principal.isService()) {
            return Decision.allow("service principal");
        }
        if (!"active".equals(account.status())) {
            return Decision.deny("account " + account.status());
        }
        CompiledAccount.CompiledUser user = principal.userId() == null ? null : account.users().get(principal.userId());
        if (user == null) {
            return Decision.deny("not a member of the account");
        }
        if (!"active".equals(user.status())) {
            return Decision.deny("user suspended");
        }
        List<Rail> rails = rails(compiled, account);
        Rail railDeny = firstRail(principal, user, check, rails, "deny");
        if (railDeny != null) {
            return new Decision(false, "denied by guardrail " + railDeny.name() + ", statement " + railDeny.statement().sid(), null,
                    railDeny.statement().sid(), null, false, null, Decision.GUARDRAIL, railDeny.guardrailId(), null);
        }
        if (rails.stream().anyMatch(r -> "allow".equals(r.statement().effect())) && firstRail(principal, user, check, rails, "allow") == null) {
            return new Decision(false, "outside the organization's guardrails", null, null, null, false, null, Decision.GUARDRAIL, null, null);
        }
        Set<String> groupRoles = groupRoles(account, user.groups());
        TreeSet<String> roles = new TreeSet<>(groupRoles);
        roles.addAll(grantRoles(user, now));
        List<Bound> statements = statementsOfRoles(account, roles);
        Bound deny = first(principal, user, check, statements, "deny");
        if (deny != null) {
            CompiledAccount.CompiledPolicy policy = account.policies().get(deny.policyId());
            return new Decision(false, "denied by policy " + (policy == null ? deny.policyId() : policy.name()) + ", statement " + deny.statement().sid(),
                    deny.policyId(), deny.statement().sid(), deny.role(), null, null, Decision.POLICY, null, elevated(groupRoles, deny.role()));
        }
        Bound bounded = null;
        for (Bound b : statements) {
            if ("allow".equals(b.statement().effect()) && matches(principal, user, check, b.statement())) {
                if (boundaryPermits(account, principal, user, check, b.role())) {
                    return new Decision(true, "allowed by role " + b.role(), b.policyId(), b.statement().sid(), b.role(), null, null, Decision.POLICY, null,
                            elevated(groupRoles, b.role()));
                }
                if (bounded == null) {
                    bounded = b;
                }
            }
        }
        if (bounded != null) {
            String boundary = account.roles().get(bounded.role()).boundary();
            CompiledAccount.CompiledPolicy policy = account.policies().get(boundary);
            return new Decision(false, "outside the permission boundary " + (policy == null ? boundary : policy.name()) + " of role " + bounded.role(),
                    boundary, null, bounded.role(), false, null, Decision.BOUNDARY, null, null);
        }
        String reason = "no policy allows " + check.action() + " on " + check.resource();
        for (String role : MANAGED_ORDER) {
            if (account.roles().containsKey(role) && roleAllows(account, principal, user, check, role)) {
                return new Decision(false, reason, null, null, null, true, role);
            }
        }
        return new Decision(false, reason, null, null, null, false, null);
    }

    /** The roles the user's groups hold, by name. */
    static Set<String> groupRoles(CompiledAccount account, List<String> groups) {
        Set<String> roles = new TreeSet<>();
        for (String group : groups) {
            CompiledAccount.CompiledGroup g = account.groups().get(group);
            if (g != null) {
                roles.addAll(g.roles());
            }
        }
        return roles;
    }

    /** The roles the user holds for a while that still hold at {@code now}. */
    static Set<String> grantRoles(CompiledAccount.CompiledUser user, Instant now) {
        Set<String> roles = new TreeSet<>();
        for (CompiledAccount.CompiledGrant grant : user.grantsOrEmpty()) {
            if (grant.until() > now.getEpochSecond()) {
                roles.add(grant.role());
            }
        }
        return roles;
    }

    private static Boolean elevated(Set<String> groupRoles, String role) {
        return groupRoles.contains(role) ? null : Boolean.TRUE;
    }

    /** The statements of these groups' roles, in the order the Rego policy uses: roles sorted by name, policies in role order, statements in order. */
    static List<Bound> statements(CompiledAccount account, List<String> groups) {
        return statementsOfRoles(account, new TreeSet<>(groupRoles(account, groups)));
    }

    static List<Bound> statementsOfRoles(CompiledAccount account, TreeSet<String> roles) {
        List<Bound> bound = new ArrayList<>();
        for (String role : roles) {
            bound.addAll(statementsOfRole(account, role));
        }
        return bound;
    }

    static List<Bound> statementsOfRole(CompiledAccount account, String role) {
        List<Bound> bound = new ArrayList<>();
        CompiledAccount.CompiledRole r = account.roles().get(role);
        if (r == null) {
            return bound;
        }
        for (String policyId : r.policies()) {
            CompiledAccount.CompiledPolicy p = account.policies().get(policyId);
            if (p == null) {
                continue;
            }
            for (CompiledAccount.CompiledStatement s : p.statements()) {
                bound.add(new Bound(role, policyId, s));
            }
        }
        return bound;
    }

    /** The statements of the guardrails that apply to the account, guardrails in the account's order, statements in order. */
    static List<Rail> rails(Compiled compiled, CompiledAccount account) {
        List<Rail> rails = new ArrayList<>();
        for (String id : account.guardrailsOrEmpty()) {
            Compiled.CompiledGuardrail guardrail = compiled.guardrails().get(id);
            if (guardrail == null) {
                continue;
            }
            for (CompiledAccount.CompiledStatement s : guardrail.statements()) {
                rails.add(new Rail(id, guardrail.name(), s));
            }
        }
        return rails;
    }

    private static Rail firstRail(Principal principal, CompiledAccount.CompiledUser user, Check check, List<Rail> rails, String effect) {
        for (Rail r : rails) {
            if (effect.equals(r.statement().effect()) && matches(principal, user, check, r.statement())) {
                return r;
            }
        }
        return null;
    }

    /** Whether the role's permission boundary, when it has one, allows the check: no deny of it matches and an allow does; a missing boundary policy allows nothing. */
    static boolean boundaryPermits(CompiledAccount account, Principal principal, CompiledAccount.CompiledUser user, Check check, String role) {
        CompiledAccount.CompiledRole r = account.roles().get(role);
        if (r == null || r.boundary() == null) {
            return true;
        }
        CompiledAccount.CompiledPolicy boundary = account.policies().get(r.boundary());
        if (boundary == null) {
            return false;
        }
        boolean allowed = false;
        for (CompiledAccount.CompiledStatement s : boundary.statements()) {
            if (matches(principal, user, check, s)) {
                if ("deny".equals(s.effect())) {
                    return false;
                }
                allowed = allowed || "allow".equals(s.effect());
            }
        }
        return allowed;
    }

    private static Bound first(Principal principal, CompiledAccount.CompiledUser user, Check check, List<Bound> statements, String effect) {
        for (Bound b : statements) {
            if (effect.equals(b.statement().effect()) && matches(principal, user, check, b.statement())) {
                return b;
            }
        }
        return null;
    }

    private static boolean roleAllows(CompiledAccount account, Principal principal, CompiledAccount.CompiledUser user, Check check, String role) {
        List<Bound> statements = statementsOfRole(account, role);
        return first(principal, user, check, statements, "deny") == null && first(principal, user, check, statements, "allow") != null
                && boundaryPermits(account, principal, user, check, role);
    }

    public static boolean matches(Principal principal, CompiledAccount.CompiledUser user, Check check, CompiledAccount.CompiledStatement s) {
        return actionMatches(s, check.action()) && resourceMatches(s, check.resource()) && conditionsHold(principal, user, check, s);
    }

    static boolean actionMatches(CompiledAccount.CompiledStatement s, String action) {
        boolean any = false;
        for (String pattern : s.actions()) {
            if (Globs.matches(pattern, action)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return false;
        }
        if (s.notActions() != null) {
            for (String pattern : s.notActions()) {
                if (Globs.matches(pattern, action)) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean resourceMatches(CompiledAccount.CompiledStatement s, String resource) {
        String sentinel = Globs.sentinel(resource);
        for (String pattern : s.resources()) {
            if (Globs.matches(pattern, sentinel)) {
                return true;
            }
        }
        return false;
    }

    static boolean conditionsHold(Principal principal, CompiledAccount.CompiledUser user, Check check, CompiledAccount.CompiledStatement s) {
        if (s.conditions() == null || s.conditions().isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> condition : s.conditions().entrySet()) {
            if (!conditionHolds(principal, user, check, condition.getKey(), condition.getValue())) {
                return false;
            }
        }
        return true;
    }

    static boolean conditionHolds(Principal principal, CompiledAccount.CompiledUser user, Check check, String key, Object want) {
        if ("requester.group".equals(key)) {
            for (String group : user.groups()) {
                if (valueMatches(group, want)) {
                    return true;
                }
            }
            return false;
        }
        Object got = derived(principal, check, key);
        return got != null && valueMatches(got, want);
    }

    /** The value a condition key names, from the resource's wfrn, the principal or the check's context; null when there is none. */
    static Object derived(Principal principal, Check check, String key) {
        switch (key) {
            case "region":
                return segment(check.resource(), 3);
            case "account":
                return segment(check.resource(), 4);
            case "service":
                return segment(check.resource(), 2);
            case "type": {
                String typeAndId = segment(check.resource(), 5);
                return typeAndId == null ? null : typeAndId.split("/", 2)[0];
            }
            case "requester.user":
                return principal.userId();
            case "requester.email": {
                Object requester = check.contextOrEmpty().get("requester");
                return requester instanceof Map<?, ?> m ? m.get("email") : null;
            }
            default:
                if (key.startsWith("tag.")) {
                    Object tags = check.contextOrEmpty().get("tags");
                    return tags instanceof Map<?, ?> m ? m.get(key.substring(4)) : null;
                }
                if (key.startsWith("context.")) {
                    return check.contextOrEmpty().get(key.substring(8));
                }
                return null;
        }
    }

    static String segment(String wfrn, int index) {
        if (wfrn == null) {
            return null;
        }
        String[] parts = wfrn.split(":", -1);
        return parts.length > index ? parts[index] : null;
    }

    /** JSON equality: a list wants any of its values, a scalar wants itself; numbers compare by value, strings by text. */
    static boolean valueMatches(Object got, Object want) {
        if (want instanceof List<?> options) {
            for (Object option : options) {
                if (valueMatches(got, option)) {
                    return true;
                }
            }
            return false;
        }
        if (got instanceof Number a && want instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        if (got instanceof Boolean || want instanceof Boolean) {
            return String.valueOf(got).toLowerCase(Locale.ROOT).equals(String.valueOf(want).toLowerCase(Locale.ROOT)) && got.getClass() == want.getClass();
        }
        return got != null && got.equals(want);
    }
}
