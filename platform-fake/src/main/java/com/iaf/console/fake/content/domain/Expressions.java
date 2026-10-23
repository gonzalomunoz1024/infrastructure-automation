package com.iaf.console.fake.content.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * The platform's expression language, enough for the sample content: {@code {{ expr }}} templates
 * over roots such as {@code $input}, {@code $json}, {@code $vars}, {@code $job}, {@code $resource},
 * {@code $region}, {@code $item}, {@code $error} and step aliases, {@code $('step')}, {@code ??},
 * comparisons, {@code &&}, {@code ||}, {@code +}, member and index access, a few string and array
 * methods, {@code $range(a, b)} and single-parameter arrow functions in {@code map}.
 */
public final class Expressions {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Expressions() {
    }

    /** Where roots and steps are looked up; a missing name is {@link MissingNode}. */
    public interface Scope {

        JsonNode root(String name);

        JsonNode step(String name);
    }

    /** A scope built from maps. */
    public static final class MapScope implements Scope {

        private final Map<String, JsonNode> roots = new HashMap<>();
        private final Map<String, JsonNode> steps = new HashMap<>();

        public MapScope put(String name, JsonNode value) {
            roots.put(name.startsWith("$") ? name : "$" + name, value == null ? NullNode.getInstance() : value);
            return this;
        }

        public MapScope putStep(String name, JsonNode output) {
            steps.put(name, output == null ? NullNode.getInstance() : output);
            return this;
        }

        public MapScope copy() {
            MapScope c = new MapScope();
            c.roots.putAll(roots);
            c.steps.putAll(steps);
            return c;
        }

        @Override
        public JsonNode root(String name) {
            JsonNode value = roots.get(name);
            return value == null ? MissingNode.getInstance() : value;
        }

        @Override
        public JsonNode step(String name) {
            JsonNode value = steps.get(name);
            return value == null ? MissingNode.getInstance() : value;
        }
    }

    /** Renders every template in a node tree; a text that is one template becomes the typed value. */
    public static JsonNode render(JsonNode template, Scope scope) {
        if (template == null || template.isNull() || template.isMissingNode()) {
            return template == null ? NullNode.getInstance() : template;
        }
        if (template.isTextual()) {
            return renderValue(template.asText(), scope);
        }
        if (template.isObject()) {
            ObjectNode out = NODES.objectNode();
            for (Map.Entry<String, JsonNode> field : template.properties()) {
                JsonNode value = render(field.getValue(), scope);
                out.set(field.getKey(), value.isMissingNode() ? NullNode.getInstance() : value);
            }
            return out;
        }
        if (template.isArray()) {
            ArrayNode out = NODES.arrayNode();
            for (JsonNode item : template) {
                JsonNode value = render(item, scope);
                out.add(value.isMissingNode() ? NullNode.getInstance() : value);
            }
            return out;
        }
        return template;
    }

    /** A text with templates: one whole template keeps its type, anything else becomes text. */
    public static JsonNode renderValue(String text, Scope scope) {
        String trimmed = text.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}") && trimmed.indexOf("{{", 2) < 0) {
            return evaluate(trimmed.substring(2, trimmed.length() - 2), scope);
        }
        return TextNode.valueOf(renderText(text, scope));
    }

