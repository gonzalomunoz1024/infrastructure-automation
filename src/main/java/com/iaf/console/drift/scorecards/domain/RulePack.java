package com.iaf.console.drift.scorecards.domain;

import java.util.List;
import java.util.Optional;

/** A rule pack (kind: RulePack, the hub's rule-packs/): its name, title, version and rules. */
public record RulePack(String name, String title, String description, int version, String owner, List<Rule> rules) {

    public RulePack {
        rules = List.copyOf(rules);
    }

    public Optional<Rule> rule(String id) {
        return rules.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** The rules that may apply to a type. */
    public List<Rule> rulesFor(String type) {
        return rules.stream().filter(r -> r.appliesToType(type)).toList();
    }

    /** The Rego package path of the pack: {@code console/scorecards/<pack>}, as OPA's Data API addresses it. */
    public String regoPath() {
        return "console/scorecards/" + name.replace('-', '_');
    }
}
