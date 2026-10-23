package com.iaf.console.identity.iam.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One statement of a policy: an effect on actions ({@code <service>:<verb>}, {@code *} allowed)
 * over wfrn patterns, under conditions that must all hold. {@code notActions} (this service's
 * extension, optional) carves actions out of an allow, so a role can grant "everything but
 * delete" without a deny that would also override the user's other roles.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Statement(String sid, Effect effect, List<String> actions, List<String> notActions, List<String> resources, Map<String, Object> conditions) {

    public enum Effect {
        allow, deny
    }

    public Statement {
        actions = actions == null ? List.of() : List.copyOf(actions);
        notActions = notActions == null || notActions.isEmpty() ? null : List.copyOf(notActions);
        resources = resources == null ? List.of() : List.copyOf(resources);
        conditions = conditions == null || conditions.isEmpty() ? null : Map.copyOf(conditions);
    }

    public static Statement allow(String sid, List<String> actions, List<String> resources) {
        return new Statement(sid, Effect.allow, actions, null, resources, null);
    }

    public static Statement allowExcept(String sid, List<String> actions, List<String> notActions, List<String> resources) {
        return new Statement(sid, Effect.allow, actions, notActions, resources, null);
    }

    public static Statement deny(String sid, List<String> actions, List<String> resources) {
        return new Statement(sid, Effect.deny, actions, null, resources, null);
    }

    public List<String> notActionsOrEmpty() {
        return notActions == null ? List.of() : notActions;
    }

    public Map<String, Object> conditionsOrEmpty() {
        return conditions == null ? Map.of() : conditions;
    }
}
