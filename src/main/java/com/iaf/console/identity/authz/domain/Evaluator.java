package com.iaf.console.identity.authz.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The decision procedure, the twin of {@code opa/console/authz.rego}; the conformance test runs
 * both on the same fixtures. In order: unknown account, service principal, inactive account, not
 * a member, suspended, then the statements of the user's roles (roles by name, policies in role
 * order, statements in document order): the first matching deny wins, else the first matching
 * allow, else a default deny that names the managed role that would allow it.
 */
public final class Evaluator {

    /** The managed roles from the least to the most permissive, the order a suggestion is searched in. */
    public static final List<String> MANAGED_ORDER = List.of("viewer", "auditor", "operator", "account-admin");

    private Evaluator() {
    }

    /** A statement with the role and policy it came through. */
    record Bound(String role, String policyId, CompiledAccount.CompiledStatement statement) {
    }

    public static Decision decide(Compiled compiled, Principal principal, Check check) {
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
        List<Bound> statements = statements(account, user.groups());
        Bound deny = first(principal, user, check, statements, "deny");
        if (deny != null) {
            CompiledAccount.CompiledPolicy policy = account.policies().get(deny.policyId());
            return new Decision(false, "denied by policy " + (policy == null ? deny.policyId() : policy.name()) + ", statement " + deny.statement().sid(),
                    deny.policyId(), deny.statement().sid(), deny.role(), null, null);
        }
        Bound allow = first(principal, user, check, statements, "allow");
        if (allow != null) {
            return new Decision(true, "allowed by role " + allow.role(), allow.policyId(), allow.statement().sid(), allow.role(), null, null);
        }
        String reason = "no policy allows " + check.action() + " on " + check.resource();
        for (String role : MANAGED_ORDER) {
            if (account.roles().containsKey(role) && roleAllows(account, principal, user, check, role)) {
                return new Decision(false, reason, null, null, null, true, role);
            }
        }
        return new Decision(false, reason, null, null, null, false, null);
    }

    /** The statements of these groups' roles, in the order the Rego policy uses: roles sorted by name, policies in role order, statements in order. */
    static List<Bound> statements(CompiledAccount account, List<String> groups) {
        TreeSet<String> roles = new TreeSet<>();
        for (String group : groups) {
            CompiledAccount.CompiledGroup g = account.groups().get(group);
            if (g != null) {
                roles.addAll(g.roles());
            }
        }
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
        return first(principal, user, check, statements, "deny") == null && first(principal, user, check, statements, "allow") != null;
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
