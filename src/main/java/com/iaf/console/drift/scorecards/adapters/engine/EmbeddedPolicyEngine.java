package com.iaf.console.drift.scorecards.adapters.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.scorecards.domain.Rule;
import com.iaf.console.drift.scorecards.domain.RulePack;
import com.iaf.console.drift.scorecards.domain.Verdict;
import com.iaf.console.drift.scorecards.ports.outbound.PolicyEnginePort;

import reactor.core.publisher.Mono;

/**
 * The embedded engine: each rule's declarative {@code types}, {@code when} and {@code check},
 * which say what its Rego says (the hub's check runs every rule's examples through both). It
 * stands in for the firm's OPA until that is wired in, as the platform's own policy check is a
 * mock today, and answers in microseconds without a call.
 */
public final class EmbeddedPolicyEngine implements PolicyEnginePort {

    @Override
    public Mono<Map<String, Verdict>> evaluate(RulePack pack, List<Rule> rules, ObjectNode input) {
        return Mono.fromSupplier(() -> {
            Map<String, Verdict> out = new LinkedHashMap<>();
            for (Rule rule : rules) {
                out.put(rule.id(), rule.judge(input));
            }
            return out;
        });
    }

    @Override
    public String name() {
        return "embedded";
    }
}
