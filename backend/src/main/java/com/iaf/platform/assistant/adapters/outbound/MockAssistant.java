package com.iaf.platform.assistant.adapters.outbound;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.DraftBuilder;
import com.iaf.platform.assistant.domain.DraftEditor;
import com.iaf.platform.assistant.domain.DraftRepairs;
import com.iaf.platform.assistant.domain.Events;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.domain.ProviderDraftBuilder;
import com.iaf.platform.assistant.domain.Registry;
import com.iaf.platform.assistant.domain.WorkflowDoc;
import com.iaf.platform.assistant.domain.WorkflowDraft;
import com.iaf.platform.assistant.domain.WorkflowDraft.Missing;
import com.iaf.platform.assistant.domain.WorkflowDraft.Question;
import com.iaf.platform.assistant.domain.Words;
import com.iaf.platform.assistant.ports.outbound.AssistantPort;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The demo agent: deterministic drafts from the registry. It reads providers, published workflows
 * and resource types through the other slices' query ports, builds or edits a draft by rules,
 * validates it with the real checks, repairs what it can for up to five rounds, dry-runs it, and
 * streams the same events the Devin adapter would.
 */
@Slf4j
@Component
public class MockAssistant implements AssistantPort {

    static final int MAX_ROUNDS = 5;

    private final ProviderQueries providers;
    private final WorkflowQueries workflows;
    private final ResourceQueries resources;
    private final RunsCommands runs;
    private final Clock clock;
    private final Duration pace;

    @Autowired
    public MockAssistant(ProviderQueries providers, WorkflowQueries workflows, ResourceQueries resources, RunsCommands runs, Clock clock) {
        this(providers, workflows, resources, runs, clock, Duration.ofMillis(100));
    }

    /** {@code pace} is the base delay between events (status 1x, message 2x, draft 3x); zero for tests. */
    public MockAssistant(ProviderQueries providers, WorkflowQueries workflows, ResourceQueries resources, RunsCommands runs, Clock clock, Duration pace) {
        this.providers = providers;
        this.workflows = workflows;
        this.resources = resources;
        this.runs = runs;
        this.clock = clock;
        this.pace = pace == null ? Duration.ZERO : pace;
    }

    @Override
    public String mode() {
        return "mock";
    }

    /** Its drafts already went through the platform's checks, repairs and dry run. */
    @Override
    public boolean needsPlatformChecks() {
        return false;
    }

    @Override
    public Flux<AssistantEvent> run(AssistantRequest request) {
        Flux<AssistantEvent> events = Flux.concat(
                Flux.just(Events.status(now(), "Reading the registry")),
                registry().flatMapMany(reg -> Flux.concat(
                        Flux.just(Events.message(now(), found(reg)), Events.status(now(), "Drafting")),
                        Mono.fromCallable(() -> build(request, reg)).subscribeOn(Schedulers.boundedElastic())
                                .flatMap(built -> validate(built, reg, 0))
                                .flatMap(v -> dryRun(v, request.who()))
                                .flatMapMany(this::emit))));
        return paced(events).onErrorResume(e -> {
            log.warn("mock assistant failed: {}", e.toString());
            return Flux.just(Events.message(now(), "The assistant could not finish the draft: " + e.getMessage()), Events.failed(now(), e.getMessage()));
        });
    }

    @Override
    public Mono<ProviderDraft> draftProvider(String description, Identity who) {
        String owner = who == null || who.teams() == null || who.teams().isEmpty() ? null : who.teams().get(0);
        return registry().publishOn(Schedulers.boundedElastic()).map(reg -> ProviderDraftBuilder.build(description, reg, owner));
    }

    // ---------------------------------------------------------------- pipeline

    /** What the builder or the editor produced, before validation. */
    record Built(ObjectNode document, String title, String summary, List<String> changes, List<String> assumptions, List<Question> questions, List<Missing> missing) {
    }

    /** The draft after the validate-and-repair rounds. */
    record Validated(Built built, String yaml, List<Problem> problems, int rounds, List<String> repairs) {
        boolean valid() {
            return problems.stream().noneMatch(Problem::isError);
        }
    }

