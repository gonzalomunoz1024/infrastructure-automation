package com.iaf.platform.runs.application;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobContext;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.domain.Preview.AffectedResource;
import com.iaf.platform.runs.domain.Preview.Cost;
import com.iaf.platform.runs.domain.Preview.PreviewStep;
import com.iaf.platform.runs.domain.PreviewRules;
import com.iaf.platform.runs.domain.PreviewRules.PlanAnswer;
import com.iaf.platform.runs.domain.PreviewRules.PlanRequest;
import com.iaf.platform.runs.domain.PreviewRules.ResourceRef;
import com.iaf.platform.runs.domain.PreviewRules.Walk;
import com.iaf.platform.runs.domain.PriceList;
import com.iaf.platform.runs.domain.WorkflowInterpreter;
import com.iaf.platform.runs.ports.outbound.PriceListPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCall;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.Semver;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Builds the preview of a run. The static walk of the flow against the provider files is judged
 * by a dry run on the first row's input and the recorded samples: the steps the dry run does not
 * take are skipped. A provider action's {@code plan} is asked with the step's input (for real when
 * the plan only reads, else on its recorded sample) and refines what the step would do. The
 * touched resources are the {@code x-resource} inputs of every row and the ids the {@code use}
 * steps read, and, for what a change or a delete acts on, everything the registry's lineage says
 * was built from it. The monthly cost comes from the plans, the price lists and the recorded
 * samples, times the rows of a batch. When the dry run cannot finish, the static walk stands.
 */
@Slf4j
final class Previews {

    /** How long the dry run behind a preview may take before the preview keeps the static walk. */
    static final Duration DRY_RUN_TIMEOUT = Duration.ofSeconds(20);
    /** How long one plan call may take. */
    static final Duration PLAN_TIMEOUT = Duration.ofSeconds(10);
    private static final int PLANS_AT_ONCE = 4;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final ProviderQueries providers;
    private final WorkflowQueries workflows;
    private final ResourceQueries resources;
    private final StepExecutors executors;
    private final PriceListPort prices;
    private final Clock clock;

    Previews(ProviderQueries providers, WorkflowQueries workflows, ResourceQueries resources, StepExecutors executors, PriceListPort prices, Clock clock) {
        this.providers = providers;
        this.workflows = workflows;
        this.resources = resources;
        this.executors = executors;
        this.prices = prices;
        this.clock = clock;
    }

    Mono<Preview> build(WorkflowDefinition def, List<JsonNode> inputs, PolicyDecision decision, Identity who) {
        Mono<List<ProviderDefinition>> provs = providers.all().collectList().defaultIfEmpty(List.of());
        Mono<List<WorkflowDefinition>> pubs = workflows.publishedAll().map(WorkflowRecord::definition).collectList().defaultIfEmpty(List.of());
        Mono<List<PriceList>> lists = prices == null ? Mono.just(List.of())
                : prices.priceLists().collectList().onErrorResume(e -> Mono.just(List.of())).defaultIfEmpty(List.of());
        JsonNode first = inputs.isEmpty() ? F.objectNode() : inputs.get(0);
        return Mono.zip(provs, pubs, lists).flatMap(t -> {
            PreviewRules.Catalog catalog = PreviewRules.catalog(t.getT1(), (name, range) -> highest(t.getT2(), name, range));
            List<PreviewStep> statics = PreviewRules.steps(def, catalog);
            return dryRun(def, first, who).flatMap(walk -> plans(walk, catalog, JobFactory.environmentOf(first)).flatMap(plans -> {
                List<PreviewStep> steps = PreviewRules.refine(statics, walk, plans, catalog);
                Cost cost = PreviewRules.total(PreviewRules.costParts(def, steps, walk, plans, t.getT3(), catalog), inputs.size());
                Map<String, ResourceRef> refs = new LinkedHashMap<>();
                for (JsonNode in : inputs) {
                    for (ResourceRef r : PreviewRules.resourceRefs(def, in)) {
                        refs.putIfAbsent(r.id(), r);
                    }
                }
                PreviewRules.usedRefs(walk).forEach(r -> refs.putIfAbsent(r.id(), r));
                return affected(refs.values(), steps, walk).map(affected -> new Preview(def.name(), def.version(), steps, affected, cost, decision));
            }));
        });
    }

