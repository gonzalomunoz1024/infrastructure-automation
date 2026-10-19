package com.iaf.platform.workflows.application;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.domain.Suggest;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderType;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.CheckContext;
import com.iaf.platform.workflows.domain.DependencyRules;
import com.iaf.platform.workflows.domain.PublishChecks;
import com.iaf.platform.workflows.domain.Semver;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.StepTypeSchemas;
import com.iaf.platform.workflows.domain.WorkflowChecks;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowMermaid;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowSchema;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowTemplates;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Read side: the store's records as the API's read models, the checks' catalog, and the
 * registry the designer and the natural-language builder browse. Store calls run on the
 * bounded-elastic scheduler because the Git store blocks.
 */
@Service
@Primary
@RequiredArgsConstructor
public class WorkflowQueryHandler implements WorkflowQueries {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Comparator<WorkflowRecord> BY_VERSION = (a, b) -> Semver.ORDER.compare(a.version(), b.version());

    private final WorkflowStorePort store;
    private final ChangeRequestPort changeRequests;
    private final ProviderQueries providers;
    private final ResourceQueries resources;
    private final CredentialsPort credentials;

    // ------------------------------------------------------------------ the shared port

    @Override
    public Mono<WorkflowRecord> published(String name) {
        return optional(() -> store.published(name));
    }

    @Override
    public Mono<WorkflowRecord> version(String name, String version) {
        return optional(() -> store.get(name, version));
    }

    @Override
    public Mono<WorkflowRecord> resolve(String name, String range) {
        return optional(() -> {
            List<WorkflowRecord> runnable = store.versions(name).stream().filter(WorkflowRecord::isRunnable).toList();
            return Semver.highest(runnable.stream().map(WorkflowRecord::version).toList(), range)
                    .flatMap(v -> runnable.stream().filter(r -> r.version().equals(v)).findFirst());
        });
    }

