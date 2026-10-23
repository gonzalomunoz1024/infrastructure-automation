package com.iaf.console.drift.scorecards.application;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iaf.console.drift.drift.application.IdempotentCommands;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.OnBehalfOfPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.drift.drift.ports.outbound.WorkflowStarterPort;
import com.iaf.console.drift.scorecards.domain.EvaluationInput;
import com.iaf.console.drift.scorecards.domain.RemediationInput;
import com.iaf.console.drift.scorecards.domain.RemediationSpec;
import com.iaf.console.drift.scorecards.domain.ResourceEvaluation;
import com.iaf.console.drift.scorecards.domain.Rule;
import com.iaf.console.drift.scorecards.domain.RuleResult;
import com.iaf.console.drift.scorecards.domain.RuleStatus;
import com.iaf.console.drift.scorecards.domain.Templates;
import com.iaf.console.drift.scorecards.domain.Views;
import com.iaf.console.drift.scorecards.ports.outbound.EvaluationStorePort;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.model.CatalogEntry;

import reactor.core.publisher.Mono;

/**
 * A rule's one-click fix for one failing resource, always a person's act and never automatic
 * (hub decision 26, as decision 10 makes a revert). A {@code workflow} fix starts the rule's verb
 * of the type through the account's platform service account on behalf of the person, with the
 * job label {@code scorecard-rule}; a {@code tags} fix sets the tags through the state service in
 * the person's name. Either is idempotent under its key; the resource is scored again when the
 * drift service next reads it or hears the change.
 */
public final class RemediationService {

