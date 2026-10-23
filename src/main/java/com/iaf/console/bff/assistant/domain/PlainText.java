package com.iaf.console.bff.assistant.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text as the copilot may show it: plain sentences in short paragraphs. Whatever an assistant
 * writes passes through here before it is stored, so HTML (tags, comments, scripts, entities) and
 * Markdown (headings, emphasis, code, links, quotes, rules) come out as their words, and control
 * and direction-changing characters are dropped.
 */
public final class PlainText {

    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?(-->|$)");
    private static final Pattern BREAK = Pattern.compile("(?i)<\\s*br\\s*/?\\s*>");
    private static final Pattern BLOCK_END = Pattern.compile("(?i)<\\s*/\\s*(p|div|h[1-6]|ul|ol|table|tr|blockquote|pre)\\s*>");
    private static final Pattern ITEM = Pattern.compile("(?i)<\\s*li\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("<\\s*/?\\s*[A-Za-z!?][^>]*>");
    private static final Pattern FENCE = Pattern.compile("(?m)^[ \\t]*(```|~~~).*$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]\\n]*)]\\([^)\\n]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]+)]\\([^)\\n]*\\)");
    private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]{0,3}#{1,6}[ \\t]+");
    private static final Pattern QUOTE = Pattern.compile("(?m)^[ \\t]{0,3}>[ \\t]?");
    private static final Pattern RULE = Pattern.compile("(?m)^[ \\t]*([-*_])([ \\t]*\\1){2,}[ \\t]*$");
    private static final Pattern BULLET = Pattern.compile("(?m)^([ \\t]*)[*+][ \\t]+");
    private static final Pattern BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern STRIKE = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");
    private static final Pattern EMPHASIS = Pattern.compile("(?<![\\w*])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![\\w*])");
    private static final Pattern CODE = Pattern.compile("`+([^`\\n]*)`+");
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\u00A0\\u2000-\\u200A\\u3000]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern NEWLINES = Pattern.compile("\\s*\\n\\s*");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp);");

    private PlainText() {
    }

    /** The text without markup, paragraphs kept, at most {@code max} characters; null when nothing is left. */
    public static String of(String text, int max) {
        if (text == null) {
            return null;
        }
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        s = entities(s);
        s = SCRIPT.matcher(s).replaceAll("");
        s = COMMENT.matcher(s).replaceAll("");
        s = BREAK.matcher(s).replaceAll("\n");
        s = BLOCK_END.matcher(s).replaceAll("\n\n");
        s = ITEM.matcher(s).replaceAll("\n- ");
        s = TAG.matcher(s).replaceAll("");
        s = FENCE.matcher(s).replaceAll("");
        s = IMAGE.matcher(s).replaceAll("$1");
        s = LINK.matcher(s).replaceAll("$1");
        s = HEADING.matcher(s).replaceAll("");
        s = QUOTE.matcher(s).replaceAll("");
        s = RULE.matcher(s).replaceAll("");
        s = BULLET.matcher(s).replaceAll("$1- ");
        s = BOLD.matcher(s).replaceAll("$2");
        s = STRIKE.matcher(s).replaceAll("$1");
        s = EMPHASIS.matcher(s).replaceAll("$1");
        s = CODE.matcher(s).replaceAll("$1");
        s = INVISIBLE.matcher(s).replaceAll("");
        StringBuilder lines = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            lines.append(SPACES.matcher(line).replaceAll(" ").strip()).append('\n');
        }
        s = BLANK_LINES.matcher(lines.toString()).replaceAll("\n\n").strip();
        return s.isEmpty() ? null : cut(s, max);
    }

    /** The same on one line, for a label or a title. */
    public static String line(String text, int max) {
        String s = of(text, Integer.MAX_VALUE);
        return s == null ? null : cut(NEWLINES.matcher(s).replaceAll(" "), max);
    }

    /** At most {@code max} characters, cut at a space when one is near and marked with an ellipsis. */
    static String cut(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int end = Math.max(1, max - 1);
        if (Character.isLowSurrogate(s.charAt(end)) && end > 1) {
            end--;
        }
        int space = s.lastIndexOf(' ', end);
        if (space > end - 24 && space > 0) {
            end = space;
        }
        return s.substring(0, end).stripTrailing() + "…";
    }

    /** The named and numeric entities an assistant is likely to write, decoded once, so encoded markup is stripped like the rest. */
    private static String entities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (m.find()) {
            String name = m.group(1);
            String decoded = switch (name) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                default -> codePoint(name);
            };
            m.appendReplacement(out, Matcher.quoteReplacement(decoded));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String codePoint(String numeric) {
        try {
            int cp = numeric.charAt(1) == 'x' || numeric.charAt(1) == 'X' ? Integer.parseInt(numeric.substring(2), 16) : Integer.parseInt(numeric.substring(1));
            return Character.isValidCodePoint(cp) && !Character.isISOControl(cp) ? new String(Character.toChars(cp)) : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }
}
