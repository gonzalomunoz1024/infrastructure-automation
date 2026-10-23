package com.iaf.console.drift.scorecards.ports.outbound;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.scorecards.domain.Rule;
import com.iaf.console.drift.scorecards.domain.RulePack;
import com.iaf.console.drift.scorecards.domain.Verdict;

import reactor.core.publisher.Mono;

/**
 * The policy engine the rules run on: the firm's OPA, or the embedded engine of the rules'
 * declarative half until that one is wired in (hub decision 26).
 */
public interface PolicyEnginePort {

    /**
     * The verdict of each given rule of the pack for one input, by rule id; a rule missing from the
     * answer could not be judged. An error means the engine could not be asked at all.
     */
    Mono<Map<String, Verdict>> evaluate(RulePack pack, List<Rule> rules, ObjectNode input);

    /** The engine's name, for logs and metrics: {@code embedded} or {@code opa}. */
    String name();
}