    private static final Logger log = LoggerFactory.getLogger(RemediationService.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");
    /** The job label that ties a fix's job to its rule ({@code <pack>.<rule>}). */
    public static final String RULE_LABEL = "scorecard-rule";

    private final ScorecardQueries queries;
    private final EvaluationStorePort evaluations;
    private final WatchStorePort watches;
    private final PlatformPort platform;
    private final StateServicePort state;
    private final WorkflowStarterPort starter;
    private final OnBehalfOfPort onBehalfOf;
    private final IdempotentCommands idempotent;
    private final ScorecardMetrics metrics;

    public RemediationService(ScorecardQueries queries, EvaluationStorePort evaluations, WatchStorePort watches, PlatformPort platform,
            StateServicePort state, WorkflowStarterPort starter, OnBehalfOfPort onBehalfOf, IdempotentCommands idempotent, ScorecardMetrics metrics) {
        this.queries = queries;
        this.evaluations = evaluations;
        this.watches = watches;
        this.platform = platform;
        this.state = state;
        this.starter = starter;
        this.onBehalfOf = onBehalfOf;
        this.idempotent = idempotent;
        this.metrics = metrics;
    }

    /** What the person asks: the resource, values for the fix's inputs, why, and who when the BFF relays it. */
    public record Request(String wfrn, ObjectNode input, String reason, String actor) {
    }

    public Mono<IdempotentCommands.Outcome> remediate(AccountScope scope, String pack, String ruleId, Request request, String idempotencyKey,
            String requestId, String relayedAssertion, OnBehalfOfPort.Person person) {
        if (request == null || request.wfrn() == null || request.wfrn().isBlank()) {
            throw new IllegalArgumentException("wfrn is required");
        }
        Rule rule = queries.rule(pack, ruleId);
        RemediationSpec spec = rule.remediation();
        if (spec == null) {
            throw new ConflictException("remediation.none: the rule " + pack + "/" + ruleId + " has no one-click fix", Map.of("rule", pack + "/" + ruleId));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "remediate");
        body.put("rule", pack + "/" + ruleId);
        body.put("wfrn", request.wfrn());
        body.put("input", request.input() == null ? Map.of() : request.input());
        if (request.reason() != null) {
            body.put("reason", request.reason());
        }
        int status = spec.tags() ? 200 : 202;
        return idempotent.run(scope.accountId(), idempotencyKey, body, status,
                () -> doRemediate(scope, rule, spec, request, idempotencyKey, requestId, relayedAssertion, person).map(Json::objectNode))
                .doOnSuccess(o -> metrics.remediation(spec.kind(), "started"))
                .doOnError(e -> metrics.remediation(spec.kind(), "refused"));
    }

    private Mono<Views.Remediation> doRemediate(AccountScope scope, Rule rule, RemediationSpec spec, Request request, String idempotencyKey,
            String requestId, String relayedAssertion, OnBehalfOfPort.Person person) {
        return evaluations.find(scope, request.wfrn())
                .switchIfEmpty(Mono.error(() -> NotFoundException.of("evaluation of", request.wfrn())))
                .flatMap(evaluation -> {
                    RuleResult result = evaluation.result(rule.key());
                    if (result == null || result.status() != RuleStatus.FAIL) {
                        return Mono.error(new ConflictException("evaluation.passing: " + request.wfrn() + " does not fail " + rule.key().text(),
                                Map.of("status", result == null ? "not-applicable" : result.status().id())));
                    }
                    return watches.find(request.wfrn())
                            .switchIfEmpty(Mono.error(() -> new ConflictException("resource.unwatched: the resource left the inventory",
                                    Map.of("wfrn", request.wfrn()))))
                            .flatMap(watch -> {
                                if (watch.inFlight()) {
                                    return Mono.error(new ConflictException("resource.inFlight: a change is running on the resource; try again when it ends",
                                            Map.of("state", watch.state())));
                                }
                                ObjectNode resource = (ObjectNode) EvaluationInput.of(watch, evaluation.observed()).get("resource");
                                ObjectNode inputs = inputs(spec, request.input(), resource);
                                return spec.tags() ? tags(rule, spec, watch, resource, inputs, request, idempotencyKey, requestId, person)
                                        : workflow(rule, spec, watch, evaluation, resource, inputs, request, idempotencyKey, requestId, relayedAssertion,
                                                person);
                            });
                });
    }

    private Mono<Views.Remediation> tags(Rule rule, RemediationSpec spec, Watch watch, ObjectNode resource, ObjectNode inputs, Request request,
            String idempotencyKey, String requestId, OnBehalfOfPort.Person person) {
        String actor = person != null && person.subject() != null ? person.subject() : request.actor();
        if (actor == null || actor.isBlank()) {
            return Mono.error(new ConflictException("remediation.needsPerson: a fix is made in the name of the person who asks for it; the call names none",
                    Map.of("field", "actor")));
        }
        Map<String, String> set = new TreeMap<>();
        List<Problem> problems = new ArrayList<>();
        spec.set().forEach((tag, template) -> {
            JsonNode v = Templates.render(TextNode.valueOf(template), inputs, resource);
            if (v == null || v.isNull() || v.asText().isBlank()) {
                problems.add(Problem.error("remediation.input", "/input", "the tag " + tag + " has no value"));
            } else {
                set.put(tag, v.asText());
            }
        });
        if (!problems.isEmpty()) {
            return Mono.error(new InvalidInputException("remediation.input: " + problems.get(0).message(), problems));
        }
        String key = idempotencyKey != null && !idempotencyKey.isBlank() ? "scorecard:" + watch.accountId() + ":" + idempotencyKey.trim() : null;
        return state.tag(watch.wfrn(), set, actor, requestId, key)
                .doOnSuccess(r -> log.info("{}: tags {} set by {} to fix {}", watch.wfrn(), set.keySet(), actor, rule.key()))
                .thenReturn(new Views.Remediation(watch.wfrn(), rule.pack(), rule.id(), RemediationSpec.TAGS, null, null, set));
    }

    private Mono<Views.Remediation> workflow(Rule rule, RemediationSpec spec, Watch watch, ResourceEvaluation evaluation, ObjectNode resource,
            ObjectNode inputs, Request request, String idempotencyKey, String requestId, String relayedAssertion, OnBehalfOfPort.Person person) {
        return entry(spec, watch.type()).zipWith(assertion(watch, relayedAssertion, person)).flatMap(found -> {
            CatalogEntry entry = found.getT1();
            String assertion = found.getT2();
            String actor = person != null && person.subject() != null ? person.subject() : request.actor() != null ? request.actor() : "console";
            String reason = request.reason() != null ? request.reason() : "compliance: " + rule.title() + " (" + rule.key().text() + ")";
            Map<String, Object> input = new LinkedHashMap<>();
            if (entry.subjectInput() != null) {
                input.put(entry.subjectInput(), subjectOf(watch));
            }
            ObjectNode changes = Json.object();
            JsonNode rendered = Templates.render(spec.input() == null ? Json.object() : spec.input(), inputs, resource);
            for (Iterator<Map.Entry<String, JsonNode>> it = rendered.properties().iterator(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isNull()) {
                    continue;
                }
                input.put(e.getKey(), Json.MAPPER.convertValue(e.getValue(), Object.class));
                if (!"reason".equals(e.getKey())) {
                    changes.set(e.getKey(), e.getValue());
                }
            }
            if (entry.input() != null && entry.input().path("properties").has("reason") && !input.containsKey("reason")) {
                input.put("reason", reason);
            }
            String key = idempotencyKey != null && !idempotencyKey.isBlank() ? "scorecard:" + watch.accountId() + ":" + idempotencyKey.trim()
                    : "scorecard:" + watch.wfrn() + ":" + rule.key().text() + ":" + evaluation.version();
            RequestOptions options = RequestOptions.console(assertion, requestId, key).withResourceWfrn(watch.wfrn())
                    .withLabel(RULE_LABEL, rule.pack() + "." + rule.id());
            if (entry.version() != null) {
                options = options.withWorkflowVersion(entry.version());
            }
            RequestOptions sent = options;
            // an update goes through the state service first, as every console update does, so the resource shows it as updating
            Mono<Void> before = "update".equals(entry.verb()) && !changes.isEmpty()
                    ? state.requestUpdate(watch.wfrn(), entry.workflow(), entry.version(), changes, actor, requestId).then()
                    : Mono.empty();
            return before.then(starter.start(watch.accountId(), entry, pathParams(entry, watch), input, sent))
                    .flatMap(job -> ("update".equals(entry.verb()) ? state.noteJob(watch.wfrn(), job.jobId()) : Mono.<Void>empty())
                            .thenReturn(new Views.Remediation(watch.wfrn(), rule.pack(), rule.id(), RemediationSpec.WORKFLOW, job.jobId(), entry.workflow(),
                                    null)))
                    .doOnSuccess(r -> log.info("{}: {} started as job {} by {} to fix {}", watch.wfrn(), entry.workflow(), r.jobId(), actor, rule.key()));
        });
    }

    /** The catalog entry of the fix: the type's verb, or the named workflow when its noun is not the type. */
    private Mono<CatalogEntry> entry(RemediationSpec spec, String type) {
        Mono<Optional<CatalogEntry>> byType = platform.listCatalog(type).map(entries -> entries.stream()
                .filter(e -> e.path() != null && (spec.workflow() == null || spec.workflow().equals(e.workflow()))
                        && (spec.verb() == null || spec.verb().equals(e.verb())))
                .sorted((a, b) -> Boolean.compare(a.subjectInput() == null, b.subjectInput() == null))
                .findFirst());
        Mono<Optional<CatalogEntry>> byName = spec.workflow() == null ? Mono.just(Optional.empty())
                : platform.listCatalog(null).map(entries -> entries.stream().filter(e -> e.path() != null && spec.workflow().equals(e.workflow())).findFirst());
        return byType.flatMap(found -> found.isPresent() ? Mono.just(found) : byName)
                .flatMap(found -> found.map(Mono::just).orElseGet(() -> Mono.error(new ConflictException(
                        "remediation.unavailable: no " + (spec.workflow() != null ? spec.workflow() : spec.verb()) + " workflow for " + type + " in the catalog",
                        Map.of("type", type)))));
    }

    private Mono<String> assertion(Watch watch, String relayed, OnBehalfOfPort.Person person) {
        if (relayed != null && !relayed.isBlank()) {
            return Mono.just(relayed.trim());
        }
        Mono<String> signed = person == null ? Mono.empty() : onBehalfOf.assertion(watch.accountId(), watch.region(), person);
        return signed.switchIfEmpty(Mono.error(() -> new ConflictException(
                "remediation.needsPerson: a fix runs on behalf of the person who asks for it; the call needs the BFF's X-On-Behalf-Of assertion",
                Map.of("header", "X-On-Behalf-Of"))));
    }

    /** The fix's inputs: what the person gave, else the default; each of its type and list. A missing or wrong one is a 400. */
    static ObjectNode inputs(RemediationSpec spec, ObjectNode given, ObjectNode resource) {
        ObjectNode out = Json.object();
        List<Problem> problems = new ArrayList<>();
        for (RemediationInput in : spec.inputs()) {
            JsonNode v = given == null ? null : given.get(in.name());
            if ((v == null || v.isNull()) && in.defaultValue() != null) {
                v = Templates.render(in.defaultValue(), Json.object(), resource);
            }
            if (v == null || v.isNull() || v.isTextual() && v.asText().isBlank()) {
                if (in.required()) {
                    problems.add(Problem.error("remediation.input", "/input/" + in.name(), in.title() + " is required"));
                }
                continue;
            }
            JsonNode typed = typed(in.type(), v);
            if (typed == null) {
                problems.add(Problem.error("remediation.input", "/input/" + in.name(), in.title() + " is a " + in.type()));
                continue;
            }
            if (in.enumeration() != null && in.enumeration().stream().noneMatch(e -> e.equals(typed) || e.isValueNode() && e.asText().equals(typed.asText()))) {
                problems.add(Problem.error("remediation.input", "/input/" + in.name(), in.title() + " is one of " + in.enumeration()));
                continue;
            }
            out.set(in.name(), typed);
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("remediation.input: " + problems.get(0).message(), problems);
        }
        return out;
    }

    private static JsonNode typed(String type, JsonNode v) {
        switch (type) {
            case "integer" -> {
                if (v.isIntegralNumber()) {
                    return v;
                }
                if (v.isTextual() && v.asText().trim().matches("-?\\d{1,9}")) {
                    return IntNode.valueOf(Integer.parseInt(v.asText().trim()));
                }
                return null;
            }
            case "number" -> {
                if (v.isNumber()) {
                    return v;
                }
                try {
                    return v.isTextual() ? Json.MAPPER.getNodeFactory().numberNode(new java.math.BigDecimal(v.asText().trim())) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            case "boolean" -> {
                if (v.isBoolean()) {
                    return v;
                }
                String t = v.asText();
                return "true".equals(t) || "false".equals(t) ? Json.MAPPER.getNodeFactory().booleanNode(Boolean.parseBoolean(t)) : null;
            }
            default -> {
                return v.isValueNode() ? TextNode.valueOf(v.asText()) : null;
            }
        }
    }

    private static String subjectOf(Watch watch) {
        return watch.registryId() != null ? watch.registryId() : watch.providerId();
    }

    /** The path's placeholders: the subject input is the platform id of the resource, anything else comes from the desired attributes. */
    private static Map<String, String> pathParams(CatalogEntry entry, Watch watch) {
        Map<String, String> params = new LinkedHashMap<>();
        Matcher m = PLACEHOLDER.matcher(entry.path());
        while (m.find()) {
            String name = m.group(1);
            if (!name.equals(entry.subjectInput()) && watch.desired().hasNonNull(name)) {
                params.put(name, watch.desired().get(name).asText());
            } else {
                params.put(name, subjectOf(watch));
            }
        }
        return params;
    }
}
