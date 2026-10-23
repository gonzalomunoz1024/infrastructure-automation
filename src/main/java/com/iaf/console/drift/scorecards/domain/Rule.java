package com.iaf.console.drift.scorecards.domain;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.drift.domain.Severity;

/**
 * One rule of a pack: what it applies to ({@code types}, {@code when}), what passes
 * ({@code check}), what a failing resource is told, its Rego for the firm's OPA engine, its
 * optional fix, and its examples. {@link #judge} is the embedded engine's verdict: the
 * declarative half of the rule.
 */
public record Rule(String pack, String id, String title, String description, Severity severity, Set<String> types, Condition when, Condition check,
        String message, String rego, RemediationSpec remediation, List<Example> examples) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    /** An input and the verdict the pack expects: pass, fail or not-applicable. */
    public record Example(JsonNode input, String expect) {
    }

    public Rule {
        types = Set.copyOf(types);
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    public RuleKey key() {
        return new RuleKey(pack, id);
    }

    public boolean appliesToType(String type) {
        return types.contains("*") || types.contains(type);
    }

    /** The declarative verdict for an input. */
    public Verdict judge(JsonNode input) {
        JsonNode type = Condition.valueAt(input, "resource.type");
        if (type == null || !appliesToType(type.asText()) || when != null && !when.holds(input)) {
            return Verdict.NOT_APPLICABLE;
        }
        return check.holds(input) ? Verdict.pass() : Verdict.fail(message(input));
    }

    /** The message with {@code {{ path }}} filled in from the input (empty where missing). */
    public String message(JsonNode input) {
        Matcher m = PLACEHOLDER.matcher(message);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            JsonNode v = Condition.valueAt(input, m.group(1));
            String text = v == null ? "" : v.isValueNode() ? v.asText() : v.toString();
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The Rego package the rule's policy declares: {@code console.scorecards.<pack>.<rule>}, dashes as underscores. */
    public String regoPackage() {
        return "console.scorecards." + pack.replace('-', '_') + "." + id.replace('-', '_');
    }
}
