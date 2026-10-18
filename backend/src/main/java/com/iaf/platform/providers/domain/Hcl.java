package com.iaf.platform.providers.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Just enough HCL (the Terraform language) to read a module's interface: blocks with their labels,
 * attributes kept as the tokens of their expression, literal values (strings, heredocs, numbers,
 * booleans, null, lists and objects) and type constraints. Expressions are never evaluated; a
 * value that refers to anything or calls a function is "not a literal". Pure: no I/O.
 */
public final class Hcl {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    /** Blocks, values and types nested deeper than this are not read: the parser recurses, and a hostile file would overflow the stack. */
    static final int MAX_DEPTH = 64;

    private Hcl() {
    }

    enum Kind { IDENT, STRING, HEREDOC, NUMBER, PUNCT, NEWLINE, EOF }

    /** {@code text} is the unescaped value of a string or heredoc; {@code template} when it interpolates. */
    record Token(Kind kind, String text, int start, int end, int line, boolean template) {
        boolean is(String punct) {
            return kind == Kind.PUNCT && text.equals(punct);
        }
    }

    /** An attribute's value: its tokens (newlines inside brackets included) and its source text. */
    public record Expr(List<Token> tokens, String source) {
        /** The source with its whitespace collapsed, like {@code list(string)} or {@code map(number)}. */
        public String text() {
            return source.replaceAll("\\s+", " ").trim();
        }
    }

    public record Block(String type, List<String> labels, Map<String, Expr> attributes, List<Block> blocks, int line) {
        public String label() {
            return labels.isEmpty() ? null : labels.get(0);
        }

        public Expr attribute(String name) {
            return attributes.get(name);
        }

        public List<Block> blocks(String type) {
            return blocks.stream().filter(b -> b.type().equals(type)).toList();
        }
    }

    /** A type constraint as a JSON Schema; {@code optional} and {@code defaultValue} come from {@code optional(T, d)}. */
    public record Type(ObjectNode schema, boolean optional, JsonNode defaultValue) {
    }

    /** The top-level blocks of a file; a syntax error is an {@link IllegalArgumentException} naming the line. */
    public static List<Block> parse(String text) {
        Parser p = new Parser(text == null ? "" : text, new Lexer(text == null ? "" : text).tokens());
        return p.file();
    }

    /** One expression written as text, like the {@code "list(string)"} type of a {@code .tf.json} variable. */
    public static Expr expression(String text) {
        String s = text == null ? "" : text;
        List<Token> tokens = new ArrayList<>(new Lexer(s).tokens());
        tokens.removeIf(tok -> tok.kind() == Kind.EOF);
        while (!tokens.isEmpty() && tokens.get(tokens.size() - 1).kind() == Kind.NEWLINE) {
            tokens.remove(tokens.size() - 1);
        }
        return new Expr(List.copyOf(tokens), s.trim());
    }

    /** The JSON value of a literal expression, or null when the expression is not a literal. */
    public static JsonNode literal(Expr expr) {
        if (expr == null) {
            return null;
        }
        Values v = new Values(expr.tokens());
        try {
            JsonNode value = v.value();
            v.skipNewlines();
            return v.done() ? value : null;
        } catch (NotLiteral e) {
            return null;
        }
    }