    public static String renderText(String text, Scope scope) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int open = text.indexOf("{{", from);
            if (open < 0) {
                out.append(text, from, text.length());
                return out.toString();
            }
            int close = text.indexOf("}}", open + 2);
            if (close < 0) {
                out.append(text, from, text.length());
                return out.toString();
            }
            out.append(text, from, open);
            out.append(asText(evaluate(text.substring(open + 2, close), scope)));
            from = close + 2;
        }
    }

    /** Evaluates one expression (without braces). */
    public static JsonNode evaluate(String expression, Scope scope) {
        Parser parser = new Parser(expression, scope);
        JsonNode value = parser.parseExpression();
        parser.expectEnd();
        return value;
    }

    public static boolean truthy(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asDouble() != 0;
        }
        if (value.isTextual()) {
            return !value.asText().isEmpty();
        }
        return true;
    }

    public static String asText(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isNumber() && value.isIntegralNumber()) {
            return Long.toString(value.asLong());
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    static boolean equal(JsonNode a, JsonNode b) {
        boolean aNull = a == null || a.isNull() || a.isMissingNode();
        boolean bNull = b == null || b.isNull() || b.isMissingNode();
        if (aNull || bNull) {
            return aNull && bNull;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.asDouble() == b.asDouble();
        }
        if (a.isNumber() && b.isTextual() || a.isTextual() && b.isNumber()) {
            return a.asText().equals(b.asText());
        }
        return a.equals(b);
    }

    /** A function value: one parameter and a body evaluated against a scope. */
    private record Lambda(String parameter, String body) {

        JsonNode apply(JsonNode argument, Scope outer) {
            Scope inner = new Scope() {
                @Override
                public JsonNode root(String name) {
                    return name.equals(parameter) ? argument : outer.root(name);
                }

                @Override
                public JsonNode step(String name) {
                    return outer.step(name);
                }
            };
            return evaluate(body, inner);
        }
    }

    /** A recursive-descent parser that evaluates as it parses. */
    private static final class Parser {

        private final String src;
        private final Scope scope;
        private int pos;

        Parser(String src, Scope scope) {
            this.src = src;
            this.scope = scope;
        }

        JsonNode parseExpression() {
            return parseNullish();
        }

        void expectEnd() {
            skipSpace();
            if (pos < src.length()) {
                throw new IllegalArgumentException("unexpected '" + src.substring(pos) + "' in expression: " + src);
            }
        }

        private JsonNode parseNullish() {
            JsonNode left = parseOr();
            while (consume("??")) {
                JsonNode right = parseOr();
                if (left.isNull() || left.isMissingNode()) {
                    left = right;
                }
            }
            return left;
        }

        private JsonNode parseOr() {
            JsonNode left = parseAnd();
            while (consume("||")) {
                JsonNode right = parseAnd();
                left = truthy(left) ? left : right;
            }
            return left;
        }

        private JsonNode parseAnd() {
            JsonNode left = parseEquality();
            while (consume("&&")) {
                JsonNode right = parseEquality();
                left = truthy(left) ? right : left;
            }
            return left;
        }

        private JsonNode parseEquality() {
            JsonNode left = parseRelational();
            while (true) {
                if (consume("==")) {
                    left = BooleanNode.valueOf(equal(left, parseRelational()));
                } else if (consume("!=")) {
                    left = BooleanNode.valueOf(!equal(left, parseRelational()));
                } else {
                    return left;
                }
            }
        }

        private JsonNode parseRelational() {
            JsonNode left = parseAdditive();
            while (true) {
                if (consume("<=")) {
                    left = compare(left, parseAdditive(), c -> c <= 0);
                } else if (consume(">=")) {
                    left = compare(left, parseAdditive(), c -> c >= 0);
                } else if (peek("<") && !peek("<<")) {
                    pos++;
                    left = compare(left, parseAdditive(), c -> c < 0);
                } else if (peek(">")) {
                    pos++;
                    left = compare(left, parseAdditive(), c -> c > 0);
                } else {
                    return left;
                }
            }
        }

        private static JsonNode compare(JsonNode a, JsonNode b, java.util.function.IntPredicate test) {
            if (a.isNumber() && b.isNumber()) {
                return BooleanNode.valueOf(test.test(Double.compare(a.asDouble(), b.asDouble())));
            }
            if (a.isTextual() && b.isTextual()) {
                return BooleanNode.valueOf(test.test(a.asText().compareTo(b.asText())));
            }
            return BooleanNode.FALSE;
        }

        private JsonNode parseAdditive() {
            JsonNode left = parseMultiplicative();
            while (true) {
                if (consume("+")) {
                    JsonNode right = parseMultiplicative();
                    left = add(left, right);
                } else if (peek("-") && !peek("->")) {
                    pos++;
                    JsonNode right = parseMultiplicative();
                    left = number(number(left) - number(right));
                } else {
                    return left;
                }
            }
        }

        private JsonNode parseMultiplicative() {
            JsonNode left = parseUnary();
            while (true) {
                if (consume("*")) {
                    left = number(number(left) * number(parseUnary()));
                } else if (consume("/")) {
                    left = number(number(left) / number(parseUnary()));
                } else {
                    return left;
                }
            }
        }

        private static JsonNode add(JsonNode a, JsonNode b) {
            if (a.isNumber() && b.isNumber()) {
                return number(a.asDouble() + b.asDouble());
            }
            return TextNode.valueOf(asText(a) + asText(b));
        }

        private static double number(JsonNode n) {
            return n.isNumber() ? n.asDouble() : n.isTextual() ? parseDouble(n.asText()) : 0;
        }

        private static double parseDouble(String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static JsonNode number(double d) {
            return d == Math.rint(d) && Math.abs(d) < 9.0e15 ? LongNode.valueOf((long) d) : DoubleNode.valueOf(d);
        }

        private JsonNode parseUnary() {
            if (consume("!")) {
                return BooleanNode.valueOf(!truthy(parseUnary()));
            }
            if (consume("-")) {
                return number(-number(parseUnary()));
            }
            return parsePostfix(parsePrimary());
        }

        private JsonNode parsePostfix(JsonNode value) {
            while (true) {
                skipSpace();
                if (consume(".")) {
                    String name = identifier();
                    skipSpace();
                    if (peek("(")) {
                        value = callMethod(value, name, parseArguments());
                    } else {
                        value = member(value, name);
                    }
                } else if (peek("[")) {
                    pos++;
                    JsonNode index = parseExpression();
                    skipSpace();
                    expect("]");
                    value = index(value, index);
                } else {
                    return value;
                }
            }
        }

        private JsonNode parsePrimary() {
            skipSpace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("expression ends early: " + src);
            }
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                JsonNode value = parseExpression();
                skipSpace();
                expect(")");
                return value;
            }
            if (c == '\'' || c == '"') {
                return TextNode.valueOf(string());
            }
            if (Character.isDigit(c)) {
                return numberLiteral();
            }
            if (c == '[') {
                pos++;
                ArrayNode array = NODES.arrayNode();
                skipSpace();
                if (!peek("]")) {
                    do {
                        array.add(parseExpression());
                        skipSpace();
                    } while (consume(","));
                }
                expect("]");
                return array;
            }
            if (c == '$') {
                pos++;
                skipSpace();
                if (peek("(")) {
                    pos++;
                    skipSpace();
                    String stepName = string();
                    skipSpace();
                    expect(")");
                    JsonNode output = scope.step(stepName);
                    ObjectNode step = NODES.objectNode();
                    step.set("json", output.isMissingNode() ? NullNode.getInstance() : output);
                    return step;
                }
                String name = identifier();
                if (name.equals("range")) {
                    List<Object> args = parseArguments();
                    long from = (long) number(node(args.get(0)));
                    long to = (long) number(node(args.get(1)));
                    ArrayNode range = NODES.arrayNode();
                    for (long i = from; i <= to; i++) {
                        range.add(i);
                    }
                    return range;
                }
                return scope.root("$" + name);
            }
            String word = identifier();
            switch (word) {
                case "true":
                    return BooleanNode.TRUE;
                case "false":
                    return BooleanNode.FALSE;
                case "null":
                    return NullNode.getInstance();
                default:
                    // a bare identifier is a lambda parameter bound as a root by the enclosing call
                    JsonNode bound = scope.root(word);
                    if (!bound.isMissingNode()) {
                        return bound;
                    }
                    throw new IllegalArgumentException("unknown name '" + word + "' in expression: " + src);
            }
        }

        private List<Object> parseArguments() {
            skipSpace();
            expect("(");
            List<Object> args = new ArrayList<>();
            skipSpace();
            if (!peek(")")) {
                do {
                    skipSpace();
                    Lambda lambda = lambda();
                    if (lambda != null) {
                        args.add(lambda);
                    } else {
                        args.add(parseExpression());
                    }
                    skipSpace();
                } while (consume(","));
            }
            expect(")");
            return args;
        }

        /** {@code name => body}, where the body runs to the matching close of the call. */
        private Lambda lambda() {
            int save = pos;
            if (pos < src.length() && Character.isLetter(src.charAt(pos))) {
                String param = identifier();
                skipSpace();
                if (consume("=>")) {
                    skipSpace();
                    int start = pos;
                    int depth = 0;
                    while (pos < src.length()) {
                        char c = src.charAt(pos);
                        if (c == '\'' || c == '"') {
                            string();
                            continue;
                        }
                        if (c == '(' || c == '[') {
                            depth++;
                        } else if (c == ')' || c == ']') {
                            if (depth == 0) {
                                break;
                            }
                            depth--;
                        } else if (c == ',' && depth == 0) {
                            break;
                        }
                        pos++;
                    }
                    return new Lambda(param, src.substring(start, pos));
                }
            }
            pos = save;
            return null;
        }

        private JsonNode callMethod(JsonNode target, String name, List<Object> args) {
            switch (name) {
                case "replace":
                    return TextNode.valueOf(asText(target).replace(asText(node(args.get(0))), asText(node(args.get(1)))));
                case "slice": {
                    int from = (int) number(node(args.get(0)));
                    if (target.isArray()) {
                        int to = args.size() > 1 ? (int) number(node(args.get(1))) : target.size();
                        ArrayNode out = NODES.arrayNode();
                        for (int i = Math.max(0, from); i < Math.min(to, target.size()); i++) {
                            out.add(target.get(i));
                        }
                        return out;
                    }
                    String text = asText(target);
                    int to = args.size() > 1 ? (int) number(node(args.get(1))) : text.length();
                    return TextNode.valueOf(text.substring(Math.min(Math.max(0, from), text.length()), Math.min(Math.max(from, to), text.length())));
                }
                case "toLowerCase":
                    return TextNode.valueOf(asText(target).toLowerCase(java.util.Locale.ROOT));
                case "toUpperCase":
                    return TextNode.valueOf(asText(target).toUpperCase(java.util.Locale.ROOT));
                case "trim":
                    return TextNode.valueOf(asText(target).trim());
                case "startsWith":
                    return BooleanNode.valueOf(asText(target).startsWith(asText(node(args.get(0)))));
                case "endsWith":
                    return BooleanNode.valueOf(asText(target).endsWith(asText(node(args.get(0)))));
                case "includes": {
                    JsonNode needle = node(args.get(0));
                    if (target.isArray()) {
                        for (JsonNode item : target) {
                            if (equal(item, needle)) {
                                return BooleanNode.TRUE;
                            }
                        }
                        return BooleanNode.FALSE;
                    }
                    return BooleanNode.valueOf(asText(target).contains(asText(needle)));
                }
                case "isEmpty":
                    return BooleanNode.valueOf(target.isMissingNode() || target.isNull() || target.isContainerNode() && target.isEmpty()
                            || target.isTextual() && target.asText().isEmpty());
                case "concat": {
                    ArrayNode out = NODES.arrayNode();
                    if (target.isArray()) {
                        out.addAll((ArrayNode) target);
                    }
                    for (Object arg : args) {
                        JsonNode value = node(arg);
                        if (value.isArray()) {
                            out.addAll((ArrayNode) value);
                        } else {
                            out.add(value);
                        }
                    }
                    return out;
                }
                case "join": {
                    String separator = args.isEmpty() ? "," : asText(node(args.get(0)));
                    List<String> parts = new ArrayList<>();
                    for (JsonNode item : target) {
                        parts.add(asText(item));
                    }
                    return TextNode.valueOf(String.join(separator, parts));
                }
                case "split": {
                    ArrayNode out = NODES.arrayNode();
                    for (String part : asText(target).split(java.util.regex.Pattern.quote(asText(node(args.get(0)))))) {
                        out.add(part);
                    }
                    return out;
                }
                case "map": {
                    ArrayNode out = NODES.arrayNode();
                    if (target.isArray() && !args.isEmpty() && args.get(0) instanceof Lambda fn) {
                        for (JsonNode item : target) {
                            out.add(fn.apply(item, scope));
                        }
                    }
                    return out;
                }
                case "filter": {
                    ArrayNode out = NODES.arrayNode();
                    if (target.isArray() && !args.isEmpty() && args.get(0) instanceof Lambda fn) {
                        for (JsonNode item : target) {
                            if (truthy(fn.apply(item, scope))) {
                                out.add(item);
                            }
                        }
                    }
                    return out;
                }
                case "some": {
                    if (target.isArray() && !args.isEmpty() && args.get(0) instanceof Lambda fn) {
                        for (JsonNode item : target) {
                            if (truthy(fn.apply(item, scope))) {
                                return BooleanNode.TRUE;
                            }
                        }
                    }
                    return BooleanNode.FALSE;
                }
                case "keys": {
                    ArrayNode out = NODES.arrayNode();
                    target.fieldNames().forEachRemaining(out::add);
                    return out;
                }
                case "toString":
                    return TextNode.valueOf(asText(target));
                default:
                    throw new IllegalArgumentException("unknown method '" + name + "' in expression: " + src);
            }
        }

        private static JsonNode node(Object arg) {
            if (arg instanceof JsonNode n) {
                return n;
            }
            throw new IllegalArgumentException("a function is not a value here");
        }

        private static JsonNode member(JsonNode value, String name) {
            if (value == null || value.isNull() || value.isMissingNode()) {
                return MissingNode.getInstance();
            }
            if (name.equals("length")) {
                if (value.isArray() || value.isObject()) {
                    return LongNode.valueOf(value.size());
                }
                return LongNode.valueOf(asText(value).length());
            }
            JsonNode field = value.get(name);
            return field == null ? MissingNode.getInstance() : field;
        }

        private static JsonNode index(JsonNode value, JsonNode index) {
            if (value == null || value.isNull() || value.isMissingNode()) {
                return MissingNode.getInstance();
            }
            if (value.isArray() && index.isNumber()) {
                JsonNode item = value.get(index.asInt());
                return item == null ? MissingNode.getInstance() : item;
            }
            return member(value, asText(index));
        }

        private String identifier() {
            skipSpace();
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("a name was expected at '" + src.substring(start) + "' in expression: " + src);
            }
            return src.substring(start, pos);
        }

        private String string() {
            char quote = src.charAt(pos++);
            StringBuilder out = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '\\' && pos < src.length()) {
                    out.append(src.charAt(pos++));
                } else if (c == quote) {
                    if (quote == '\'' && pos < src.length() && src.charAt(pos) == '\'') {
                        out.append('\'');
                        pos++;
                    } else {
                        return out.toString();
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string in expression: " + src);
        }

        private JsonNode numberLiteral() {
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            String text = src.substring(start, pos);
            return text.contains(".") ? DoubleNode.valueOf(Double.parseDouble(text)) : LongNode.valueOf(Long.parseLong(text));
        }

        private void skipSpace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private boolean peek(String token) {
            skipSpace();
            return src.startsWith(token, pos);
        }

        private boolean consume(String token) {
            if (peek(token)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        private void expect(String token) {
            if (!consume(token)) {
                throw new IllegalArgumentException("'" + token + "' was expected at '" + src.substring(Math.min(pos, src.length())) + "' in expression: "
                        + src);
            }
        }
    }
}
