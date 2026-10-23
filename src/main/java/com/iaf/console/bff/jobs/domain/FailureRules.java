package com.iaf.console.bff.jobs.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The failure explainer's rules over error classes (WP 9.2): the providers' common classes (their
 * errors tables map HTTP answers to {@code not_found}, {@code conflict}, {@code rate_limited} and
 * {@code unavailable}) and the platform's own. A class no rule knows goes to the copilot, or gets
 * {@link #generic} when there is none.
 */
public final class FailureRules {

    private record Rule(String title, String fix, String confidence) {
    }

    private static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry("unavailable", new Rule("The provider was unavailable",
                    "Retry once the provider is back. Nothing in the request needs to change.", FailureAdvice.HIGH)),
            Map.entry("rate_limited", new Rule("The provider asked the platform to slow down",
                    "Retry in a few minutes. Nothing in the request needs to change.", FailureAdvice.HIGH)),
            Map.entry("timeout", new Rule("The provider took too long to answer",
                    "Retry. If it times out again, the provider may be overloaded, so check its status first.", FailureAdvice.MEDIUM)),
            Map.entry("not_found", new Rule("Something the request names was not found",
                    "Check the values the failed step used, correct the one that names a missing item, and retry with the fix.", FailureAdvice.MEDIUM)),
            Map.entry("conflict", new Rule("The provider refused the change because of a conflict",
                    "Wait for other changes on the resource to finish, or choose another name if the one asked for is taken, then retry.",
                    FailureAdvice.MEDIUM)),
            Map.entry("approval.rejected", new Rule("An approver rejected the request",
                    "Change the request as the approver asked, then retry it. It asks for approval again.", FailureAdvice.MEDIUM)),
            Map.entry("resource.notReady", new Rule("A resource the request waits on was not ready",
                    "Wait until that resource is ready, then retry.", FailureAdvice.HIGH)),
            Map.entry("resource.idCollision", new Rule("The provider's resource is already registered to another record",
                    "Ask the platform team to resolve the collision. A retry alone fails the same way.", FailureAdvice.HIGH)),
            Map.entry("batch.rowsFailed", new Rule("Some rows of the batch failed",
                    "Open the failed rows to see why each one failed, then retry them. The rows that went through are left alone.", FailureAdvice.HIGH)),
            Map.entry("platform.internal", new Rule("The platform failed unexpectedly",
                    "Retry once. If it fails again, send the platform team this request's id.", FailureAdvice.LOW)));

    private FailureRules() {
    }

    /** The rule's advice for a class, the platform's message as the detail; empty for a class no rule knows. */
    public static Optional<FailureAdvice> forClass(FailureFacts facts) {
        Rule rule = RULES.get(facts.errorClass());
        return rule == null ? Optional.empty()
                : Optional.of(new FailureAdvice(rule.title(), facts.message(), rule.fix(), Map.of(), rule.confidence(), List.of()));
    }

    /** What is said of a class no rule knows when the copilot cannot answer either. */
    public static FailureAdvice generic(FailureFacts facts) {
        String where = facts.step() == null ? "A step" : "The step " + (facts.step().name() != null ? facts.step().name() : facts.step().id());
        return new FailureAdvice(where + " failed (" + facts.errorClass() + ")", facts.message(),
                "Read the step's error, correct the input it points to, and retry with the fix.", Map.of(), FailureAdvice.LOW, List.of());
    }

    /** Whether a rule knows the class. */
    public static boolean knows(String errorClass) {
        return RULES.containsKey(errorClass);
    }
}