    /** The string of an expression that is one plain string or heredoc (no interpolation), or null. */
    public static String string(Expr expr) {
        JsonNode v = literal(expr);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    /** The boolean of an expression that is {@code true} or {@code false}, or {@code otherwise}. */
    public static boolean bool(Expr expr, boolean otherwise) {
        JsonNode v = literal(expr);
        return v != null && v.isBoolean() ? v.asBoolean() : otherwise;
    }

    /** A type constraint ({@code string}, {@code list(object({ ... }))}, the legacy {@code "map"}) as a JSON Schema. */
    public static Type type(Expr expr) {
        if (expr == null) {
            return null;
        }
        Types t = new Types(expr.tokens());
        try {
            Type type = t.type();
            t.skipNewlines();
            return t.done() ? type : null;
        } catch (NotLiteral e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ lexer

    private static final class Lexer {
        final String s;
        int i;
        int line = 1;
        final List<Token> out = new ArrayList<>();

        Lexer(String s) {
            this.s = s;
        }

        List<Token> tokens() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\n') {
                    out.add(new Token(Kind.NEWLINE, "\n", i, i + 1, line, false));
                    i++;
                    line++;
                } else if (c == ' ' || c == '\t' || c == '\r' || c == '﻿') {
                    i++;
                } else if (c == '#' || (c == '/' && peek(1) == '/')) {
                    while (i < s.length() && s.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && peek(1) == '*') {
                    int end = s.indexOf("*/", i + 2);
                    if (end < 0) {
                        throw error("a /* comment is never closed");
                    }
                    for (int k = i; k < end; k++) {
                        if (s.charAt(k) == '\n') {
                            line++;
                        }
                    }
                    i = end + 2;
                } else if (c == '"') {
                    string();
                } else if (c == '<' && peek(1) == '<' && heredocAhead()) {
                    heredoc();
                } else if (Character.isLetter(c) || c == '_') {
                    int start = i;
                    while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '-')) {
                        i++;
                    }
                    out.add(new Token(Kind.IDENT, s.substring(start, i), start, i, line, false));
                } else if (Character.isDigit(c)) {
                    int start = i;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                    if (i < s.length() - 1 && s.charAt(i) == '.' && Character.isDigit(s.charAt(i + 1))) {
                        i++;
                        while (i < s.length() && Character.isDigit(s.charAt(i))) {
                            i++;
                        }
                    }
                    if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                        int save = i;
                        i++;
                        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                            i++;
                        }
                        if (i < s.length() && Character.isDigit(s.charAt(i))) {
                            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                                i++;
                            }
                        } else {
                            i = save;
                        }
                    }
                    out.add(new Token(Kind.NUMBER, s.substring(start, i), start, i, line, false));
                } else {
                    String two = i + 2 <= s.length() ? s.substring(i, i + 2) : "";
                    String three = i + 3 <= s.length() ? s.substring(i, i + 3) : "";
                    String p = three.equals("...") ? three
                            : List.of("==", "!=", "<=", ">=", "&&", "||", "=>").contains(two) ? two : String.valueOf(c);
                    out.add(new Token(Kind.PUNCT, p, i, i + p.length(), line, false));
                    i += p.length();
                }
            }
            out.add(new Token(Kind.EOF, "", s.length(), s.length(), line, false));
            return out;
        }

        char peek(int k) {
            return i + k < s.length() ? s.charAt(i + k) : '\0';
        }

        boolean heredocAhead() {
            int k = i + 2;
            if (k < s.length() && s.charAt(k) == '-') {
                k++;
            }
            int start = k;
            while (k < s.length() && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_')) {
                k++;
            }
            return k > start && (k >= s.length() || s.charAt(k) == '\n' || s.charAt(k) == '\r');
        }

        void heredoc() {
            int start = i;
            int startLine = line;
            i += 2;
            boolean indent = false;
            if (s.charAt(i) == '-') {
                indent = true;
                i++;
            }
            int m = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                i++;
            }
            String marker = s.substring(m, i);
            int nl = s.indexOf('\n', i);
            if (nl < 0) {
                throw error("the heredoc " + marker + " is never closed");
            }
            i = nl + 1;
            line++;
            List<String> lines = new ArrayList<>();
            while (true) {
                if (i >= s.length()) {
                    throw new IllegalArgumentException("line " + startLine + ": the heredoc " + marker + " is never closed");
                }
                int eol = s.indexOf('\n', i);
                String l = s.substring(i, eol < 0 ? s.length() : eol);
                String bare = l.endsWith("\r") ? l.substring(0, l.length() - 1) : l;
                i = eol < 0 ? s.length() : eol;
                if (bare.trim().equals(marker)) {
                    break;
                }
                lines.add(bare);
                if (eol < 0) {
                    throw new IllegalArgumentException("line " + startLine + ": the heredoc " + marker + " is never closed");
                }
                i++;
                line++;
            }
            if (indent) {
                int min = lines.stream().filter(l -> !l.isBlank()).mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
                lines.replaceAll(l -> l.length() >= min ? l.substring(min) : l.stripLeading());
            }
            String text = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
            out.add(new Token(Kind.HEREDOC, text, start, i, startLine, text.contains("${") || text.contains("%{")));
        }

        void string() {
            int start = i;
            int startLine = line;
            i++;
            StringBuilder sb = new StringBuilder();
            boolean template = false;
            while (true) {
                if (i >= s.length() || s.charAt(i) == '\n') {
                    throw new IllegalArgumentException("line " + startLine + ": a string is never closed");
                }
                char c = s.charAt(i);
                if (c == '"') {
                    i++;
                    break;
                }
                if (c == '\\') {
                    char n = peek(1);
                    switch (n) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'u', 'U' -> {
                            int len = n == 'u' ? 4 : 8;
                            if (i + 2 + len > s.length()) {
                                throw error("a \\" + n + " escape is cut short");
                            }
                            sb.appendCodePoint(Integer.parseInt(s.substring(i + 2, i + 2 + len), 16));
                            i += len;
                        }
                        default -> throw error("unknown escape \\" + n);
                    }
                    i += 2;
                    continue;
                }
                if ((c == '$' || c == '%') && peek(1) == c && peek(2) == '{') {
                    sb.append(c).append('{');
                    i += 3;
                    continue;
                }
                if ((c == '$' || c == '%') && peek(1) == '{') {
                    template = true;
                    int end = interpolationEnd(i + 2);
                    sb.append(s, i, end);
                    i = end;
                    continue;
                }
                sb.append(c);
                i++;
            }
            out.add(new Token(Kind.STRING, sb.toString(), start, i, startLine, template));
        }

        /** The index just past the {@code }} closing an interpolation opened before {@code from}. */
        int interpolationEnd(int from) {
            int depth = 1;
            int k = from;
            while (k < s.length()) {
                char c = s.charAt(k);
                if (c == '\n') {
                    line++;
                } else if (c == '"') {
                    k++;
                    while (k < s.length() && s.charAt(k) != '"') {
                        k += s.charAt(k) == '\\' ? 2 : 1;
                    }
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return k + 1;
                    }
                }
                k++;
            }
            throw error("an interpolation ${ is never closed");
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("line " + line + ": " + message);
        }
    }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        final String source;
        final List<Token> t;
        int p;
        int depth;

        Parser(String source, List<Token> tokens) {
            this.source = source;
            this.t = tokens;
        }

        List<Block> file() {
            Body body = body(false);
            if (!body.attributes().isEmpty() && body.blocks().isEmpty()) {
                // a .tfvars-like file: nothing to read here
                return List.of();
            }
            return body.blocks();
        }

        record Body(Map<String, Expr> attributes, List<Block> blocks) {
        }

        Body body(boolean nested) {
            Map<String, Expr> attributes = new LinkedHashMap<>();
            List<Block> blocks = new ArrayList<>();
            while (true) {
                skipNewlines();
                Token tok = t.get(p);
                if (tok.kind() == Kind.EOF) {
                    if (nested) {
                        throw new IllegalArgumentException("line " + tok.line() + ": a block is never closed");
                    }
                    break;
                }
                if (tok.is("}")) {
                    if (!nested) {
                        throw new IllegalArgumentException("line " + tok.line() + ": unexpected }");
                    }
                    break;
                }
                if (tok.kind() != Kind.IDENT) {
                    throw new IllegalArgumentException("line " + tok.line() + ": expected an attribute or a block, found " + describe(tok));
                }
                p++;
                if (t.get(p).is("=")) {
                    p++;
                    attributes.put(tok.text(), expression());
                    continue;
                }
                List<String> labels = new ArrayList<>();
                while (t.get(p).kind() == Kind.STRING || t.get(p).kind() == Kind.IDENT) {
                    labels.add(t.get(p).text());
                    p++;
                }
                if (!t.get(p).is("{")) {
                    throw new IllegalArgumentException("line " + t.get(p).line() + ": expected { after " + tok.text() + ", found " + describe(t.get(p)));
                }
                p++;
                if (++depth > MAX_DEPTH) {
                    throw new IllegalArgumentException("line " + tok.line() + ": blocks nest more than " + MAX_DEPTH + " deep");
                }
                Body inner = body(true);
                depth--;
                p++; // the closing }
                blocks.add(new Block(tok.text(), List.copyOf(labels), inner.attributes(), inner.blocks(), tok.line()));
            }
            return new Body(attributes, blocks);
        }

        /** The tokens up to the end of the line (or the closing } of a one-line block), brackets balanced. */
        Expr expression() {
            List<Token> tokens = new ArrayList<>();
            int depth = 0;
            int line = t.get(p).line();
            while (true) {
                Token tok = t.get(p);
                if (tok.kind() == Kind.EOF) {
                    if (depth > 0) {
                        throw new IllegalArgumentException("line " + line + ": a bracket is never closed");
                    }
                    break;
                }
                if (depth == 0 && (tok.kind() == Kind.NEWLINE || tok.is("}"))) {
                    break;
                }
                if (tok.is("(") || tok.is("[") || tok.is("{")) {
                    depth++;
                } else if (tok.is(")") || tok.is("]") || tok.is("}")) {
                    depth--;
                }
                tokens.add(tok);
                p++;
            }
            List<Token> trimmed = new ArrayList<>(tokens);
            while (!trimmed.isEmpty() && trimmed.get(trimmed.size() - 1).kind() == Kind.NEWLINE) {
                trimmed.remove(trimmed.size() - 1);
            }
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("line " + line + ": an attribute has no value");
            }
            String text = source.substring(trimmed.get(0).start(), trimmed.get(trimmed.size() - 1).end());
            return new Expr(List.copyOf(trimmed), text);
        }

        void skipNewlines() {
            while (t.get(p).kind() == Kind.NEWLINE) {
                p++;
            }
        }

        static String describe(Token tok) {
            return switch (tok.kind()) {
                case STRING -> "a string";
                case HEREDOC -> "a heredoc";
                case NUMBER -> "a number";
                case NEWLINE -> "the end of the line";
                case EOF -> "the end of the file";
                default -> tok.text();
            };
        }
    }

    // ------------------------------------------------------------------ literal values

    private static final class NotLiteral extends RuntimeException {
        NotLiteral() {
            super(null, null, false, false);
        }
    }

    private static class Cursor {
        final List<Token> t;
        int p;
        int depth;

        Cursor(List<Token> tokens) {
            this.t = tokens;
        }

        Token peek() {
            return p < t.size() ? t.get(p) : new Token(Kind.EOF, "", 0, 0, 0, false);
        }

        Token next() {
            Token tok = peek();
            p++;
            return tok;
        }

        boolean done() {
            return p >= t.size();
        }

        void skipNewlines() {
            while (p < t.size() && t.get(p).kind() == Kind.NEWLINE) {
                p++;
            }
        }

        void expect(String punct) {
            skipNewlines();
            if (!next().is(punct)) {
                throw new NotLiteral();
            }
        }

        /** One level deeper; past {@link #MAX_DEPTH} the expression is not read. */
        void deeper() {
            if (++depth > MAX_DEPTH) {
                throw new NotLiteral();
            }
        }
    }

    private static final class Values extends Cursor {
        Values(List<Token> tokens) {
            super(tokens);
        }

        JsonNode value() {
            skipNewlines();
            Token tok = next();
            switch (tok.kind()) {
                case STRING, HEREDOC -> {
                    if (tok.template()) {
                        throw new NotLiteral();
                    }
                    return F.textNode(tok.text());
                }
                case NUMBER -> {
                    return number(tok.text(), false);
                }
                case IDENT -> {
                    return switch (tok.text()) {
                        case "true" -> F.booleanNode(true);
                        case "false" -> F.booleanNode(false);
                        case "null" -> F.nullNode();
                        default -> throw new NotLiteral();
                    };
                }
                case PUNCT -> {
                    if (tok.is("-") && peek().kind() == Kind.NUMBER) {
                        return number(next().text(), true);
                    }
                    if (tok.is("[") || tok.is("{")) {
                        deeper();
                        JsonNode nested = tok.is("[") ? list() : object();
                        depth--;
                        return nested;
                    }
                    throw new NotLiteral();
                }
                default -> throw new NotLiteral();
            }
        }

        ArrayNode list() {
            ArrayNode out = F.arrayNode();
            while (true) {
                skipNewlines();
                if (peek().is("]")) {
                    next();
                    return out;
                }
                out.add(value());
                skipNewlines();
                Token sep = next();
                if (sep.is("]")) {
                    return out;
                }
                if (!sep.is(",")) {
                    throw new NotLiteral();
                }
            }
        }

        ObjectNode object() {
            ObjectNode out = F.objectNode();
            while (true) {
                skipNewlines();
                if (peek().is("}")) {
                    next();
                    return out;
                }
                Token key = next();
                if (key.kind() != Kind.IDENT && !(key.kind() == Kind.STRING && !key.template())) {
                    throw new NotLiteral();
                }
                skipNewlines();
                Token eq = next();
                if (!eq.is("=") && !eq.is(":")) {
                    throw new NotLiteral();
                }
                out.set(key.text(), value());
                if (peek().is(",")) {
                    next();
                } else if (!peek().is("}") && peek().kind() != Kind.NEWLINE) {
                    throw new NotLiteral();
                }
            }
        }

        static JsonNode number(String text, boolean negative) {
            BigDecimal n = new BigDecimal(text);
            if (negative) {
                n = n.negate();
            }
            if (n.scale() <= 0 || n.stripTrailingZeros().scale() <= 0) {
                try {
                    long l = n.longValueExact();
                    return l == (int) l ? F.numberNode((int) l) : F.numberNode(l);
                } catch (ArithmeticException e) {
                    return F.numberNode(n);
                }
            }
            return F.numberNode(n.doubleValue());
        }
    }

    // ------------------------------------------------------------------ type constraints

    private static final class Types extends Cursor {
        Types(List<Token> tokens) {
            super(tokens);
        }

        Type type() {
            deeper();
            Type type = constraint();
            depth--;
            return type;
        }

        private Type constraint() {
            skipNewlines();
            Token tok = next();
            if (tok.kind() == Kind.STRING && !tok.template()) {
                // Terraform 0.11 spelled types as strings
                return switch (tok.text()) {
                    case "string" -> plain("string");
                    case "list" -> plain("array");
                    case "map" -> plain("object");
                    default -> throw new NotLiteral();
                };
            }
            if (tok.kind() != Kind.IDENT) {
                throw new NotLiteral();
            }
            switch (tok.text()) {
                case "string" -> {
                    return plain("string");
                }
                case "number" -> {
                    return plain("number");
                }
                case "bool" -> {
                    return plain("boolean");
                }
                case "any" -> {
                    return new Type(F.objectNode(), false, null);
                }
                case "list", "set", "map" -> {
                    String kind = tok.text();
                    ObjectNode schema = F.objectNode();
                    schema.put("type", "map".equals(kind) ? "object" : "array");
                    if (!peek().is("(")) {
                        return new Type(schema, false, null); // the legacy bare list and map
                    }
                    next();
                    Type element = type();
                    expect(")");
                    if ("map".equals(kind)) {
                        schema.set("additionalProperties", element.schema());
                    } else {
                        schema.set("items", element.schema());
                        if ("set".equals(kind)) {
                            schema.put("uniqueItems", true);
                        }
                    }
                    return new Type(schema, false, null);
                }
                case "tuple" -> {
                    expect("(");
                    expect("[");
                    int n = 0;
                    while (true) {
                        skipNewlines();
                        if (peek().is("]")) {
                            next();
                            break;
                        }
                        type();
                        n++;
                        skipNewlines();
                        if (peek().is(",")) {
                            next();
                        } else if (!peek().is("]")) {
                            throw new NotLiteral();
                        }
                    }
                    expect(")");
                    ObjectNode schema = F.objectNode();
                    schema.put("type", "array");
                    schema.put("minItems", n);
                    schema.put("maxItems", n);
                    return new Type(schema, false, null);
                }
                case "object" -> {
                    expect("(");
                    expect("{");
                    ObjectNode schema = F.objectNode();
                    schema.put("type", "object");
                    ObjectNode properties = schema.putObject("properties");
                    ArrayNode required = F.arrayNode();
                    while (true) {
                        skipNewlines();
                        Token key = next();
                        if (key.is("}")) {
                            break;
                        }
                        if (key.kind() != Kind.IDENT && key.kind() != Kind.STRING) {
                            throw new NotLiteral();
                        }
                        skipNewlines();
                        Token eq = next();
                        if (!eq.is("=") && !eq.is(":")) {
                            throw new NotLiteral();
                        }
                        Type field = type();
                        ObjectNode fieldSchema = field.schema();
                        if (field.defaultValue() != null) {
                            fieldSchema.set("default", field.defaultValue());
                        }
                        properties.set(key.text(), fieldSchema);
                        if (!field.optional()) {
                            required.add(key.text());
                        }
                        if (peek().is(",")) {
                            next();
                        }
                    }
                    expect(")");
                    if (!required.isEmpty()) {
                        schema.set("required", required);
                    }
                    return new Type(schema, false, null);
                }
                case "optional" -> {
                    expect("(");
                    Type inner = type();
                    skipNewlines();
                    JsonNode defaultValue = null;
                    if (peek().is(",")) {
                        next();
                        int from = p;
                        int depth = 0;
                        while (p < t.size() && !(depth == 0 && t.get(p).is(")"))) {
                            Token x = t.get(p);
                            if (x.is("(") || x.is("[") || x.is("{")) {
                                depth++;
                            } else if (x.is(")") || x.is("]") || x.is("}")) {
                                depth--;
                            }
                            p++;
                        }
                        defaultValue = literal(new Expr(t.subList(from, p), ""));
                    }
                    expect(")");
                    return new Type(inner.schema(), true, defaultValue);
                }
                default -> throw new NotLiteral();
            }
        }

        static Type plain(String jsonType) {
            ObjectNode schema = F.objectNode();
            schema.put("type", jsonType);
            return new Type(schema, false, null);
        }
    }
}
