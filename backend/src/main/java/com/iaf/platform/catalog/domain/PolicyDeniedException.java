package com.iaf.platform.catalog.domain;

import java.util.List;

import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.ports.PolicyPort.Rule;

/** The policy engine said no: 403 with the rules that fired. */
public class PolicyDeniedException extends ForbiddenException {

    private final transient List<Rule> rules;
    private final String engine;

    public PolicyDeniedException(String message, List<Rule> rules, String engine) {
        super(message);
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.engine = engine;
    }

    public List<Rule> rules() {
        return rules;
    }

    public String engine() {
        return engine;
    }
}
