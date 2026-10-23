package com.iaf.console.drift.scorecards.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.iaf.console.drift.drift.domain.Json;

/**
 * The rule packs in force, by name. {@link #digest} names the set (pack names and versions): an
 * evaluation made under another digest is made again on the resource's next read.
 */
public record RulePacks(List<RulePack> packs, String digest) {

    public static RulePacks of(List<RulePack> packs) {
        List<RulePack> sorted = packs.stream().sorted(Comparator.comparing(RulePack::name)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).name().equals(sorted.get(i - 1).name())) {
                throw new IllegalArgumentException("two rule packs are named " + sorted.get(i).name());
            }
        }
        String digest = Json.sha256(sorted.stream().map(p -> p.name() + "@" + p.version()).collect(Collectors.joining(","))).substring(0, 16);
        return new RulePacks(sorted, digest);
    }

    public Optional<RulePack> pack(String name) {
        return packs.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public Optional<Rule> rule(RuleKey key) {
        return pack(key.pack()).flatMap(p -> p.rule(key.rule()));
    }

    public List<Rule> allRules() {
        return packs.stream().flatMap(p -> p.rules().stream()).toList();
    }
}