    @Override
    public Flux<WorkflowRecord> publishedAll() {
        return blocking(() -> store.all().stream().filter(WorkflowRecord::isPublished).toList()).flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<CheckContext> checkContext() {
        Mono<Map<String, ProviderDefinition>> providerMap = providers.all().collectMap(ProviderDefinition::name, p -> p);
        // every type with its attribute schema (null when the file has none), for the fields use and register steps return
        Mono<Map<String, JsonNode>> types = resources.types().<Map<String, JsonNode>>collect(HashMap::new, (m, t) -> m.put(t.name(), t.schema()));
        // every version a run step can start, so a step pinned to an older major is judged against that major
        Mono<List<WorkflowRecord>> runnable = blocking(() -> store.all().stream().filter(WorkflowRecord::isRunnable).toList());
        return Mono.zip(providerMap, types, runnable, credentialOwners()).map(t -> CheckContext.of(t.getT1(),
                t.getT3().stream().filter(WorkflowRecord::isPublished).map(WorkflowRecord::definition).toList(),
                t.getT3().stream().map(WorkflowRecord::definition).toList(), t.getT2(), t.getT4().orElse(null)));
    }

    /** The owner team of every owned credential in the vault, by name; empty when the vault cannot list them (owners are then not judged). */
    private Mono<Optional<Map<String, String>>> credentialOwners() {
        return credentials.list()
                .map(all -> {
                    Map<String, String> owners = new HashMap<>();
                    all.stream().filter(c -> c.owner() != null && !c.owner().isBlank()).forEach(c -> owners.put(c.name(), c.owner()));
                    return Optional.<Map<String, String>>of(owners);
                })
                .defaultIfEmpty(Optional.of(Map.of()))
                .onErrorResume(e -> Mono.just(Optional.empty()));
    }

    @Override
    public Mono<List<Problem>> check(String yaml) {
        return checkContext().map(ctx -> WorkflowChecks.check(yaml, ctx));
    }

    // ------------------------------------------------------------------ read models

    @Override
    public Flux<WorkflowSummary> list(String folder, String status, String q) {
        return blocking(() -> {
            Map<String, List<WorkflowRecord>> byName = groupByName(store.all());
            return byName.values().stream()
                    .map(WorkflowQueryHandler::summaryOf)
                    .filter(s -> folder == null || folder.isBlank() || folder.equalsIgnoreCase(s.folder()))
                    .filter(s -> status == null || status.isBlank() || status.equalsIgnoreCase(s.status()))
                    .filter(s -> q == null || q.isBlank() || matches(s, q))
                    .sorted(Comparator.comparing(WorkflowSummary::name))
                    .toList();
        }).flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<WorkflowDetail> detail(String name) {
        return blocking(() -> store.versions(name))
                .filter(v -> !v.isEmpty())
                .switchIfEmpty(Mono.error(() -> new NotFoundException("workflow", name)))
                .flatMap(versions -> checkContext().map(ctx -> {
                    WorkflowSummary s = summaryOf(versions);
                    WorkflowRecord current = currentOf(versions);
                    List<Problem> problems = WorkflowChecks.check(current.definition(), ctx);
                    List<ChangeRequest> crs = changeRequests.all().stream()
                            .filter(cr -> cr.kind() == ChangeRequest.Kind.WORKFLOW && name.equals(cr.name()))
                            .map(cr -> withoutContent(judged(cr, changeRequests, providers)))
                            .toList();
                    return new WorkflowDetail(s.name(), s.title(), s.noun(), s.verb(), s.persona(), s.folder(), s.owner(), s.tags(), s.status(),
                            s.latestVersion(), s.publishedVersion(), s.stepCount(), s.updatedAt(), s.mcp(),
                            current.yaml(), WorkflowYaml.toJson(current.definition()), versions.stream().map(WorkflowQueryHandler::versionOf).toList(),
                            problems, crs);
                }));
    }

    @Override
    public Flux<WorkflowVersion> versions(String name) {
        return blocking(() -> store.versions(name))
                .filter(v -> !v.isEmpty())
                .switchIfEmpty(Mono.error(() -> new NotFoundException("workflow", name)))
                .flatMapMany(v -> Flux.fromIterable(v.stream().map(WorkflowQueryHandler::versionOf).toList()));
    }

    @Override
    public Mono<VersionDetail> versionDetail(String name, String version) {
        return optional(() -> store.get(name, version))
                .switchIfEmpty(Mono.error(() -> new NotFoundException("workflow version", name + "@" + version)))
                .map(r -> new VersionDetail(r.version(), r.status(), r.yaml(), WorkflowYaml.toJson(r.definition()), r.contentHash()));
    }

    @Override
    public Flux<ChangeRequest> changeRequests(String status) {
        return blocking(() -> changeRequests.all().stream()
                .filter(cr -> status == null || status.isBlank() || cr.status().json().equalsIgnoreCase(status))
                .map(cr -> withoutContent(judged(cr, changeRequests, providers)))
                .toList()).flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<ChangeRequestDetail> changeRequest(String id) {
        return optional(() -> changeRequests.get(id).map(cr -> judged(cr, changeRequests, providers)))
                .switchIfEmpty(Mono.error(() -> new NotFoundException("change request", id)))
                .map(cr -> new ChangeRequestDetail(cr, WorkflowMermaid.preview(cr).map(Preview::new).orElse(null)));
    }

    /**
     * The change request with its requires check judged now: the change requests it requires may
     * have been merged or closed since it was opened, and the versions they publish may have
     * become current (two store reads per change request it requires).
     */
    static ChangeRequest judged(ChangeRequest cr, ChangeRequestPort store, ProviderQueries providers) {
        return cr.required().isEmpty() ? cr : cr.withCheck(PublishChecks.requires(cr.required(), store::get, providers::currentVersion));
    }

    @Override
    public Flux<Template> templates() {
        return Flux.fromIterable(WorkflowTemplates.all()).map(t -> new Template(t.id(), t.title(), t.description(), t.yaml()));
    }

    @Override
    public Mono<JsonNode> workflowSchema() {
        return Mono.fromSupplier(WorkflowSchema::document);
    }

    // ------------------------------------------------------------------ registry

    @Override
    public Flux<StepTypeInfo> stepTypes() {
        return Flux.fromArray(StepType.values())
                .map(t -> new StepTypeInfo(t.key(), StepTypeSchemas.title(t), t.category(), StepTypeSchemas.schema(t), StepTypeSchemas.description(t)));
    }

    @Override
    public Flux<RegistryProvider> registryProviders() {
        return providers.all().map(p -> new RegistryProvider(p.name(), p.title(), p.type().key(), p.owner(),
                p.actions().stream().map(WorkflowQueryHandler::actionOf).toList()));
    }

    @Override
    public Flux<RegistryWorkflow> registryWorkflows(Boolean published) {
        boolean onlyPublished = published == null || published;
        return blocking(() -> {
            List<WorkflowRecord> records;
            if (onlyPublished) {
                records = store.all().stream().filter(WorkflowRecord::isPublished).toList();
            } else {
                records = groupByName(store.all()).values().stream().map(WorkflowQueryHandler::currentOf).toList();
            }
            return records.stream()
                    .sorted(Comparator.comparing(WorkflowRecord::name))
                    .map(r -> new RegistryWorkflow(r.name(), r.definition().title(), r.version(),
                            r.definition().inputSchema() == null ? emptyObjectSchema() : r.definition().inputSchema(), outputSchema(r.definition())))
                    .toList();
        }).flatMapMany(Flux::fromIterable);
    }

    @Override
    public Flux<WorkflowSummary> searchWorkflows(String q) {
        return blocking(() -> {
            List<WorkflowSummary> all = groupByName(store.all()).values().stream().map(WorkflowQueryHandler::summaryOf).toList();
            if (q == null || q.isBlank()) {
                return all.stream().sorted(Comparator.comparing(WorkflowSummary::name)).limit(5).toList();
            }
            Map<WorkflowSummary, Integer> scores = new HashMap<>();
            for (WorkflowSummary s : all) {
                int score = score(s, q);
                if (score > 0) {
                    scores.put(s, score);
                }
            }
            return scores.entrySet().stream()
                    .sorted(Map.Entry.<WorkflowSummary, Integer>comparingByValue().reversed().thenComparing(e -> e.getKey().name()))
                    .limit(5)
                    .map(Map.Entry::getKey)
                    .toList();
        }).flatMapMany(Flux::fromIterable);
    }

    @Override
    public Flux<ResourceTypeDefinition> resourceTypes() {
        return resources.types();
    }

    @Override
    public Flux<Playbook> playbooks() {
        return providers.all().filter(p -> p.type() == ProviderType.ANSIBLE).flatMapIterable(p -> {
            JsonNode git = p.spec().source() == null ? null : p.spec().source().get("git");
            String version = p.metadata().version() != null ? p.metadata().version() : (git == null ? null : git.path("ref").asText(null));
            return p.actions().stream().map(a -> new Playbook(p.name() + "/" + a.id(), version, a.playbook(), p.owner())).toList();
        });
    }

    @Override
    public Flux<CredentialName> credentials() {
        return credentials.list().flatMapMany(Flux::fromIterable).map(c -> new CredentialName(c.name(), c.type()));
    }

    @Override
    public Mono<Dependencies> dependencies() {
        Mono<List<ResourceTypeDefinition>> types = resources.types().collectList();
        Mono<List<WorkflowRecord>> published = blocking(() -> store.all().stream().filter(WorkflowRecord::isPublished).toList());
        return Mono.zip(types, published).map(t -> dependenciesOf(t.getT1(), t.getT2()));
    }

    /** The dependency graph of the published workflows ({@link DependencyRules}) as the API's read model. */
    static Dependencies dependenciesOf(List<ResourceTypeDefinition> types, List<WorkflowRecord> published) {
        DependencyRules.Graph g = DependencyRules.of(published.stream().map(WorkflowRecord::definition).toList(), producedBy(types));
        List<TypeDependencies> byType = g.types().stream()
                .map(type -> new TypeDependencies(type,
                        g.produces().stream().filter(l -> l.type().equals(type)).map(l -> new DependencyLink(l.workflow(), l.via().json(), l.at(), null)).toList(),
                        g.consumersOfType(type).stream().map(l -> new DependencyLink(l.workflow(), l.via().json(), l.at(), null)).toList()))
                .toList();
        List<WorkflowDependencies> byWorkflow = published.stream()
                .sorted(Comparator.comparing(WorkflowRecord::name))
                .map(r -> new WorkflowDependencies(r.name(), r.version(), List.copyOf(g.typesProducedBy(r.name())), List.copyOf(g.typesConsumedBy(r.name())),
                        g.runsOf(r.name()).stream().map(l -> new DependencyLink(l.producer(), l.via().json(), l.at(), l.range())).toList(),
                        g.consumersOf(r.name()).stream().map(l -> new DependencyLink(l.consumer(), l.via().json(), l.at(), l.range())).toList()))
                .toList();
        return new Dependencies(byType, byWorkflow, g.cycles());
    }

    /** Every type with the workflow its file names in {@code producedBy} (may be null). */
    static Map<String, String> producedBy(List<ResourceTypeDefinition> types) {
        Map<String, String> out = new HashMap<>();
        types.forEach(t -> out.put(t.name(), t.producedBy()));
        return out;
    }

    // ------------------------------------------------------------------ mapping

    /** The record the designer opens: the draft, else the candidate, else the published version, else the highest. */
    static WorkflowRecord currentOf(List<WorkflowRecord> versions) {
        for (WorkflowStatus s : List.of(WorkflowStatus.DRAFT, WorkflowStatus.CANDIDATE, WorkflowStatus.PUBLISHED)) {
            Optional<WorkflowRecord> r = versions.stream().filter(v -> v.status() == s).max(BY_VERSION);
            if (r.isPresent()) {
                return r.get();
            }
        }
        return versions.stream().max(BY_VERSION).orElseThrow();
    }

    static WorkflowSummary summaryOf(List<WorkflowRecord> versions) {
        WorkflowRecord current = currentOf(versions);
        WorkflowDefinition d = current.definition();
        Optional<WorkflowRecord> published = versions.stream().filter(WorkflowRecord::isPublished).max(BY_VERSION);
        String latest = versions.stream().map(WorkflowRecord::version).max(Semver.ORDER).orElse(current.version());
        var m = d.metadata();
        return new WorkflowSummary(d.name(), d.title(), m.noun(), m.verb(), m.persona(), m.folder(), m.owner(),
                m.tags() == null ? List.of() : m.tags(), published.isPresent() ? "published" : "draft", latest,
                published.map(WorkflowRecord::version).orElse(null), d.allSteps().size(), current.updatedAt(), d.mcpExposed());
    }

    static WorkflowVersion versionOf(WorkflowRecord r) {
        return new WorkflowVersion(r.version(), r.status(), r.contentHash(), r.publishedAt(), r.publishedBy(), r.releaseId(), r.notes(),
                r.rolledBack() ? Boolean.TRUE : null);
    }

    /** A list row: the change request without its yaml and diff. */
    static ChangeRequest withoutContent(ChangeRequest cr) {
        return new ChangeRequest(cr.id(), cr.kind(), cr.name(), cr.version(), cr.branch(), cr.title(), cr.author(), cr.status(), cr.checks(),
                cr.strategy(), null, null, cr.createdAt(), cr.mergedAt(), cr.mergedBy(), cr.url(), cr.requires());
    }

    static RegistryAction actionOf(Action a) {
        JsonNode input = a.input() != null ? a.input() : a.vars();
        JsonNode output = a.output();
        if (output == null) {
            JsonNode example = a.sample() != null ? a.sample() : a.example();
            output = example == null ? null : Schemas.infer(example);
        }
        return new RegistryAction(a.id(), a.title() == null ? a.id() : a.title(), a.effect() == null ? "unknown" : a.effect().json(),
                a.retrySafe() ? "safe" : "unsafe", a.approvalRequired() ? "required" : "none", input, output, a.sample());
    }

    /** {@code workflow.outputs} keys as an object schema. */
    static ObjectNode outputSchema(WorkflowDefinition d) {
        ObjectNode s = emptyObjectSchema();
        ObjectNode props = (ObjectNode) s.get("properties");
        if (d.workflow() != null && d.workflow().outputs() != null) {
            d.workflow().outputs().keySet().forEach(k -> props.set(k, F.objectNode()));
        }
        return s;
    }

    static ObjectNode emptyObjectSchema() {
        ObjectNode s = F.objectNode();
        s.put("type", "object");
        s.putObject("properties");
        return s;
    }

    static Map<String, List<WorkflowRecord>> groupByName(List<WorkflowRecord> all) {
        return all.stream().collect(Collectors.groupingBy(WorkflowRecord::name, java.util.LinkedHashMap::new, Collectors.toList()));
    }

    static boolean matches(WorkflowSummary s, String q) {
        String needle = q.toLowerCase(Locale.ROOT);
        return contains(s.name(), needle) || contains(s.title(), needle) || contains(s.noun(), needle) || contains(s.verb(), needle)
                || contains(s.folder(), needle) || s.tags().stream().anyMatch(t -> contains(t, needle));
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Fuzzy score of a summary against a query: exact tokens count most, substrings next, near misses last. */
    static int score(WorkflowSummary s, String q) {
        Set<String> fields = new HashSet<>();
        add(fields, s.name());
        add(fields, s.title());
        add(fields, s.noun());
        add(fields, s.verb());
        s.tags().forEach(t -> add(fields, t));
        int score = 0;
        for (String token : q.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (fields.contains(token)) {
                score += 3;
            } else if (fields.stream().anyMatch(f -> f.contains(token))) {
                score += 2;
            } else if (Suggest.closest(token, fields).isPresent()) {
                score += 1;
            }
        }
        return score;
    }

    private static void add(Set<String> fields, String value) {
        if (value == null) {
            return;
        }
        for (String part : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!part.isBlank()) {
                fields.add(part);
            }
        }
    }

    // ------------------------------------------------------------------ scheduling

    static <T> Mono<T> blocking(Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic());
    }

    static <T> Mono<T> optional(Callable<Optional<T>> call) {
        return blocking(call).flatMap(Mono::justOrEmpty);
    }
}