    record Finished(Validated validated, DryRunResult dryRun) {
    }

    private Mono<Registry> registry() {
        return Mono.zip(providers.all().collectList(), workflows.publishedAll().collectList(), resources.types().collectList())
                .map(t -> new Registry(t.getT1(), t.getT2(), t.getT3()));
    }

    private static String found(Registry reg) {
        int actions = reg.providers().stream().mapToInt(p -> p.actions().size()).sum();
        return "Found " + count(reg.providers().size(), "provider") + " with " + count(actions, "action") + ", "
                + count(reg.workflows().size(), "published workflow") + " and " + count(reg.types().size(), "resource type") + " in the registry.";
    }

    private static String count(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    private Built build(AssistantRequest request, Registry reg) {
        Context ctx = request.context();
        DraftBuilder.Context bctx = new DraftBuilder.Context(ctx == null ? null : ctx.owner(), ctx == null ? null : ctx.folder(), ctx == null ? null : ctx.requesterTeam());
        String current = request.currentDraft();
        if (current != null && !current.isBlank()) {
            try {
                DraftEditor.Result r = DraftEditor.edit(current, request.text(), reg, bctx);
                String title = r.document().path("metadata").path("title").asText(r.document().path("metadata").path("name").asText("the draft"));
                String summary = r.changes().isEmpty()
                        ? "Nothing changed in " + title + "."
                        : "Applied " + count(r.changes().size(), "change") + " to " + title + ": " + String.join("; ", r.changes()) + ".";
                return new Built(r.document(), title, Words.clip(summary, 580), r.changes(), r.assumptions(), r.questions(), r.missing());
            } catch (IllegalArgumentException e) {
                log.info("current draft does not parse, drafting afresh: {}", e.getMessage());
                DraftBuilder.Result r = DraftBuilder.build(request.text(), reg, bctx);
                List<String> assumptions = new ArrayList<>(r.assumptions());
                assumptions.add(0, "The draft on the canvas did not parse (" + e.getMessage() + "), so this is a fresh draft from your message.");
                return new Built(r.document(), r.title(), r.summary(), List.of("Replaced the draft that did not parse"), assumptions, r.questions(), r.missing());
            }
        }
        DraftBuilder.Result r = DraftBuilder.build(request.text(), reg, bctx);
        return new Built(r.document(), r.title(), r.summary(), List.of(), r.assumptions(), r.questions(), r.missing());
    }

    /** Check, repair what the fixes allow, check again: at most {@link #MAX_ROUNDS} rounds. */
    private Mono<Validated> validate(Built built, Registry reg, int round) {
        return validate(built, reg, round, new ArrayList<>());
    }

    private Mono<Validated> validate(Built built, Registry reg, int round, List<String> repairs) {
        String yaml;
        try {
            yaml = WorkflowDoc.toYaml(built.document());
        } catch (IllegalArgumentException e) {
            return Mono.just(new Validated(built, "", List.of(Problem.error("yaml.invalid", null, null, e.getMessage())), round + 1, repairs));
        }
        String text = yaml;
        return workflows.check(text).defaultIfEmpty(List.of()).flatMap(problems -> {
            int r = round + 1;
            if (problems.isEmpty() || r >= MAX_ROUNDS) {
                return Mono.just(new Validated(built, text, problems, r, repairs));
            }
            List<String> applied = DraftRepairs.apply(built.document(), problems, reg);
            if (applied.isEmpty()) {
                return Mono.just(new Validated(built, text, problems, r, repairs));
            }
            repairs.addAll(applied);
            return validate(built, reg, r, repairs);
        });
    }

    private Mono<Finished> dryRun(Validated v, Identity who) {
        if (!v.valid() || v.yaml().isBlank()) {
            return Mono.just(new Finished(v, null));
        }
        WorkflowDefinition def = DraftRepairs.parseOrNull(v.built().document());
        if (def == null) {
            return Mono.just(new Finished(v, null));
        }
        JsonNode input = Schemas.sample(def.inputSchema());
        return Mono.defer(() -> runs.dryRun(def, input, who == null ? Identity.SYSTEM : who))
                .map(result -> new Finished(v, result))
                .defaultIfEmpty(new Finished(v, null))
                .onErrorResume(e -> {
                    log.info("dry run failed: {}", e.toString());
                    return Mono.just(new Finished(v, new DryRunResult(false, List.of(), List.of(), e.getMessage())));
                });
    }

    private Flux<AssistantEvent> emit(Finished f) {
        Validated v = f.validated();
        Built b = v.built();
        List<String> errors = v.problems().stream().filter(Problem::isError).map(p -> p.code() + (p.step() == null ? "" : " @" + p.step()) + ": " + p.message()).toList();
        String status = !b.questions().isEmpty() ? WorkflowDraft.NEEDS_INPUT : v.valid() ? WorkflowDraft.READY : WorkflowDraft.FAILED;
        List<String> changes = new ArrayList<>(b.changes());
        changes.addAll(v.repairs());
        WorkflowDraft.DryRun dry = f.dryRun() == null ? null : new WorkflowDraft.DryRun(f.dryRun().passed(), f.dryRun().path() == null ? List.of() : f.dryRun().path(), f.dryRun().error());
        WorkflowDraft draft = new WorkflowDraft(status, b.summary(), v.yaml(), changes, b.assumptions(), b.questions(), b.missing(),
                new WorkflowDraft.Validation(v.valid(), v.rounds(), errors), dry);
        return Flux.just(
                Events.draft(now(), draft),
                Events.validation(now(), v.problems()),
                Events.message(now(), prose(draft, v, f.dryRun())),
                Events.done(now()));
    }

    /** The summary, what was assumed and what is asked, as one plain paragraph. */
    private static String prose(WorkflowDraft draft, Validated v, DryRunResult dry) {
        StringBuilder sb = new StringBuilder(draft.summary());
        if (!v.repairs().isEmpty()) {
            sb.append(" While validating I ").append(String.join("; ", v.repairs()).toLowerCase().replaceFirst("^(\\w)", "$1")).append('.');
        }
        long warnings = v.problems().stream().filter(p -> !p.isError()).count();
        if (v.valid()) {
            sb.append(" The checks pass").append(warnings > 0 ? " with " + count((int) warnings, "warning") : "").append(" after ").append(count(v.rounds(), "round")).append('.');
        } else {
            sb.append(" The checks still report ").append(count(draft.validation().errors().size(), "error")).append(": ")
                    .append(String.join("; ", draft.validation().errors())).append('.');
        }
        if (dry != null) {
            sb.append(dry.passed() ? " The dry run passed" + (dry.path() == null || dry.path().isEmpty() ? "" : " through " + String.join(", ", dry.path())) + "."
                    : " The dry run failed" + (dry.error() == null ? "" : ": " + dry.error()) + ".");
        }
        if (!draft.assumptions().isEmpty()) {
            sb.append(" I assumed: ").append(String.join(" ", draft.assumptions()));
        }
        if (!draft.missing().isEmpty()) {
            sb.append(" Missing from the registry: ").append(String.join("; ", draft.missing().stream()
                    .map(m -> m.kind().replace('_', ' ') + " " + m.name() + " (" + m.why() + ")").toList())).append('.');
        }
        if (!draft.questions().isEmpty()) {
            sb.append(" Questions: ");
            for (Question q : draft.questions()) {
                sb.append(q.question());
                if (q.options() != null && !q.options().isEmpty()) {
                    sb.append(" (").append(String.join(", ", q.options())).append(')');
                }
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    private Flux<AssistantEvent> paced(Flux<AssistantEvent> events) {
        if (pace.isZero()) {
            return events;
        }
        return events.concatMap(e -> {
            Duration d = switch (e.type()) {
                case AssistantEvent.DRAFT -> pace.multipliedBy(3);
                case AssistantEvent.MESSAGE, AssistantEvent.VALIDATION -> pace.multipliedBy(2);
                case AssistantEvent.DONE -> Duration.ZERO;
                default -> pace;
            };
            return d.isZero() ? Mono.just(e) : Mono.delay(d).thenReturn(e);
        });
    }

    private java.time.Instant now() {
        return clock.instant();
    }
}
