package com.iaf.console.bff.digests.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rules that write a digest when no assistant does ({@code source: rules}): the headline
 * joins the week's main numbers, and each section says its numbers in a sentence or two, or why
 * it has none.
 */
public final class DigestTemplate {

    private DigestTemplate() {
    }

    public static DigestText write(DigestFacts facts) {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put(Digest.CHANGES, changes(facts));
        texts.put(Digest.DRIFT, drift(facts.drift()));
        texts.put(Digest.COST, cost(facts.cost()));
        texts.put(Digest.ACCESS, access(facts));
        return new DigestText(headline(facts), texts);
    }

    static String headline(DigestFacts facts) {
        List<String> parts = new ArrayList<>();
        DigestFacts.Changes c = facts.changes();
        if (c.available()) {
            parts.add(c.requests() == 0 ? "no requests through the console"
                    : count(c.requests(), "request", "requests") + ", " + percent(c.selfServedShare()) + "% self-served"
                            + (c.failed() > 0 ? ", " + c.failed() + " failed" : ""));
        }
        DigestFacts.Drift d = facts.drift();
        if (d.available() && (d.found() > 0 || d.open() > 0)) {
            parts.add(count(d.found(), "drift finding", "drift findings") + " found, " + d.open() + " open");
        }
        DigestFacts.Cost k = facts.cost();
        if (k.available()) {
            parts.add("spend " + money(k.spend(), k.currency()) + change(k.change()));
        }
        DigestFacts.Access a = facts.access();
        if (a.available() && !a.facts().isEmpty()) {
            parts.add(count(a.facts().size(), "access grant", "access grants"));
        }
        if (parts.isEmpty()) {
            return "The week of " + facts.weekStart() + " in " + facts.accountLabel() + ": no numbers could be had.";
        }
        return capitalized(String.join("; ", parts)) + ".";
    }

    static String changes(DigestFacts facts) {
        DigestFacts.Changes c = facts.changes();
        if (!c.available()) {
            return "The week's requests could not be counted: " + c.unavailable() + ".";
        }
        if (c.requests() == 0) {
            return "No requests were started through the console in the week of " + facts.weekStart() + ".";
        }
        StringBuilder text = new StringBuilder();
        text.append(capitalized(count(c.requests(), "request was", "requests were"))).append(" started through the console: ")
                .append(c.selfServed()).append(" (").append(percent(c.selfServedShare())).append("%) self-served and ").append(c.failed())
                .append(" failed.");
        if (!c.busiest().isEmpty()) {
            List<String> named = c.busiest().stream().map(b -> b.workflow() + " (" + b.requests() + ")").toList();
            text.append(named.size() == 1 ? " The busiest workflow was " : " The busiest workflows were ").append(joined(named)).append('.');
        }
        return text.toString();
    }

    static String drift(DigestFacts.Drift d) {
        if (!d.available()) {
            return "Drift is not in this digest: " + d.unavailable() + ".";
        }
        if (d.found() == 0 && d.fixed() == 0 && d.open() == 0) {
            return "No drift was found, and no findings are open.";
        }
        return capitalized(count(d.found(), "drift finding was", "drift findings were")) + " found and " + d.fixed() + " fixed; "
                + count(d.open(), "finding is", "findings are") + " open at the end of the week.";
    }

    static String cost(DigestFacts.Cost k) {
        if (!k.available()) {
            return "Spend is not in this digest: " + k.unavailable() + ".";
        }
        StringBuilder text = new StringBuilder("The account spent ").append(money(k.spend(), k.currency()));
        if (k.previous() == null || k.previous().signum() == 0) {
            text.append("; the week before it spent nothing.");
        } else {
            Double change = k.change();
            text.append(change == null || change == 0d ? ", the same as" : change > 0 ? ", up " + percent(Math.abs(change)) + "% on" : ", down "
                    + percent(Math.abs(change)) + "% on").append(" the week before (").append(money(k.previous(), k.currency())).append(").");
        }
        if (!k.top().isEmpty()) {
            List<String> named = k.top().stream().map(s -> s.label() + " (" + money(s.amount(), k.currency()) + ")").toList();
            text.append(" Most of it went to ").append(joined(named)).append('.');
        }
        return text.toString();
    }

    static String access(DigestFacts facts) {
        DigestFacts.Access a = facts.access();
        if (!a.available()) {
            return "Access changes are not in this digest: " + a.unavailable() + ".";
        }
        List<String> parts = new ArrayList<>();
        long memberships = a.count(AccessFact.MEMBERSHIP);
        long roles = a.count(AccessFact.ROLE);
        long keys = a.count(AccessFact.ACCESS_KEY);
        long wildcards = a.count(AccessFact.WILDCARD_POLICY);
        if (memberships > 0) {
            parts.add(count(memberships, "group membership", "group memberships") + " granted");
        }
        if (roles > 0) {
            parts.add(count(roles, "role", "roles") + " given to groups");
        }
        if (keys > 0) {
            parts.add(count(keys, "access key", "access keys") + " created");
        }
        if (wildcards > 0) {
            parts.add(count(wildcards, "policy", "policies") + " now allowing every action (*)");
        }
        String text = parts.isEmpty()
                ? "No one joined a group, no group was given a role, no access key was created and no policy came to allow every action."
                : capitalized(joined(parts)) + ".";
        if (a.partial(facts.weekStart())) {
            text += " The console has followed the identity service's events only since " + a.since() + ", so changes before then are not counted.";
        }
        return text;
    }

    // ---- words

    static String count(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    static long percent(Double share) {
        return share == null ? 0 : Math.round(share * 100);
    }

    static String change(Double change) {
        if (change == null) {
            return "";
        }
        if (change == 0d) {
            return ", the same as the week before";
        }
        return (change > 0 ? ", up " : ", down ") + percent(Math.abs(change)) + "%";
    }

    /** An amount as people read it: {@code $1,234.50} in dollars, {@code 1,234.50 EUR} otherwise. */
    static String money(BigDecimal amount, String currency) {
        String number = String.format(Locale.ROOT, "%,.2f", amount == null ? BigDecimal.ZERO : amount);
        return currency == null || "USD".equals(currency) ? "$" + number : number + " " + currency;
    }

    static String joined(List<String> items) {
        if (items.size() <= 1) {
            return String.join("", items);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    static String capitalized(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
