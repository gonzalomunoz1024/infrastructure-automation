package com.iaf.console.identity.iam.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.iaf.console.identity.common.Names;
import com.iaf.console.wfrn.WfrnPattern;

/** The checks on a policy document before anything is written: names, actions known, resources valid wfrn patterns, conditions well-formed. */
public final class PolicyValidation {

    static final Set<String> CONDITION_KEYS = Set.of("region", "account", "service", "type", "requester.user", "requester.email", "requester.group");

    private PolicyValidation() {
    }

    public static Names.Check check(String name, List<Statement> statements, ActionCatalog catalog) {
        Names.Check check = new Names.Check();
        check.require(Names.isName(name), "name", "name matches ^[a-z0-9-]{2,64}$");
        if (statements == null || statements.isEmpty()) {
            return check.problem("invalid", "statements", "at least one statement is required");
        }
        if (statements.size() > Policy.MAX_STATEMENTS) {
            return check.problem("invalid", "statements", "at most " + Policy.MAX_STATEMENTS + " statements");
        }
        Set<String> sids = new HashSet<>();
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
            String at = "statements[" + i + "]";
            if (s == null) {
                check.problem("invalid", at, "a statement is required");
                continue;
            }
            check.require(s.effect() != null, at + ".effect", "effect is allow or deny");
            if (s.sid() != null && !sids.add(s.sid())) {
                check.problem("invalid", at + ".sid", "sid '" + s.sid() + "' is used twice");
            }
            check.require(!s.actions().isEmpty(), at + ".actions", "at least one action is required");
            for (int j = 0; j < s.actions().size(); j++) {
                checkAction(check, at + ".actions[" + j + "]", s.actions().get(j), catalog);
            }
            for (int j = 0; j < s.notActionsOrEmpty().size(); j++) {
                checkAction(check, at + ".notActions[" + j + "]", s.notActionsOrEmpty().get(j), catalog);
            }
            check.require(s.notActions() == null || s.effect() == Statement.Effect.allow, at + ".notActions", "notActions is for allow statements");
            check.require(!s.resources().isEmpty(), at + ".resources", "at least one resource is required");
            for (int j = 0; j < s.resources().size(); j++) {
                String r = s.resources().get(j);
                if (!isPattern(r)) {
                    check.problem("invalid", at + ".resources[" + j + "]", "'" + r + "' is not a wfrn pattern");
                }
            }
            for (Map.Entry<String, Object> c : s.conditionsOrEmpty().entrySet()) {
                String key = c.getKey();
                boolean known = CONDITION_KEYS.contains(key) || key.startsWith("tag.") || key.startsWith("context.");
                check.require(known, at + ".conditions." + key, "a condition key is region, account, service, type, requester.user, requester.email, "
                        + "requester.group, tag.<name> or context.<name>");
                Object v = c.getValue();
                boolean scalar = v instanceof String || v instanceof Number || v instanceof Boolean;
                boolean list = v instanceof List<?> l && l.stream().allMatch(x -> x instanceof String || x instanceof Number || x instanceof Boolean);
                check.require(scalar || list, at + ".conditions." + key, "a condition value is a string, number, boolean or a list of them");
            }
        }
        return check;
    }

    public static boolean isPattern(String r) {
        if (r == null) {
            return false;
        }
        try {
            WfrnPattern.parse(r);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void checkAction(Names.Check check, String field, String action, ActionCatalog catalog) {
        if (!Actions.isValid(action)) {
            check.problem("invalid", field, "'" + action + "' is not <service>:<verb>");
            return;
        }
        if (catalog != null && !catalog.knows(action)) {
            check.problem("unknown-action", field, "'" + action + "' names no known service or verb");
        }
    }
}
