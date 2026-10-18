package com.iaf.platform.common.expressions;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * The platform's expression language: n8n-style {@code {{ $json.vm.id }}} templates with a small,
 * deterministic grammar (member access, comparisons, boolean logic, arithmetic, a few string and
 * array methods). Pure: evaluation only reads the {@link ExpressionContext}, never the clock or
 * the network, so it is safe inside a Temporal workflow.
 *
 * <p>A string that is exactly one expression yields the typed value; a string with text around
 * expressions yields text.</p>
 */
public final class ExpressionEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private ExpressionEngine() {
    }

    public static boolean isTemplate(String s) {
        return s != null && s.contains("{{");
    }

    /** Evaluate a template string. Non-template strings come back as text unchanged. */
    public static JsonNode evaluate(String template, ExpressionContext ctx) {
        if (template == null) {
            return NullNode.getInstance();
        }
        if (!isTemplate(template)) {
            return new TextNode(template);
        }
        String t = template.trim();
        if (t.startsWith("{{") && t.endsWith("}}") && t.indexOf("{{", 2) < 0) {
            return nullSafe(new Parser(t.substring(2, t.length() - 2)).parseAll().eval(ctx));
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("{{", i);
            if (open < 0) {
                out.append(template, i, template.length());
                break;
            }
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                throw new ExpressionException("Unclosed {{ in \"" + template + "\"");
            }
            out.append(template, i, open);
            out.append(asText(new Parser(template.substring(open + 2, close)).parseAll().eval(ctx)));
            i = close + 2;
        }
        return new TextNode(out.toString());
    }

    /** Resolve every template string inside a JSON value, recursively. */
    public static JsonNode resolve(JsonNode value, ExpressionContext ctx) {
        if (value == null || value.isNull()) {
            return NullNode.getInstance();
        }
        if (value.isTextual()) {
            return evaluate(value.asText(), ctx);
        }
        if (value.isObject()) {
            ObjectNode out = F.objectNode();
            value.properties().forEach(e -> out.set(e.getKey(), resolve(e.getValue(), ctx)));
            return out;
        }
        if (value.isArray()) {
            ArrayNode out = F.arrayNode();
            value.forEach(v -> out.add(resolve(v, ctx)));
            return out;
        }
        return value;
    }

    /** Evaluate a template and judge it as a boolean (asserts, switch cases). */
    public static boolean test(String template, ExpressionContext ctx) {
        return truthy(evaluate(template, ctx));
    }

    /** Every data reference in a template, for the checks. Parse errors yield no references. */
    public static List<Reference> references(String template) {
        List<Reference> refs = new ArrayList<>();
        if (!isTemplate(template)) {
            return refs;
        }
        int i = 0;
        while (true) {
            int open = template.indexOf("{{", i);
            if (open < 0) {
                break;
            }
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                break;
            }
            try {
                new Parser(template.substring(open + 2, close)).parseAll().collect(refs);
            } catch (ExpressionException ignored) {
                // a broken expression is reported by the syntax check, not here
            }
            i = close + 2;
        }
        return refs;
    }

    /** References inside every string of a JSON value. */
    public static List<Reference> references(JsonNode value) {
        List<Reference> refs = new ArrayList<>();
        walk(value, refs);
        return refs;
    }

    private static void walk(JsonNode v, List<Reference> refs) {
        if (v == null) {
            return;
        }
        if (v.isTextual()) {
            refs.addAll(references(v.asText()));
        } else if (v.isContainerNode()) {
            v.forEach(c -> walk(c, refs));
        }
    }

    /** Syntax check of every template in a value; returns the first error message or null. */
    public static String syntaxError(String template) {
        if (!isTemplate(template)) {
            return null;
        }
        int i = 0;
        while (true) {
            int open = template.indexOf("{{", i);
            if (open < 0) {
                return null;
            }
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                return "Unclosed {{";
            }
            try {
                new Parser(template.substring(open + 2, close)).parseAll();
            } catch (ExpressionException e) {
                return e.getMessage();
            }
            i = close + 2;
        }
    }

    // ---------------------------------------------------------------- helpers

    public static boolean truthy(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return false;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isNumber()) {
            return n.asDouble() != 0;
        }
        if (n.isTextual()) {
            return !n.asText().isEmpty();
        }
        return true;
    }

    public static String asText(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return "";
        }
        if (n.isValueNode()) {
            return n.asText();
        }
        return n.toString();
    }

    private static JsonNode nullSafe(JsonNode n) {
        return n == null || n.isMissingNode() ? NullNode.getInstance() : n;
    }

    private static JsonNode number(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return new LongNode((long) d);
        }
        return new DoubleNode(d);
    }

    private static boolean equal(JsonNode a, JsonNode b) {
        a = nullSafe(a);
        b = nullSafe(b);
        if (a.isNumber() && b.isNumber()) {
            return a.asDouble() == b.asDouble();
        }
        if (a.isValueNode() && b.isValueNode() && !(a.isNull() ^ b.isNull())) {
            return a.isNull() || a.asText().equals(b.asText());
        }
        return a.equals(b);
    }

    private static int compare(JsonNode a, JsonNode b, String op) {
        a = nullSafe(a);
        b = nullSafe(b);
        if (a.isNumber() && b.isNumber()) {
            return Double.compare(a.asDouble(), b.asDouble());
        }
        if (a.isNull() || b.isNull()) {
            throw new ExpressionException("Cannot compare null with " + op);
        }
        return asText(a).compareTo(asText(b));
    }

    // ---------------------------------------------------------------- AST

    private interface Node {
        JsonNode eval(ExpressionContext ctx);

        default void collect(List<Reference> refs) {
        }
    }

    private record Literal(JsonNode value) implements Node {
        @Override
        public JsonNode eval(ExpressionContext ctx) {
            return value;
        }
    }

    /** {@code $json}, {@code $input}, {@code $('step')}, {@code $alias}. */
    private record Root(String name, String stepName) implements Node {
        @Override
        public JsonNode eval(ExpressionContext ctx) {
            if (stepName != null) {
                JsonNode out = ctx.step(stepName);
                if (out == null) {
                    throw new ExpressionException("Unknown step '" + stepName + "'");
                }
                ObjectNode item = F.objectNode();
                item.set("json", out);
                ObjectNode ref = F.objectNode();
                ref.set("json", out);
                ref.set("item", item);
                return ref;
            }
            return switch (name) {
                case "json" -> ctx.json();
                case "input" -> ctx.input();
                case "vars" -> ctx.vars();
                case "job" -> ctx.job();
                case "env" -> ctx.env();
                case "error" -> ctx.error();
                default -> {
                    JsonNode a = ctx.alias(name);
                    if (a == null) {
                        throw new ExpressionException("Unknown reference $" + name);
                    }
                    yield a;
                }
            };
        }

        Reference.Kind kind() {
            if (stepName != null) {
                return Reference.Kind.STEP;
            }
            return switch (name) {
                case "json" -> Reference.Kind.JSON;
                case "input" -> Reference.Kind.INPUT;
                case "vars" -> Reference.Kind.VARS;
                case "job" -> Reference.Kind.JOB;
                case "env" -> Reference.Kind.ENV;
                case "error" -> Reference.Kind.ERROR;
                default -> Reference.Kind.ALIAS;
            };
        }
    }

    private record Member(Node target, String field) implements Node {
        @Override
        public JsonNode eval(ExpressionContext ctx) {
            JsonNode t = nullSafe(target.eval(ctx));
            if ("length".equals(field)) {
                if (t.isArray()) {
                    return new LongNode(t.size());
                }
                if (t.isTextual()) {
                    return new LongNode(t.asText().length());
                }
                if (t.isObject()) {
                    return new LongNode(t.size());
                }
                return NullNode.getInstance();
            }
            if (t.isObject()) {
                return nullSafe(t.get(field));
            }
            return NullNode.getInstance();
        }

        @Override
        public void collect(List<Reference> refs) {
            Reference r = path();
            if (r != null) {
                refs.add(r);
            } else {
                target.collect(refs);
            }
        }

        /** The dotted path from a root, or null when the target is not a plain member chain. */
        Reference path() {
            List<String> parts = new ArrayList<>();
            Node n = this;
            while (n instanceof Member m) {
                parts.add(0, m.field());
                n = m.target();
            }
            if (n instanceof Root root) {
                // for $('step').json.a.b drop the leading json / item.json
                if (root.stepName() != null) {
                    if (!parts.isEmpty() && parts.get(0).equals("item")) {
                        parts.remove(0);
                    }
                    if (!parts.isEmpty() && parts.get(0).equals("json")) {
                        parts.remove(0);
                    }
                }
                String p = String.join(".", parts);
                if ("length".equals(parts.isEmpty() ? "" : parts.get(parts.size() - 1))) {
                    p = String.join(".", parts.subList(0, parts.size() - 1));
                }
                return new Reference(root.kind(), root.stepName() != null ? root.stepName() : (root.kind() == Reference.Kind.ALIAS ? root.name() : null), p);
            }
            return null;
        }
    }

    private record Index(Node target, Node index) implements Node {
        @Override
        public JsonNode eval(ExpressionContext ctx) {
            JsonNode t = nullSafe(target.eval(ctx));
            JsonNode i = nullSafe(index.eval(ctx));
            if (t.isArray() && i.isNumber()) {
                return nullSafe(t.get(i.asInt()));
            }
            if (t.isObject()) {
                return nullSafe(t.get(asText(i)));
            }
            return NullNode.getInstance();
        }

        @Override
        public void collect(List<Reference> refs) {
            target.collect(refs);
            index.collect(refs);
        }
    }

    private record Call(Node target, String method, List<Node> args) implements Node {
        @Override
        public JsonNode eval(ExpressionContext ctx) {
            JsonNode t = nullSafe(target.eval(ctx));
            List<JsonNode> a = args.stream().map(n -> n instanceof Lambda ? NullNode.getInstance() : nullSafe(n.eval(ctx))).toList();
            return switch (method) {
                case "toUpperCase" -> new TextNode(asText(t).toUpperCase());
                case "toLowerCase" -> new TextNode(asText(t).toLowerCase());
                case "trim" -> new TextNode(asText(t).trim());
                case "toString" -> new TextNode(asText(t));
                case "toNumber" -> {
                    try {
                        yield number(Double.parseDouble(asText(t).trim()));
                    } catch (NumberFormatException e) {
                        yield NullNode.getInstance();
                    }
                }
                case "includes", "contains" -> {
                    if (t.isArray()) {
                        for (JsonNode v : t) {
                            if (equal(v, a.get(0))) {
                                yield BooleanNode.TRUE;
                            }
                        }
                        yield BooleanNode.FALSE;
                    }
                    yield BooleanNode.valueOf(asText(t).contains(asText(a.get(0))));
                }
                case "startsWith" -> BooleanNode.valueOf(asText(t).startsWith(asText(a.get(0))));
                case "endsWith" -> BooleanNode.valueOf(asText(t).endsWith(asText(a.get(0))));
                case "split" -> {
                    ArrayNode out = F.arrayNode();
                    for (String s : asText(t).split(java.util.regex.Pattern.quote(asText(a.get(0))), -1)) {
                        out.add(s);
                    }
                    yield out;
                }
                case "join" -> {
                    List<String> parts = new ArrayList<>();
                    t.forEach(v -> parts.add(asText(v)));
                    yield new TextNode(String.join(a.isEmpty() ? "," : asText(a.get(0)), parts));
                }
                case "first" -> t.isArray() && t.size() > 0 ? t.get(0) : NullNode.getInstance();
                case "last" -> t.isArray() && t.size() > 0 ? t.get(t.size() - 1) : NullNode.getInstance();
                case "keys" -> {
                    ArrayNode out = F.arrayNode();
                    t.fieldNames().forEachRemaining(out::add);
                    yield out;
                }
                case "isEmpty" -> BooleanNode.valueOf(!truthy(t) || (t.isContainerNode() && t.size() == 0));
                case "isNotEmpty" -> BooleanNode.valueOf(truthy(t) && !(t.isContainerNode() && t.size() == 0));
                case "replace" -> new TextNode(asText(t).replace(asText(a.get(0)), asText(a.get(1))));
                case "substring", "slice" -> {
                    int from = a.isEmpty() ? 0 : a.get(0).asInt();
                    if (t.isArray()) {
                        int size = t.size();
                        int start = from < 0 ? Math.max(0, size + from) : Math.min(from, size);
                        int end = a.size() > 1 ? (a.get(1).asInt() < 0 ? Math.max(0, size + a.get(1).asInt()) : Math.min(a.get(1).asInt(), size)) : size;
                        ArrayNode out = F.arrayNode();
                        for (int i = start; i < end; i++) {
                            out.add(t.get(i));
                        }
                        yield out;
                    }
                    String s = asText(t);
                    int start = Math.max(0, Math.min(from < 0 ? s.length() + from : from, s.length()));
                    int end = a.size() > 1 ? Math.max(start, Math.min(a.get(1).asInt() < 0 ? s.length() + a.get(1).asInt() : a.get(1).asInt(), s.length())) : s.length();
                    yield new TextNode(s.substring(start, end));
                }
                case "concat" -> {
                    if (t.isArray()) {
                        ArrayNode out = F.arrayNode();
                        out.addAll((ArrayNode) t);
                        for (JsonNode x : a) {
                            if (x.isArray()) {
                                out.addAll((ArrayNode) x);
                            } else {
                                out.add(x);
                            }
                        }
                        yield out;
                    }
                    StringBuilder sb = new StringBuilder(asText(t));
                    a.forEach(x -> sb.append(asText(x)));
                    yield new TextNode(sb.toString());
                }
                case "indexOf" -> {
                    if (t.isArray()) {
                        for (int i = 0; i < t.size(); i++) {
                            if (equal(t.get(i), a.get(0))) {
                                yield new LongNode(i);
                            }
                        }
                        yield new LongNode(-1);
                    }
                    yield new LongNode(asText(t).indexOf(asText(a.get(0))));
                }
                case "padStart" -> {
                    String s = asText(t);
                    String pad = a.size() > 1 ? asText(a.get(1)) : " ";
                    StringBuilder sb = new StringBuilder();
                    while (sb.length() + s.length() < a.get(0).asInt() && !pad.isEmpty()) {
                        sb.append(pad);
                    }
                    yield new TextNode(sb.substring(0, Math.max(0, a.get(0).asInt() - s.length())) + s);
                }
                case "toFixed" -> new TextNode(String.format(java.util.Locale.ROOT, "%." + (a.isEmpty() ? 0 : a.get(0).asInt()) + "f", t.asDouble()));
                case "sum" -> {
                    double sum = 0;
                    for (JsonNode v : t) {
                        sum += v.asDouble();
                    }
                    yield number(sum);
                }
                case "min", "max" -> {
                    JsonNode best = NullNode.getInstance();
                    for (JsonNode v : t) {
                        if (best.isNull() || ("min".equals(method) ? v.asDouble() < best.asDouble() : v.asDouble() > best.asDouble())) {
                            best = v;
                        }
                    }
                    yield best;
                }
                case "unique" -> {
                    ArrayNode out = F.arrayNode();
                    for (JsonNode v : t) {
                        boolean seen = false;
                        for (JsonNode o : out) {
                            if (equal(o, v)) {
                                seen = true;
                                break;
                            }
                        }
                        if (!seen) {
                            out.add(v);
                        }
                    }
                    yield out;
                }
                case "reverse" -> {
                    ArrayNode out = F.arrayNode();
                    for (int i = t.size() - 1; i >= 0; i--) {
                        out.add(t.get(i));
                    }
                    yield out;
                }
                case "sort" -> {
                    List<JsonNode> items = new ArrayList<>();
                    t.forEach(items::add);
                    items.sort((x, y) -> compare(x, y, "sort"));
                    ArrayNode out = F.arrayNode();
                    items.forEach(out::add);
                    yield out;
                }
                case "map", "filter", "some", "every", "find", "findIndex" -> {
                    if (args.isEmpty() || !(args.get(0) instanceof Lambda fn)) {
                        throw new ExpressionException("." + method + "() needs a function, like items." + method + "(v => v.id)");
                    }
                    ArrayNode out = F.arrayNode();
                    int i = 0;
                    for (JsonNode v : t) {
                        JsonNode r = fn.apply(ctx, v, i);
                        switch (method) {
                            case "map" -> out.add(nullSafe(r));
                            case "filter" -> {
                                if (truthy(r)) {
                                    out.add(v);
                                }
                            }
                            case "some" -> {
                                if (truthy(r)) {
                                    yield BooleanNode.TRUE;
                                }
                            }
                            case "every" -> {
                                if (!truthy(r)) {
                                    yield BooleanNode.FALSE;
                                }
                            }
                            case "find" -> {
                                if (truthy(r)) {
                                    yield v;
                                }
                            }
                            default -> {
                                if (truthy(r)) {
                                    yield new LongNode(i);
                                }
                            }
                        }
                        i++;
                    }
                    yield switch (method) {
                        case "some" -> BooleanNode.FALSE;
                        case "every" -> BooleanNode.TRUE;
                        case "find" -> NullNode.getInstance();
                        case "findIndex" -> new LongNode(-1);
                        default -> out;
                    };
                }
                default -> throw new ExpressionException("Unknown method ." + method + "()");
            };
        }

        @Override
        public void collect(List<Reference> refs) {
            target.collect(refs);
            args.forEach(n -> n.collect(refs));
        }
    }

    /** {@code v => expr} or {@code (v, i) => expr}; the parameters shadow nothing, since every reference starts with $. */
    private record Lambda(String param, String indexParam, Node body) implements Node {
        @Override
        public JsonNode eval(ExpressionContext ctx) {
            throw new ExpressionException("A function is only valid as an argument of map, filter, some, every or find");
        }

        JsonNode apply(ExpressionContext ctx, JsonNode value, int index) {
            ExpressionContext scope = ctx.bind(param, value);
            if (indexParam != null) {
                scope = scope.bind(indexParam, new LongNode(index));
            }
            return nullSafe(body.eval(scope));
        }

        @Override
        public void collect(List<Reference> refs) {
            body.collect(refs);
        }
    }

    /** A lambda parameter inside its body. */
    private record Param(String name) implements Node {
        @Override
        public JsonNode eval(ExpressionContext ctx) {
            JsonNode v = ctx.local(name);
            if (v == null) {
                throw new ExpressionException("Unknown identifier '" + name + "' (references start with $)");
            }
            return v;
        }
    }

    /** {@code $range(1, 3)}, {@code $max(a, b)}: a few pure functions. */
    private record Func(String name, List<Node> args) implements Node {
        static final List<String> NAMES = List.of("range", "max", "min", "number", "string", "keys", "length");

        @Override
        public JsonNode eval(ExpressionContext ctx) {
            List<JsonNode> a = args.stream().map(n -> nullSafe(n.eval(ctx))).toList();
            return switch (name) {
                case "range" -> {
                    ArrayNode out = F.arrayNode();
                    long from = a.size() > 1 ? a.get(0).asLong() : 0;
                    long to = a.size() > 1 ? a.get(1).asLong() : (a.isEmpty() ? 0 : a.get(0).asLong());
                    if (to - from > 10_000) {
                        throw new ExpressionException("$range is limited to 10000 items");
                    }
                    for (long i = from; i <= to; i++) {
                        out.add(i);
                    }
                    yield out;
                }
                case "max", "min" -> {
                    JsonNode best = NullNode.getInstance();
                    for (JsonNode v : a) {
                        if (best.isNull() || ("min".equals(name) ? v.asDouble() < best.asDouble() : v.asDouble() > best.asDouble())) {
                            best = v;
                        }
                    }
                    yield best;
                }
                case "number" -> {
                    try {
                        yield a.isEmpty() ? NullNode.getInstance() : number(Double.parseDouble(asText(a.get(0)).trim()));
                    } catch (NumberFormatException e) {
                        yield NullNode.getInstance();
                    }
                }
                case "string" -> new TextNode(a.isEmpty() ? "" : asText(a.get(0)));
                case "keys" -> {
                    ArrayNode out = F.arrayNode();
                    if (!a.isEmpty()) {
                        a.get(0).fieldNames().forEachRemaining(out::add);
                    }
                    yield out;
                }
                default -> new LongNode(a.isEmpty() ? 0 : (a.get(0).isContainerNode() ? a.get(0).size() : asText(a.get(0)).length()));
            };
        }

        @Override
        public void collect(List<Reference> refs) {
            args.forEach(n -> n.collect(refs));
        }
    }

    private record Unary(String op, Node operand) implements Node {
        @Override
        public JsonNode eval(ExpressionContext ctx) {
            JsonNode v = nullSafe(operand.eval(ctx));
            return switch (op) {
                case "!" -> BooleanNode.valueOf(!truthy(v));
                case "-" -> number(-v.asDouble());
                default -> throw new ExpressionException("Unknown operator " + op);
            };
        }

        @Override
        public void collect(List<Reference> refs) {
            operand.collect(refs);
        }
    }

    private record Binary(String op, Node left, Node right) implements Node {
        @Override
        public JsonNode eval(ExpressionContext ctx) {
            switch (op) {
                case "&&" -> {
                    JsonNode l = left.eval(ctx);
                    return truthy(l) ? nullSafe(right.eval(ctx)) : nullSafe(l);
                }
                case "||" -> {
                    JsonNode l = left.eval(ctx);
                    return truthy(l) ? nullSafe(l) : nullSafe(right.eval(ctx));
                }
                case "??" -> {
                    JsonNode l = nullSafe(left.eval(ctx));
                    return l.isNull() ? nullSafe(right.eval(ctx)) : l;
                }
                default -> {
                    JsonNode l = nullSafe(left.eval(ctx));
                    JsonNode r = nullSafe(right.eval(ctx));
                    return switch (op) {
                        case "==" -> BooleanNode.valueOf(equal(l, r));
                        case "!=" -> BooleanNode.valueOf(!equal(l, r));
                        case "<" -> BooleanNode.valueOf(compare(l, r, op) < 0);
                        case ">" -> BooleanNode.valueOf(compare(l, r, op) > 0);
                        case "<=" -> BooleanNode.valueOf(compare(l, r, op) <= 0);
                        case ">=" -> BooleanNode.valueOf(compare(l, r, op) >= 0);
                        case "+" -> l.isNumber() && r.isNumber() ? number(l.asDouble() + r.asDouble()) : new TextNode(asText(l) + asText(r));
                        case "-" -> number(l.asDouble() - r.asDouble());
                        case "*" -> number(l.asDouble() * r.asDouble());
                        case "/" -> number(l.asDouble() / r.asDouble());
                        case "%" -> number(l.asDouble() % r.asDouble());
                        default -> throw new ExpressionException("Unknown operator " + op);
                    };
                }
            }
        }

        @Override
        public void collect(List<Reference> refs) {
            left.collect(refs);
            right.collect(refs);
        }
    }

    private record Ternary(Node cond, Node yes, Node no) implements Node {
        @Override
        public JsonNode eval(ExpressionContext ctx) {
            return truthy(cond.eval(ctx)) ? nullSafe(yes.eval(ctx)) : nullSafe(no.eval(ctx));
        }

        @Override
        public void collect(List<Reference> refs) {
            cond.collect(refs);
            yes.collect(refs);
            no.collect(refs);
        }
    }

    private record ObjectLiteral(Map<String, Node> fields) implements Node {
        @Override
        public JsonNode eval(ExpressionContext ctx) {
            ObjectNode out = F.objectNode();
            fields.forEach((k, v) -> out.set(k, nullSafe(v.eval(ctx))));
            return out;
        }

        @Override
        public void collect(List<Reference> refs) {
            fields.values().forEach(n -> n.collect(refs));
        }
    }

    private record ArrayLiteral(List<Node> items) implements Node {
        @Override
        public JsonNode eval(ExpressionContext ctx) {
            ArrayNode out = F.arrayNode();
            items.forEach(n -> out.add(nullSafe(n.eval(ctx))));
            return out;
        }

        @Override
        public void collect(List<Reference> refs) {
            items.forEach(n -> n.collect(refs));
        }
    }

    // ---------------------------------------------------------------- parser

    private static final class Parser {
        private final String src;
        private int pos;
        /** Lambda parameters in scope while their body is parsed. */
        private final List<String> params = new ArrayList<>();

        Parser(String src) {
            this.src = src;
        }

        Node parseAll() {
            Node n = parseTernary();
            skipWs();
            if (pos < src.length()) {
                throw new ExpressionException("Unexpected '" + src.charAt(pos) + "' at " + pos + " in \"" + src.trim() + "\"");
            }
            return n;
        }

        private Node parseTernary() {
            Node cond = parseNullish();
            skipWs();
            if (peek('?') && !peekStr("??")) {
                pos++;
                Node yes = parseTernary();
                skipWs();
                expect(':');
                Node no = parseTernary();
                return new Ternary(cond, yes, no);
            }
            return cond;
        }

        private Node parseNullish() {
            Node left = parseOr();
            while (take("??")) {
                left = new Binary("??", left, parseOr());
            }
            return left;
        }

        private Node parseOr() {
            Node left = parseAnd();
            while (take("||")) {
                left = new Binary("||", left, parseAnd());
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseEquality();
            while (take("&&")) {
                left = new Binary("&&", left, parseEquality());
            }
            return left;
        }

        private Node parseEquality() {
            Node left = parseComparison();
            while (true) {
                if (take("==") || take("===")) {
                    left = new Binary("==", left, parseComparison());
                } else if (take("!=") || take("!==")) {
                    left = new Binary("!=", left, parseComparison());
                } else {
                    return left;
                }
            }
        }

        private Node parseComparison() {
            Node left = parseAdditive();
            while (true) {
                if (take("<=")) {
                    left = new Binary("<=", left, parseAdditive());
                } else if (take(">=")) {
                    left = new Binary(">=", left, parseAdditive());
                } else if (take("<")) {
                    left = new Binary("<", left, parseAdditive());
                } else if (take(">")) {
                    left = new Binary(">", left, parseAdditive());
                } else {
                    return left;
                }
            }
        }

        private Node parseAdditive() {
            Node left = parseMultiplicative();
            while (true) {
                if (take("+")) {
                    left = new Binary("+", left, parseMultiplicative());
                } else if (take("-")) {
                    left = new Binary("-", left, parseMultiplicative());
                } else {
                    return left;
                }
            }
        }

        private Node parseMultiplicative() {
            Node left = parseUnary();
            while (true) {
                if (take("*")) {
                    left = new Binary("*", left, parseUnary());
                } else if (take("/")) {
                    left = new Binary("/", left, parseUnary());
                } else if (take("%")) {
                    left = new Binary("%", left, parseUnary());
                } else {
                    return left;
                }
            }
        }

        private Node parseUnary() {
            skipWs();
            if (take("!")) {
                return new Unary("!", parseUnary());
            }
            if (take("-")) {
                return new Unary("-", parseUnary());
            }
            return parsePostfix();
        }

        private Node parsePostfix() {
            Node n = parsePrimary();
            while (true) {
                skipWs();
                if (peek('.')) {
                    pos++;
                    String name = ident();
                    skipWs();
                    if (peek('(')) {
                        pos++;
                        n = new Call(n, name, parseArgs());
                    } else {
                        n = new Member(n, name);
                    }
                } else if (peek('[')) {
                    pos++;
                    Node idx = parseTernary();
                    skipWs();
                    expect(']');
                    n = new Index(n, idx);
                } else {
                    return n;
                }
            }
        }

        private Node parsePrimary() {
            skipWs();
            if (pos >= src.length()) {
                throw new ExpressionException("Unexpected end of expression \"" + src.trim() + "\"");
            }
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                Node n = parseTernary();
                skipWs();
                expect(')');
                return n;
            }
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"' || c == '\'') {
                return new Literal(new TextNode(string()));
            }
            if (Character.isDigit(c)) {
                int start = pos;
                while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                    pos++;
                }
                return new Literal(number(Double.parseDouble(src.substring(start, pos))));
            }
            if (c == '$') {
                pos++;
                skipWs();
                if (peek('(')) {
                    pos++;
                    skipWs();
                    String step = string();
                    skipWs();
                    expect(')');
                    return new Root(null, step);
                }
                String name = ident();
                skipWs();
                if (peek('(') && Func.NAMES.contains(name)) {
                    pos++;
                    return new Func(name, parseArgs());
                }
                return new Root(name, null);
            }
            String word = ident();
            return switch (word) {
                case "true" -> new Literal(BooleanNode.TRUE);
                case "false" -> new Literal(BooleanNode.FALSE);
                case "null", "undefined" -> new Literal(NullNode.getInstance());
                default -> {
                    if (params.contains(word)) {
                        yield new Param(word);
                    }
                    throw new ExpressionException("Unknown identifier '" + word + "' (references start with $)");
                }
            };
        }

        /** Arguments after an opening parenthesis, each a value or a {@code v => expr} function. */
        private List<Node> parseArgs() {
            List<Node> args = new ArrayList<>();
            skipWs();
            if (!peek(')')) {
                do {
                    skipWs();
                    Node fn = tryLambda();
                    args.add(fn != null ? fn : parseTernary());
                    skipWs();
                } while (take(","));
            }
            expect(')');
            return args;
        }

        private Node tryLambda() {
            int save = pos;
            try {
                List<String> names = new ArrayList<>();
                if (take("(")) {
                    do {
                        names.add(ident());
                        skipWs();
                    } while (take(","));
                    expect(')');
                } else {
                    names.add(ident());
                }
                skipWs();
                if (!take("=>")) {
                    pos = save;
                    return null;
                }
                params.addAll(names);
                try {
                    Node body = parseTernary();
                    return new Lambda(names.get(0), names.size() > 1 ? names.get(1) : null, body);
                } finally {
                    names.forEach(params::remove);
                }
            } catch (ExpressionException e) {
                pos = save;
                return null;
            }
        }

        private Node parseObject() {
            expect('{');
            Map<String, Node> fields = new java.util.LinkedHashMap<>();
            skipWs();
            if (!peek('}')) {
                do {
                    skipWs();
                    String key = peek('"') || peek('\'') ? string() : ident();
                    skipWs();
                    expect(':');
                    fields.put(key, parseTernary());
                    skipWs();
                } while (take(","));
            }
            skipWs();
            expect('}');
            return new ObjectLiteral(fields);
        }

        private Node parseArray() {
            expect('[');
            List<Node> items = new ArrayList<>();
            skipWs();
            if (!peek(']')) {
                do {
                    items.add(parseTernary());
                    skipWs();
                } while (take(","));
            }
            skipWs();
            expect(']');
            return new ArrayLiteral(items);
        }

        private String string() {
            char q = src.charAt(pos++);
            StringBuilder sb = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != q) {
                char c = src.charAt(pos++);
                if (c == '\\' && pos < src.length()) {
                    sb.append(src.charAt(pos++));
                } else {
                    sb.append(c);
                }
            }
            if (pos >= src.length()) {
                throw new ExpressionException("Unterminated string in \"" + src.trim() + "\"");
            }
            pos++;
            return sb.toString();
        }

        private String ident() {
            skipWs();
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            if (start == pos) {
                throw new ExpressionException("Expected a name at " + pos + " in \"" + src.trim() + "\"");
            }
            return src.substring(start, pos);
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private boolean peek(char c) {
            return pos < src.length() && src.charAt(pos) == c;
        }

        private boolean peekStr(String s) {
            return src.startsWith(s, pos);
        }

        private boolean take(String s) {
            skipWs();
            if (src.startsWith(s, pos)) {
                // do not confuse "==" with "=", "<=" with "<", "&&" with "&"
                if (s.length() == 1 && pos + 1 < src.length()) {
                    char next = src.charAt(pos + 1);
                    if ((s.equals("<") || s.equals(">") || s.equals("!")) && next == '=') {
                        return false;
                    }
                    if (s.equals("-") && Character.isDigit(next) && pos > 0 && !Character.isWhitespace(src.charAt(pos - 1))) {
                        // "a -1" is subtraction; "a - 1" too; both handled by the caller
                    }
                }
                pos += s.length();
                return true;
            }
            return false;
        }

        private void expect(char c) {
            skipWs();
            if (!peek(c)) {
                throw new ExpressionException("Expected '" + c + "' at " + pos + " in \"" + src.trim() + "\"");
            }
            pos++;
        }
    }

    /** Convenience for tests and callers holding plain Java values. */
    public static JsonNode json(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static Iterator<Map.Entry<String, JsonNode>> fields(JsonNode n) {
        return n.properties().iterator();
    }
}
