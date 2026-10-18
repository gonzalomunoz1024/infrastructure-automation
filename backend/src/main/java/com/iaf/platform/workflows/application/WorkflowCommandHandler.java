package com.iaf.platform.workflows.application;

import static com.iaf.platform.workflows.application.WorkflowQueryHandler.blocking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyInput;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.domain.AccessRules;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.runs.ports.inbound.RunsQueries.JobOutcome;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.domain.CheckContext;
import com.iaf.platform.workflows.domain.DependencyRules;
import com.iaf.platform.workflows.domain.PlaybookPins;
import com.iaf.platform.workflows.domain.PublishChecks;
import com.iaf.platform.workflows.domain.ReplayResult;
import com.iaf.platform.workflows.domain.ReplayResult.Divergence;
import com.iaf.platform.workflows.domain.Semver;
import com.iaf.platform.workflows.domain.UnifiedDiff;
import com.iaf.platform.workflows.domain.VersionRules;
import com.iaf.platform.workflows.domain.WorkflowChecks;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowImports;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.inbound.WorkflowCommands;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Write side: drafts, publishing through change requests, merges, imports and the YAML round
 * trip. Every change goes through the store port and ends in a domain event; store calls run
 * on the bounded-elastic scheduler because the Git store blocks.
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class WorkflowCommandHandler implements WorkflowCommands {

    static final String DRAFT_SAVED = "workflow.draft-saved";
    static final String DELETED = "workflow.deleted";
    static final String PUBLISHED = "workflow.published";
    static final String CR_OPENED = "change-request.opened";
    static final String CR_MERGED = "change-request.merged";
    static final String CR_CLOSED = "change-request.closed";
    static final String RELEASE_ROLLED_BACK = "release.rolled-back";
    static final String RELEASE_PROMOTED = "release.promoted";

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Set<String> STRATEGIES = Set.of("canary", "all");
    private static final Duration REPLAY_WINDOW = Duration.ofDays(7);

    private final WorkflowStorePort store;
    private final ChangeRequestPort changeRequests;
    private final WorkflowQueryHandler queries;
    private final ProviderQueries providers;
    private final ResourceQueries resources;
    private final RunsCommands runs;
    private final RunsQueries runsQueries;
    private final PolicyPort policy;
    private final EventBus events;
    private final Clock clock;
    /** Pins git playbooks to a commit at publish; set by Spring, a no-op in handlers built by hand. */
    private PlaybookPinning pinning;

    @Autowired(required = false)
    void setPlaybookPinning(PlaybookPinning pinning) {
        this.pinning = pinning;
    }

    /** Holds uploaded playbooks; a publish checks each upload the file names is still there. Set by Spring; without it the check is skipped. */
    private ArtifactStorePort artifacts;

    @Autowired(required = false)
    void setArtifacts(ArtifactStorePort artifacts) {
        this.artifacts = artifacts;
    }

    @PostConstruct
    public void init() {
        events.register(this::on);
    }

    // ------------------------------------------------------------------ drafts

    @Override
    public Mono<CommandAccepted> createDraft(WorkflowSource source) {
        return CurrentIdentity.current().flatMap(who -> blocking(() -> {
            WorkflowDefinition parsed = source.parse();
            String name = requireNames(parsed);
            if (!store.versions(name).isEmpty()) {
                throw new ConflictException("workflow " + name + " already exists; save its draft with PUT /api/v1/workflows/" + name + "/draft");
            }
            AccessRules.requireEdit(who, parsed.metadata().owner(), "workflow " + name);
            WorkflowDefinition def = parsed.withVersion(VersionRules.firstVersion(parsed.metadata().version()));
            WorkflowRecord rec = WorkflowRecord.of(def, WorkflowStatus.DRAFT, pathFor(def), clock.instant(), who.id());
            store.save(rec);
            events.publish(DRAFT_SAVED, "workflow", name, who.id(), data().put("name", name).put("version", rec.version()).put("by", who.id()));
            return CommandAccepted.of(name, "draft", Map.of("workflow", "/api/v1/workflows/" + name, "draft", "/api/v1/workflows/" + name + "/draft"));
        }));
    }

    @Override
    public Mono<DraftSaved> saveDraft(String name, WorkflowSource source) {
        return CurrentIdentity.current().flatMap(who -> blocking(() -> {
            ContentFiles.requireSlug("The workflow name", name);
            WorkflowDefinition parsed = source.parse();
            if (!name.equals(parsed.name())) {
                throw new IllegalArgumentException("metadata.name is '" + parsed.name() + "' but the draft is saved as /workflows/" + name);
            }
            requireNames(parsed);
            List<WorkflowRecord> existing = store.versions(name);
            String owner = existing.isEmpty() ? parsed.metadata().owner() : ownerOf(existing);
            AccessRules.requireEdit(who, owner, "workflow " + name);
            String version = VersionRules.draftVersion(existing, parsed.metadata().version());
            WorkflowDefinition def = parsed.withVersion(version);
            String path = existing.isEmpty() ? pathFor(def) : existing.get(0).path();
            store.draft(name).filter(d -> !d.version().equals(version)).ifPresent(d -> store.delete(name, d.version()));
            WorkflowRecord rec = WorkflowRecord.of(def, WorkflowStatus.DRAFT, path, clock.instant(), who.id());
            store.save(rec);
            events.publish(DRAFT_SAVED, "workflow", name, who.id(), data().put("name", name).put("version", version).put("by", who.id()));
            return rec;
        }).flatMap(rec -> queries.checkContext()
                .map(ctx -> new DraftSaved(name, rec.version(), WorkflowChecks.check(rec.definition(), ctx)))));
    }

    @Override
    public Mono<Void> deleteDraft(String name) {
        return CurrentIdentity.current().flatMap(who -> blocking(() -> {
            List<WorkflowRecord> existing = store.versions(name);
            if (existing.isEmpty()) {
                throw new NotFoundException("workflow", name);
            }
            AccessRules.requireEdit(who, ownerOf(existing), "workflow " + name);
            List<WorkflowRecord> drafts = existing.stream().filter(r -> r.status() == WorkflowStatus.DRAFT).toList();
            if (drafts.isEmpty()) {
                throw new ConflictException("workflow " + name + " has no draft; published versions are never deleted");
            }
            for (WorkflowRecord d : drafts) {
                store.delete(name, d.version());
                events.publish(DELETED, "workflow", name, who.id(), data().put("name", name).put("version", d.version()).put("by", who.id()));
            }
            return true;
        })).then();
    }

    // ------------------------------------------------------------------ checks and the round trip

    @Override
    public Mono<Validation> validate(WorkflowSource source) {
        return Mono.zip(queries.checkContext(), resources.types().collectList()).flatMap(t -> blocking(() -> {
            List<Problem> problems = new ArrayList<>(problems(source, t.getT1()));
            if (problems.stream().noneMatch(p -> "yaml.invalid".equals(p.code()))) {
                problems.addAll(publishProblems(source.parse(), t.getT1(), t.getT2(), problems));
            }
            return new Validation(!WorkflowChecks.hasErrors(problems), problems);
        }));
    }

    /**
     * What the change request would fail on beyond the designer checks, judged from the files
     * alone: the version is the one a save would give the draft, and the catalog is what is
     * published now. An ansible-lint failure replaces the designer's unpinned warning.
     */
    private List<Problem> publishProblems(WorkflowDefinition parsed, CheckContext ctx, List<ResourceTypeDefinition> types, List<Problem> problems) {
        String name = parsed.name();
        if (name == null || name.isBlank()) {
            return List.of();
        }
        List<WorkflowRecord> versions = store.versions(name);
        WorkflowDefinition def = parsed.withVersion(VersionRules.draftVersion(versions, parsed.metadata().version()));
        List<WorkflowDefinition> published = publishedDefinitions();
        WorkflowDefinition previous = store.published(name).map(WorkflowRecord::definition).orElse(null);
        List<Problem> out = PublishChecks.problems(def, ctx, published, previous, DependencyRules.of(published, WorkflowQueryHandler.producedBy(types)));
        Set<String> unpinned = new HashSet<>();
        out.stream().filter(p -> "publish.ansibleLint".equals(p.code())).forEach(p -> unpinned.add(p.step()));
        problems.removeIf(p -> "ansible.unpinned".equals(p.code()) && unpinned.contains(p.step()));
        return out;
    }

    @Override
    public Mono<DryRunResult> dryRun(DryRunRequest request) {
        return CurrentIdentity.current().flatMap(who -> {
            WorkflowDefinition def = request.source().parse();
            JsonNode input = request.input() == null || request.input().isNull() ? F.objectNode() : request.input();
            return runs.dryRun(def, input, who);
        });
    }

    @Override
    public Mono<Rendered> render(WorkflowSource source) {
        return Mono.fromCallable(() -> {
            if (source.definition() == null || source.definition().isNull()) {
                throw new IllegalArgumentException("definition is required");
            }
            return new Rendered(WorkflowYaml.toYaml(WorkflowYaml.fromJson(source.definition())));
        });
    }

    @Override
    public Mono<Parsed> parse(WorkflowSource source) {
        if (!source.hasYaml()) {
            return Mono.error(new IllegalArgumentException("yaml is required"));
        }
        return queries.checkContext().map(ctx -> {
            WorkflowDefinition def;
            try {
                def = WorkflowYaml.parse(source.yaml());
            } catch (IllegalArgumentException e) {
                return new Parsed(null, List.of(Problem.error("yaml.invalid", null, null, e.getMessage())));
            }
            return new Parsed(WorkflowYaml.toJson(def), WorkflowChecks.check(def, ctx));
        });
    }

    // ------------------------------------------------------------------ publishing

    /**
     * What a publish or a replay check works on: the version, the previous published one
     * (nullable), the caller, and what pinning its git playbooks did.
     */
    private record Candidate(WorkflowRecord record, WorkflowRecord previous, Identity who, PlaybookPins.Outcome pins) {
        Candidate(WorkflowRecord record, WorkflowRecord previous, Identity who) {
            this(record, previous, who, PlaybookPins.Outcome.NONE);
        }

        String previousVersion() {
            return previous == null ? null : previous.version();
        }
    }

    @Override
    public Mono<CommandAccepted> publish(String name, PublishWorkflow command) {
        String requested = command == null ? null : command.strategy();
        String notes = command == null ? null : command.notes();
        if (requested != null && !STRATEGIES.contains(requested)) {
            return Mono.error(new IllegalArgumentException("strategy is canary or all"));
        }
        return CurrentIdentity.current().flatMap(who -> blocking(() -> {
            List<WorkflowRecord> existing = store.versions(name);
            if (existing.isEmpty()) {
                throw new NotFoundException("workflow", name);
            }
            AccessRules.requireEdit(who, ownerOf(existing), "workflow " + name);
            WorkflowRecord draft = store.draft(name).orElseThrow(() -> new ConflictException("workflow " + name + " has no draft to publish"));
            openChangeRequest(name).ifPresent(cr -> {
                throw new ConflictException("change request " + cr.id() + " is already open for workflow " + name, Map.of("changeRequest", cr.id()));
            });
            return new Candidate(withVersionRanges(draft), store.published(name).orElse(null), who);
        })).flatMap(this::pinned).flatMap(c -> Mono.zip(queries.checkContext(), missingUploads(c.record().definition())).flatMap(judged -> {
            CheckContext ctx = judged.getT1();
            WorkflowDefinition def = c.record().definition();
            List<Problem> problems = new ArrayList<>(WorkflowChecks.check(def, ctx));
            problems.addAll(judged.getT2());
            if (WorkflowChecks.hasErrors(problems)) {
                return Mono.error(new ConflictException("checks failed", problems));
            }
            String strategy = requested != null ? requested : def.rolloutOrDefault().strategy();
            Mono<Check> policyCheck = policy.evaluate(new PolicyInput(PolicyPort.Phase.PUBLISH, c.who(), name, def.version(), WorkflowYaml.toJson(def),
                            data().put("strategy", strategy).put("owner", def.metadata().owner()).put("previous", c.previousVersion())))
                    .map(PublishChecks::policy)
                    .onErrorResume(e -> Mono.just(PublishChecks.policyUnavailable(String.valueOf(e.getMessage()))));
            Mono<Check> replayCheck = c.previous() == null
                    ? Mono.just(PublishChecks.replay(ReplayResult.none(), null))
                    : replay(name, def, c.previousVersion(), c.who())
                            .map(r -> PublishChecks.replay(r, c.previousVersion()))
                            .onErrorResume(e -> Mono.just(PublishChecks.replayUnavailable(String.valueOf(e.getMessage()))));
            return Mono.zip(policyCheck, replayCheck, resources.types().collectList())
                    .flatMap(t -> blocking(() -> open(c, ctx, problems, strategy, notes, t.getT1(), t.getT2(), contracts(c, t.getT3()))));
        }));
    }

    /**
     * The draft as it will be published: a run step that names no version gets its producer's
     * current major ({@code ^2}), written into the file the way an author would pin it.
     */
    private WorkflowRecord withVersionRanges(WorkflowRecord draft) {
        WorkflowDefinition def = DependencyRules.pinRanges(draft.definition(), wf -> store.published(wf).map(WorkflowRecord::version));
        return def == draft.definition() ? draft : draft.withDefinition(def, draft.updatedAt(), draft.updatedBy());
    }

    /** The contracts check: this version against the published one, with the consumers it reaches. */
    private Check contracts(Candidate c, List<ResourceTypeDefinition> types) {
        DependencyRules.Graph graph = DependencyRules.of(publishedDefinitions(), WorkflowQueryHandler.producedBy(types));
        return PublishChecks.contracts(c.record().definition(), c.previous() == null ? null : c.previous().definition(), graph);
    }

    /**
     * One {@code playbook.missing} error per ansible step whose uploaded playbook the artifact
     * store does not hold any more: the designer checks already refused a digest that is not one,
     * and a digest from an expression is judged when the step runs.
     */
    private Mono<List<Problem>> missingUploads(WorkflowDefinition def) {
        List<PlaybookPins.Upload> uploads = PlaybookPins.uploads(def).stream().filter(PlaybookPins.Upload::validDigest).toList();
        if (artifacts == null || uploads.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(uploads)
                .filterWhen(u -> artifacts.exists(u.sha256()).map(exists -> !exists))
                .map(u -> Problem.error("playbook.missing", u.step(), "playbook.upload.sha256",
                        "The artifact store holds no upload with this digest; upload the file again (POST /api/v1/playbooks:upload) and pin the sha256 it answers"))
                .collectList();
    }

    /** The candidate with its git playbooks pinned to the commits their refs name now (written into the version). */
    private Mono<Candidate> pinned(Candidate c) {
        if (pinning == null) {
            return Mono.just(c);
        }
        return pinning.pin(c.record().definition()).map(p -> p.outcome().pinned().isEmpty()
                ? new Candidate(c.record(), c.previous(), c.who(), p.outcome())
                : new Candidate(c.record().withDefinition(p.definition(), clock.instant(), c.who().id()), c.previous(), c.who(), p.outcome()));
    }

    private CommandAccepted open(Candidate c, CheckContext ctx, List<Problem> problems, String strategy, String notes, Check policyCheck, Check replayCheck,
            Check contractsCheck) {
        WorkflowRecord draft = c.record();
        WorkflowDefinition def = draft.definition();
        String previousVersion = c.previousVersion();
        String diff = UnifiedDiff.of(c.previous() == null ? "" : c.previous().yaml(), draft.yaml(), draft.path() == null ? pathFor(def) : draft.path());
        List<Check> checks = List.of(
                PublishChecks.schema(def),
                PublishChecks.checks(problems),
                PublishChecks.ansibleLint(def, ctx, c.pins()),
                policyCheck,
                replayCheck,
                contractsCheck,
                PublishChecks.diff(diff, previousVersion),
                PublishChecks.routes(def, publishedDefinitions()),
                PublishChecks.yaml(draft));
        Instant now = clock.instant();
        String title = (previousVersion == null ? "Publish " : "Update ") + def.title() + " " + draft.version();
        ChangeRequest cr = new ChangeRequest(Ids.next("cr"), ChangeRequest.Kind.WORKFLOW, draft.name(), draft.version(),
                VersionRules.branch(draft.name(), draft.version()), title, c.who().id(), ChangeRequest.Status.OPEN, checks, strategy,
                draft.yaml(), diff, now, null, null, null);
        changeRequests.save(cr);
        store.save(draft.withStatus(WorkflowStatus.CANDIDATE).withNotes(notes));
        events.publish(CR_OPENED, "change-request", cr.id(), c.who().id(), crData(cr).put("by", c.who().id()));
        return CommandAccepted.of(cr.id(), "open", Map.of("changeRequest", "/api/v1/change-requests/" + cr.id(), "workflow", "/api/v1/workflows/" + draft.name()));
    }

    @Override
    public Mono<CommandAccepted> mergeChangeRequest(String id) {
        return CurrentIdentity.current().flatMap(who -> blocking(() -> {
            ChangeRequest cr = changeRequests.get(id).orElseThrow(() -> new NotFoundException("change request", id));
            if (cr.status() != ChangeRequest.Status.OPEN) {
                throw new ConflictException("change request " + id + " is " + cr.status().json());
            }
            if (!cr.checksPassed()) {
                throw new ConflictException("checks failed", PublishChecks.failed(cr.checks()));
            }
            // the reviewer is not the author: nobody merges their own change, admins excepted
            if (who.id().equals(cr.author()) && !who.admin()) {
                throw new ForbiddenException(who.name() + " opened change request " + id + "; someone else must review and merge it");
            }
            Instant now = clock.instant();
            Map<String, String> links;
            if (cr.kind() == ChangeRequest.Kind.WORKFLOW) {
                WorkflowRecord rec = store.get(cr.name(), cr.version())
                        .orElseThrow(() -> new NotFoundException("workflow version", cr.name() + "@" + cr.version()));
                AccessRules.requireEdit(who, rec.definition().metadata().owner(), "workflow " + cr.name());
                Check routes = PublishChecks.routes(rec.definition(), publishedDefinitions());
                if (PublishChecks.FAILED.equals(routes.status())) {
                    throw new ConflictException("another workflow was published on the same route: " + routes.detail());
                }
                Optional<WorkflowRecord> previous = store.published(cr.name()).filter(p -> !p.version().equals(cr.version()));
                // the change request first: its pull request may refuse the merge, and then nothing is published
                changeRequests.save(cr.withStatus(ChangeRequest.Status.MERGED, now, who.id()));
                previous.ifPresent(p -> store.save(p.withStatus(WorkflowStatus.RETIRED)));
                store.save(rec.published(now, who.id(), null));
                events.publish(CR_MERGED, "change-request", id, who.id(), crData(cr).put("yaml", rec.yaml()).put("by", who.id()));
                ObjectNode published = data().put("name", cr.name()).put("version", cr.version()).put("strategy", cr.strategy()).put("by", who.id());
                published.put("previous", previous.map(WorkflowRecord::version).orElse(null));
                events.publish(PUBLISHED, "workflow", cr.name(), who.id(), published);
                links = Map.of("workflow", "/api/v1/workflows/" + cr.name());
            } else {
                AccessRules.requireEdit(who, providerOwner(cr.yaml()), "provider " + cr.name());
                changeRequests.save(cr.withStatus(ChangeRequest.Status.MERGED, now, who.id()));
                events.publish(CR_MERGED, "change-request", id, who.id(), crData(cr).put("yaml", cr.yaml()).put("by", who.id()));
                links = Map.of("provider", "/api/v1/providers/" + cr.name());
            }
            return CommandAccepted.of(id, "merged", links);
        }));
    }

    private List<WorkflowDefinition> publishedDefinitions() {
        return store.all().stream().filter(r -> r.status() == WorkflowStatus.PUBLISHED).map(WorkflowRecord::definition).toList();
    }

    @Override
    public Mono<CommandAccepted> closeChangeRequest(String id) {
        return CurrentIdentity.current().flatMap(who -> blocking(() -> {
            ChangeRequest cr = changeRequests.get(id).orElseThrow(() -> new NotFoundException("change request", id));
            if (cr.status() != ChangeRequest.Status.OPEN) {
                throw new ConflictException("change request " + id + " is " + cr.status().json());
            }
            Optional<WorkflowRecord> rec = cr.kind() == ChangeRequest.Kind.WORKFLOW ? store.get(cr.name(), cr.version()) : Optional.empty();
            if (cr.kind() == ChangeRequest.Kind.WORKFLOW) {
                AccessRules.requireEdit(who, rec.map(r -> r.definition().metadata().owner()).orElse(null), "workflow " + cr.name());
            } else {
                AccessRules.requireEdit(who, providerOwner(cr.yaml()), "provider " + cr.name());
            }
            changeRequests.save(cr.withStatus(ChangeRequest.Status.CLOSED, clock.instant(), who.id()));
            rec.filter(r -> r.status() == WorkflowStatus.CANDIDATE).ifPresent(r -> store.save(r.withStatus(WorkflowStatus.DRAFT)));
            events.publish(CR_CLOSED, "change-request", id, who.id(), crData(cr).put("by", who.id()));
            return CommandAccepted.of(id, "closed", null);
        }));
    }

    // ------------------------------------------------------------------ replay

    @Override
    public Mono<ReplayResult> replayCheck(String name, String version) {
        return CurrentIdentity.current().flatMap(who -> blocking(() -> {
            WorkflowRecord rec = store.get(name, version).orElseThrow(() -> new NotFoundException("workflow version", name + "@" + version));
            return new Candidate(rec, previousOf(name, version).orElse(null), who);
        })).flatMap(c -> c.previous() == null
                ? Mono.just(ReplayResult.none())
                : replay(name, c.record().definition(), c.previousVersion(), c.who()));
    }

    /** Dry-run the definition with the input of every job of {@code previousVersion} in the last seven days. */
    Mono<ReplayResult> replay(String name, WorkflowDefinition def, String previousVersion, Identity who) {
        Instant since = clock.instant().minus(REPLAY_WINDOW);
        return runsQueries.outcomes(name, previousVersion, since)
                .defaultIfEmpty(List.of())
                .flatMapMany(Flux::fromIterable)
                .concatMap(o -> runs.dryRun(def, o.input() == null || o.input().isNull() ? F.objectNode() : o.input(), who)
                        .map(r -> divergence(o, r))
                        .defaultIfEmpty(Optional.of(new Divergence(o.id(), null, "no dry-run result")))
                        .onErrorResume(e -> Mono.just(Optional.of(new Divergence(o.id(), null, "dry run failed: " + e.getMessage())))))
                .collectList()
                .map(all -> {
                    List<Divergence> divergences = all.stream().flatMap(Optional::stream).toList();
                    return new ReplayResult(divergences.isEmpty(), all.size(), divergences);
                });
    }

    static Optional<Divergence> divergence(JobOutcome outcome, DryRunResult result) {
        if (result.passed()) {
            return Optional.empty();
        }
        Optional<DryRunResult.DryRunStep> failed = result.steps() == null ? Optional.empty()
                : result.steps().stream().filter(s -> "failed".equals(s.status())).findFirst();
        String reason = failed.map(DryRunResult.DryRunStep::error).filter(e -> e != null && !e.isBlank())
                .orElse(result.error() == null || result.error().isBlank() ? "dry run failed" : result.error());
        return Optional.of(new Divergence(outcome.id(), failed.map(DryRunResult.DryRunStep::id).orElse(null), reason));
    }

    /** The version a replay compares against: the highest published or retired version below this one, else the published one. */
    Optional<WorkflowRecord> previousOf(String name, String version) {
        Semver v = Semver.parse(version);
        List<WorkflowRecord> others = store.versions(name).stream()
                .filter(r -> r.status() == WorkflowStatus.PUBLISHED || r.status() == WorkflowStatus.RETIRED)
                .filter(r -> !r.version().equals(version))
                .toList();
        return others.stream().filter(r -> Semver.parse(r.version()).compareTo(v) < 0).max((a, b) -> Semver.ORDER.compare(a.version(), b.version()))
                .or(() -> others.stream().filter(WorkflowRecord::isPublished).max((a, b) -> Semver.ORDER.compare(a.version(), b.version())));
    }

    // ------------------------------------------------------------------ import

    @Override
    public Mono<Imported> importFrom(ImportRequest request) {
        if (request == null || request.source() == null || !WorkflowImports.SOURCES.contains(request.source())) {
            return Mono.error(new IllegalArgumentException("source is one of " + WorkflowImports.SOURCES.stream().sorted().toList()));
        }
        if (request.ref() == null || request.ref().isBlank()) {
            return Mono.error(new IllegalArgumentException("ref is required"));
        }
        return Mono.zip(providers.all().collectList(),
                        resources.types().map(ResourceTypeDefinition::name).collectList(),
                        blocking(() -> store.all().stream().map(WorkflowRecord::name).distinct().toList()))
                .map(t -> WorkflowImports.build(request.source(), request.ref(), t.getT1(), t.getT2(), t.getT3()))
                .map(i -> new Imported(i.yaml(), i.notes()));
    }

    // ------------------------------------------------------------------ events from the releases slice

    void on(DomainEvent event) {
        if (event.type().startsWith("release.") && event.data() != null && event.data().hasNonNull("provider")) {
            return; // a provider release: the providers slice keeps its versions
        }
        switch (event.type()) {
            case RELEASE_ROLLED_BACK -> rollBack(event.data());
            case RELEASE_PROMOTED -> log.info("release promoted: {}", event.data());
            default -> {
            }
        }
    }

    /**
     * A rolled-back release: its candidate ({@code to}) is retired and the version it was replacing
     * ({@code from}) is published again. Nothing changes when the candidate is no longer the
     * published version, for example when a newer version superseded it.
     */
    private void rollBack(JsonNode data) {
        String name = data == null ? null : data.path("workflow").asText(null);
        String from = data == null ? null : data.path("from").asText(null);
        String to = data == null ? null : data.path("to").asText(null);
        if (name == null || to == null) {
            log.warn("release.rolled-back without workflow/to: {}", data);
            return;
        }
        Optional<WorkflowRecord> candidate = store.get(name, to).filter(WorkflowRecord::isPublished);
        if (candidate.isEmpty()) {
            log.info("release.rolled-back: {} {} is no longer the published version; nothing to restore", name, to);
            return;
        }
        Optional<WorkflowRecord> previous = from == null ? Optional.empty() : store.get(name, from);
        if (previous.isEmpty()) {
            log.warn("release.rolled-back: {} has no version {} to return to", name, from);
            return;
        }
        store.save(previous.get().withStatus(WorkflowStatus.PUBLISHED));
        store.save(candidate.get().withStatus(WorkflowStatus.RETIRED));
        log.info("rolled back {} from {} to {}", name, to, from);
    }

    // ------------------------------------------------------------------ helpers

    private List<Problem> problems(WorkflowSource source, CheckContext ctx) {
        if (source.hasYaml()) {
            return WorkflowChecks.check(source.yaml(), ctx);
        }
        try {
            return WorkflowChecks.check(source.parse(), ctx);
        } catch (IllegalArgumentException e) {
            return List.of(Problem.error("yaml.invalid", null, null, e.getMessage()));
        }
    }

    private Optional<ChangeRequest> openChangeRequest(String name) {
        return changeRequests.all().stream()
                .filter(cr -> cr.status() == ChangeRequest.Status.OPEN && cr.kind() == ChangeRequest.Kind.WORKFLOW && name.equals(cr.name()))
                .findFirst();
    }

    static String ownerOf(List<WorkflowRecord> versions) {
        return WorkflowQueryHandler.currentOf(versions).definition().metadata().owner();
    }

    private static String providerOwner(String yaml) {
        try {
            return yaml == null ? null : ProviderYaml.parse(yaml).owner();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code workflows/<owner>/<name>.yaml}, the file of a new workflow in the content repo. */
    /** The name and owner become the file's path in the content repo, so both must be slugs (400 otherwise). */
    static String requireNames(WorkflowDefinition def) {
        String owner = def.metadata() == null ? null : def.metadata().owner();
        if (owner != null && !owner.isBlank()) {
            ContentFiles.requireSlug("The owner team", owner);
        }
        return ContentFiles.requireSlug("The workflow name", def.name());
    }

    static String pathFor(WorkflowDefinition def) {
        String owner = def.metadata().owner();
        return "workflows/" + (owner == null || owner.isBlank() ? "shared" : owner) + "/" + def.name() + ".yaml";
    }

    private static ObjectNode data() {
        return F.objectNode();
    }

    private static ObjectNode crData(ChangeRequest cr) {
        return data().put("id", cr.id()).put("kind", cr.kind().json()).put("name", cr.name()).put("version", cr.version());
    }
}
