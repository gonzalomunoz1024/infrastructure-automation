package com.iaf.console.drift.scorecards.domain;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iaf.console.drift.drift.domain.Json;

/**
 * Fills a remediation's values: {@code {{ $input.<name> }}} is what the person supplied (or the
 * input's default), {@code {{ $resource.<path> }}} a value of the evaluation input's resource. A
 * string that is exactly one placeholder takes the type of what it names ({@code cpu: "{{ $input.cpu }}"}
 * sends a number); within a longer string it is written as text. A placeholder that names nothing
 * becomes null, or an empty string within text.
 */
public final class Templates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*\\$(input|resource)\\.([A-Za-z0-9_.-]+)\\s*}}");

    private Templates() {
    }

    public static JsonNode render(JsonNode template, ObjectNode inputs, JsonNode resource) {
        if (template == null) {
            return null;
        }
        if (template.isTextual()) {
            String text = template.asText();
            Matcher whole = PLACEHOLDER.matcher(text.trim());
            if (whole.matches()) {
                JsonNode v = lookup(whole.group(1), whole.group(2), inputs, resource);
                return v == null ? Json.MAPPER.nullNode() : v.deepCopy();
            }
            Matcher m = PLACEHOLDER.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                JsonNode v = lookup(m.group(1), m.group(2), inputs, resource);
                m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : v.isValueNode() ? v.asText() : v.toString()));
            }
            m.appendTail(out);
            return TextNode.valueOf(out.toString());
        }
        if (template.isObject()) {
            ObjectNode out = Json.object();
            for (Iterator<Map.Entry<String, JsonNode>> it = template.properties().iterator(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                out.set(e.getKey(), render(e.getValue(), inputs, resource));
            }
            return out;
        }
        if (template.isArray()) {
            ArrayNode out = Json.MAPPER.createArrayNode();
            template.forEach(item -> out.add(render(item, inputs, resource)));
            return out;
        }
        return template.deepCopy();
    }

    /** Whether a value is a template at all. */
    public static boolean templated(JsonNode value) {
        return value != null && value.isTextual() && PLACEHOLDER.matcher(value.asText()).find();
    }

    private static JsonNode lookup(String scope, String path, ObjectNode inputs, JsonNode resource) {
        if ("input".equals(scope)) {
            JsonNode v = inputs == null ? null : inputs.get(path);
            return v == null || v.isNull() ? null : v;
        }
        ObjectNode wrapper = Json.object();
        wrapper.set("resource", resource);
        return Condition.valueAt(wrapper, "resource." + path);
    }
}
