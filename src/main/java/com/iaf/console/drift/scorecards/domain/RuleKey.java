package com.iaf.console.drift.scorecards.domain;

/** A rule by pack and id; {@code <pack>/<ruleId>} as text. */
public record RuleKey(String pack, String rule) implements Comparable<RuleKey> {

    /** The counters of whole resources ride beside the rules' under this key: passed counts resources failing nothing, failed those failing a rule. */
    public static final RuleKey RESOURCES = new RuleKey("_resources", "_all");

    public static RuleKey parse(String text) {
        int slash = text.indexOf('/');
        if (slash <= 0 || slash == text.length() - 1) {
            throw new IllegalArgumentException("a rule key is <pack>/<rule>: " + text);
        }
        return new RuleKey(text.substring(0, slash), text.substring(slash + 1));
    }

    public String text() {
        return pack + "/" + rule;
    }

    @Override
    public int compareTo(RuleKey o) {
        return text().compareTo(o.text());
    }

    @Override
    public String toString() {
        return text();
    }
}
