package com.iaf.platform.runs.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.iaf.platform.common.expressions.ExpressionContext;
import com.iaf.platform.common.expressions.ExpressionEngine;
import com.iaf.platform.common.expressions.ExpressionException;

/**
 * The demo's {@code script} runner: a tiny, deterministic JavaScript subset evaluated with the
 * {@link ExpressionEngine}. Statements are separated by {@code ;} or newlines;
 * {@code const|let|var name = expr} declares a value, {@code name = expr} reassigns it,
 * {@code return expr} ends the script. Expressions are what the engine understands (member
 * access, comparisons, arithmetic, string and array methods, arrow functions, object and array
 * literals with shorthand properties) plus {@code Math.max/min}, {@code Object.keys},
 * {@code Number()} and {@code String()}. The identifier {@code input} is the step's input;
 * every other bare identifier must have been declared. Production runs scripts on GraalJS.
 */
public final class MiniScript {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Set<String> KEYWORDS = Set.of("true", "false", "null", "undefined");

    private MiniScript() {
    }

    /** A script that cannot be parsed or evaluated. */
    public static class ScriptException extends IllegalArgumentException {
        public ScriptException(String message) {
            super(message);
        }
    }

    public static JsonNode run(String code, JsonNode input) {
        if (code == null || code.isBlank()) {
            return NullNode.getInstance();
        }
        Map<String, JsonNode> declared = new HashMap<>();
        JsonNode in = input == null ? F.objectNode() : input;
        for (String statement : statements(code)) {
            String s = statement.trim();
            if (s.isEmpty() || s.startsWith("//")) {
                continue;
            }
            if (s.startsWith("return")) {
                String rest = s.substring("return".length()).trim();
                if (rest.startsWith("=") || (!rest.isEmpty() && Character.isLetterOrDigit(rest.charAt(0)) && !s.startsWith("return "))) {
                    // an identifier that starts with "return": treat as an expression
                    evaluate(s, in, declared);
                    continue;
                }
                return rest.isEmpty() ? NullNode.getInstance() : evaluate(rest, in, declared);
            }
            String decl = declaration(s);
            if (decl != null) {
                int eq = s.indexOf('=');
                String name = s.substring(decl.length(), eq).trim();
                if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                    throw new ScriptException("Cannot declare '" + name + "'; destructuring is not supported");
                }
                declared.put(name, evaluate(s.substring(eq + 1).trim(), in, declared));
                continue;
            }
            int eq = assignment(s);
            if (eq > 0) {
                String name = s.substring(0, eq).trim();
                if (!declared.containsKey(name)) {
                    throw new ScriptException("'" + name + "' is not declared; use const " + name + " = ...");
                }
                declared.put(name, evaluate(s.substring(eq + 1).trim(), in, declared));
                continue;
            }
            evaluate(s, in, declared);
        }
        return NullNode.getInstance();
    }

    private static String declaration(String s) {
        for (String kw : List.of("const ", "let ", "var ")) {
            if (s.startsWith(kw) && s.indexOf('=') > 0) {
                return kw;
            }
        }
        return null;
    }

    /** The index of a plain {@code =} assignment at depth 0 (not {@code ==}, {@code =>}, {@code <=}, ...), or -1. */
    private static int assignment(String s) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == '=' && depth == 0) {
                char prev = i > 0 ? s.charAt(i - 1) : ' ';
                char next = i + 1 < s.length() ? s.charAt(i + 1) : ' ';
                if (next != '=' && next != '>' && prev != '=' && prev != '!' && prev != '<' && prev != '>') {
                    return s.substring(0, i).trim().matches("[A-Za-z_$][A-Za-z0-9_$]*") ? i : -1;
                }
            }
        }
        return -1;
    }

    /** Split on {@code ;} and newlines outside strings and brackets. */
    static List<String> statements(String code) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && i + 1 < code.length()) {
                    cur.append(code.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '/') {
                while (i < code.length() && code.charAt(i) != '\n') {
                    i++;
                }
                i--;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                quote = c;
                cur.append(c);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                cur.append(c);
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                cur.append(c);
            } else if ((c == ';' || c == '\n') && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString());
        }
        return out;
    }

    private static JsonNode evaluate(String expr, JsonNode input, Map<String, JsonNode> declared) {
        String rewritten = rewrite(expr, declared.keySet());
        ExpressionContext ctx = ExpressionContext.of(input, input, Map.of(), declared, null, null, null, null);
        try {
            return ExpressionEngine.evaluate("{{ " + rewritten + " }}", ctx);
        } catch (ExpressionException e) {
            throw new ScriptException("Script error in '" + expr.trim() + "': " + e.getMessage());
        }
    }

    /**
     * Rewrite a JavaScript expression into the engine's grammar: {@code input} and declared names
     * become {@code $} references, shorthand properties expand, {@code Math.max} becomes {@code $max}.
     * Lambda parameters are left alone (the engine binds them).
     */
    static String rewrite(String expr, Set<String> declared) {
        if (expr.indexOf('`') >= 0) {
            throw new ScriptException("Template literals are not supported; build strings with +");
        }
        Set<String> params = lambdaParams(expr);
        List<Token> tokens = tokenize(expr);
        StringBuilder out = new StringBuilder();
        List<Character> brackets = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            Token prev = null;
            for (int k = i - 1; k >= 0; k--) {
                if (tokens.get(k).kind != Kind.SPACE) {
                    prev = tokens.get(k);
                    break;
                }
            }
            Token next = null;
            for (int k = i + 1; k < tokens.size(); k++) {
                if (tokens.get(k).kind != Kind.SPACE) {
                    next = tokens.get(k);
                    break;
                }
            }
            if (t.kind == Kind.PUNCT) {
                if (t.text.equals("(") || t.text.equals("[") || t.text.equals("{")) {
                    brackets.add(t.text.charAt(0));
                } else if (t.text.equals(")") || t.text.equals("]") || t.text.equals("}")) {
                    if (!brackets.isEmpty()) {
                        brackets.remove(brackets.size() - 1);
                    }
                }
                out.append(t.text);
                continue;
            }
            if (t.kind != Kind.IDENT) {
                out.append(t.text);
                continue;
            }
            String name = t.text;
            boolean member = prev != null && prev.kind == Kind.PUNCT && prev.text.equals(".");
            boolean inObject = !brackets.isEmpty() && brackets.get(brackets.size() - 1) == '{';
            boolean key = inObject && next != null && next.kind == Kind.PUNCT && next.text.equals(":")
                    && prev != null && prev.kind == Kind.PUNCT && (prev.text.equals("{") || prev.text.equals(","));
            boolean shorthand = inObject && !member && prev != null && prev.kind == Kind.PUNCT && (prev.text.equals("{") || prev.text.equals(","))
                    && next != null && next.kind == Kind.PUNCT && (next.text.equals(",") || next.text.equals("}"));
            if (member || key || KEYWORDS.contains(name) || params.contains(name) || name.startsWith("$")) {
                out.append(name);
                continue;
            }
            if (next != null && next.kind == Kind.PUNCT && next.text.equals("=>")) {
                out.append(name);
                continue;
            }
            if (name.equals("Math") || name.equals("Object")) {
                Token method = i + 2 < tokens.size() ? tokens.get(i + 2) : null;
                if (i + 1 < tokens.size() && tokens.get(i + 1).text.equals(".") && method != null && method.kind == Kind.IDENT
                        && Set.of("max", "min", "keys").contains(method.text)) {
                    out.append('$').append(method.text);
                    i += 2;
                    continue;
                }
            }
            if ((name.equals("Number") || name.equals("String")) && next != null && next.text.equals("(")) {
                out.append('$').append(name.toLowerCase());
                continue;
            }
            if (name.equals("input") || declared.contains(name)) {
                if (shorthand) {
                    out.append(name).append(": $").append(name);
                } else {
                    out.append('$').append(name);
                }
                continue;
            }
            throw new ScriptException("'" + name + "' is not declared (scripts see input and the names they declare)");
        }
        return out.toString().replace("}}", "} }").replace("{{", "{ {");
    }

    /** Every parameter name of every arrow function in the expression. */
    private static Set<String> lambdaParams(String expr) {
        Set<String> params = new HashSet<>();
        int idx = 0;
        while ((idx = expr.indexOf("=>", idx)) >= 0) {
            int end = idx;
            while (end > 0 && Character.isWhitespace(expr.charAt(end - 1))) {
                end--;
            }
            if (end > 0 && expr.charAt(end - 1) == ')') {
                int open = expr.lastIndexOf('(', end - 1);
                if (open >= 0) {
                    for (String p : expr.substring(open + 1, end - 1).split(",")) {
                        if (!p.isBlank()) {
                            params.add(p.trim());
                        }
                    }
                }
            } else {
                int start = end;
                while (start > 0 && (Character.isLetterOrDigit(expr.charAt(start - 1)) || expr.charAt(start - 1) == '_')) {
                    start--;
                }
                if (start < end) {
                    params.add(expr.substring(start, end));
                }
            }
            idx += 2;
        }
        return params;
    }

    private enum Kind {
        IDENT, STRING, NUMBER, PUNCT, SPACE
    }

    private record Token(Kind kind, String text) {
    }

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                int start = i;
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                out.add(new Token(Kind.SPACE, s.substring(start, i)));
            } else if (c == '"' || c == '\'') {
                int start = i++;
                while (i < s.length() && s.charAt(i) != c) {
                    if (s.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, s.length());
                out.add(new Token(Kind.STRING, s.substring(start, i)));
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '$')) {
                    i++;
                }
                out.add(new Token(Kind.IDENT, s.substring(start, i)));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Token(Kind.NUMBER, s.substring(start, i)));
            } else if (s.startsWith("=>", i) || s.startsWith("===", i) || s.startsWith("!==", i)) {
                String t = s.startsWith("=>", i) ? "=>" : s.substring(i, i + 3);
                out.add(new Token(Kind.PUNCT, t));
                i += t.length();
            } else if (s.startsWith("==", i) || s.startsWith("!=", i) || s.startsWith("<=", i) || s.startsWith(">=", i)
                    || s.startsWith("&&", i) || s.startsWith("||", i) || s.startsWith("??", i)) {
                out.add(new Token(Kind.PUNCT, s.substring(i, i + 2)));
                i += 2;
            } else {
                out.add(new Token(Kind.PUNCT, String.valueOf(c)));
                i++;
            }
        }
        return out;
    }
}