    /** The dry run behind the preview, on the recorded samples; {@link Walk#NONE} when it cannot run, so the static walk stands. */
    Mono<Walk> dryRun(WorkflowDefinition def, JsonNode input, Identity who) {
        return Mono.fromCallable(() -> {
            Job job = JobFactory.newJob(def, def.version(), input, who, "preview", null, null, null, null, null, clock.instant())
                    .withId(Ids.next("dry"));
            List<PreviewRules.Recorded> recorded = Collections.synchronizedList(new ArrayList<>());
            PortStepExecutor executor = executors.dryRun(job, (prefix, e) -> recorded.add(new PreviewRules.Recorded(prefix + e.id(), e)));
            Outcome outcome = new WorkflowInterpreter(def, executor, JobContext.of(job)).run();
            synchronized (recorded) {
                return PreviewRules.walkOf(outcome.succeeded(), new ArrayList<>(recorded));
            }
        }).subscribeOn(Schedulers.boundedElastic())
                .timeout(DRY_RUN_TIMEOUT)
                .onErrorResume(e -> {
                    log.debug("the dry run behind the preview of {} did not finish: {}", def.name(), e.toString());
                    return Mono.just(Walk.NONE);
                });
    }

    private Mono<Map<String, PlanAnswer>> plans(Walk walk, PreviewRules.Catalog catalog, String environment) {
        return Flux.fromIterable(PreviewRules.planRequests(walk, catalog))
                .flatMap(r -> plan(r, environment).map(a -> Map.entry(r.key(), a)), PLANS_AT_ONCE)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /** One plan: its action called with the step's input, for real when it only reads, else on its recorded sample. */
    private Mono<PlanAnswer> plan(PlanRequest r, String environment) {
        String id = r.plan().id();
        boolean onlyReads = r.plan().effect() == Effect.READ;
        return Mono.defer(() -> executors.ports().calls().call(new ProviderCall(null, 0, r.provider(), r.plan(), environment, r.input(), !onlyReads)))
                .timeout(PLAN_TIMEOUT)
                .map(answer -> PlanAnswer.of(id, answer))
                .defaultIfEmpty(PlanAnswer.of(id, F.objectNode()))
                .onErrorResume(e -> {
                    ProviderCallException p = e instanceof ProviderCallException x ? x : e.getCause() instanceof ProviderCallException y ? y : null;
                    if (p != null) {
                        return Mono.just(PlanAnswer.failed(id, p.errorClass(), p.getMessage()));
                    }
                    String why = e instanceof TimeoutException ? "no answer within " + PLAN_TIMEOUT.toSeconds() + "s"
                            : e.getMessage() == null ? e.toString() : e.getMessage();
                    return Mono.just(PlanAnswer.failed(id, e instanceof TimeoutException ? "timeout" : "error", why));
                });
    }

    /** The touched resources as the registry names them, then what was built from the ones a change or a delete acts on. */
    private Mono<List<AffectedResource>> affected(Collection<ResourceRef> refs, List<PreviewStep> steps, Walk walk) {
        return Flux.fromIterable(refs)
                .concatMap(ref -> Mono.defer(() -> resources.get(ref.id()))
                        .map(r -> new AffectedResource(r.id(), r.type() == null ? ref.type() : r.type(), r.name() == null ? r.id() : r.name(), ref.via()))
                        .onErrorResume(e -> Mono.empty())
                        .defaultIfEmpty(new AffectedResource(ref.id(), ref.type(), ref.id(), ref.via())))
                .collectList()
                .flatMap(direct -> {
                    Set<String> ids = new LinkedHashSet<>();
                    direct.forEach(a -> ids.add(a.id()));
                    List<AffectedResource> all = new ArrayList<>(direct);
                    return dependents(new ArrayList<>(PreviewRules.changed(steps, walk, ids)), new HashSet<>(ids), all, 1).thenReturn(all);
                });
    }

    /** One generation of the registry's lineage below {@code parents}, down to {@link PreviewRules#MAX_DEPTH} generations. */
    private Mono<Void> dependents(List<String> parents, Set<String> seen, List<AffectedResource> out, int generation) {
        if (parents.isEmpty() || generation > PreviewRules.MAX_DEPTH) {
            return Mono.empty();
        }
        return Flux.fromIterable(parents)
                .concatMap(id -> Mono.defer(() -> resources.lineage(id))
                        .onErrorResume(e -> Mono.empty())
                        .flatMapIterable(l -> l.dependents() == null ? List.of() : l.dependents())
                        .filter(d -> seen.add(d.id()))
                        .map(d -> new AffectedResource(d.id(), d.type(), d.name() == null ? d.id() : d.name(), "built from " + id)))
                .collectList()
                .flatMap(next -> {
                    out.addAll(next);
                    return dependents(next.stream().map(AffectedResource::id).toList(), seen, out, generation + 1);
                });
    }

    /** The highest published version of {@code name} in {@code range} (any version when none is given). */
    static Optional<WorkflowDefinition> highest(List<WorkflowDefinition> published, String name, String range) {
        String r = range == null || range.isBlank() ? "*" : range;
        return published.stream()
                .filter(d -> name.equals(d.name()))
                .filter(d -> {
                    try {
                        return Semver.parse(d.version()).satisfies(r);
                    } catch (RuntimeException e) {
                        return false;
                    }
                })
                .max(Comparator.comparing(d -> Semver.parse(d.version())));
    }
}
