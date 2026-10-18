package com.iaf.platform.workflows.adapters.outbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.demo.DemoReleases;
import com.iaf.platform.common.demo.DemoReleases.Story;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.ports.outbound.UserDirectory;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.domain.PublishChecks;
import com.iaf.platform.workflows.domain.ReplayResult;
import com.iaf.platform.workflows.domain.UnifiedDiff;
import com.iaf.platform.workflows.domain.VersionRules;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Metadata;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.inbound.WorkflowCommands;
import com.iaf.platform.workflows.ports.inbound.WorkflowCommands.PublishWorkflow;
import com.iaf.platform.workflows.ports.inbound.WorkflowCommands.WorkflowSource;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Demo history of the memory store ({@code platform.demo.seed-history}), following
 * {@link DemoReleases}: the version each demo release replaced, kept as a retired version next to
 * the one the content dir publishes; that version's publish time and release; the change requests
 * that published them; and one change request still waiting for review, opened through the real
 * publish command so its checks are the ones a person would see.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "platform.content.git", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class DemoWorkflowHistory {

    /** The workflow, new version and author of the change request left open for review; the description is what it changes. */
    record OpenDraft(String workflow, String version, String author, String description) {
    }

    static final OpenDraft OPEN_DRAFT = new OpenDraft("create-database", "1.5.0", "ana",
            "Create a MongoDB cluster sized for the environment, register it and hand over the name of its connection secret.");

    /** How long before its release the replaced version had been published. */
    static final Duration PREVIOUS_AGE = Duration.ofDays(21);

    private final WorkflowStorePort store;
    private final ChangeRequestPort changeRequests;
    private final WorkflowCommands commands;
    private final UserDirectory users;
    private final PlatformProperties props;
    private final Clock clock;

    @PostConstruct
    public void seed() {
        if (!props.getDemo().isSeedHistory()) {
            return;
        }
        Instant now = clock.instant();
        Set<String> released = new HashSet<>();
        int versions = 0;
        int merged = 0;
        for (Story s : DemoReleases.STORIES) {
            Optional<WorkflowRecord> current = store.get(s.workflow(), s.to());
            if (current.isEmpty() || current.get().definition() == null) {
                continue;
            }
            WorkflowRecord to = current.get();
            if (store.get(s.workflow(), s.from()).isEmpty()) {
                store.save(previous(to, s, now));
                versions++;
            }
            // the newest story of a workflow decides when and by whom its version went out
            List<Story> stories = DemoReleases.of(s.workflow());
            Story latest = stories.get(stories.size() - 1);
            store.save(to.published(latest.startedAt(now), latest.startedBy(), latest.id()));
            if (released.add(s.workflow() + "@" + s.to())) {
                changeRequests.save(merged(to, store.get(s.workflow(), s.from()).orElse(null), s, now, ++merged));
            }
        }
        log.info("demo workflow history: {} earlier version(s), {} merged change request(s)", versions, merged);
    }

    /** The version a story replaced: the same file with the version it had, published well before and retired by the release. */
    static WorkflowRecord previous(WorkflowRecord to, Story s, Instant now) {
        Instant at = s.startedAt(now).minus(PREVIOUS_AGE);
        WorkflowDefinition def = to.definition().withVersion(s.from());
        return WorkflowRecord.of(def, WorkflowStatus.PUBLISHED, to.path(), at, s.startedBy())
                .published(at, s.startedBy(), null)
                .withStatus(WorkflowStatus.RETIRED);
    }

    /** The change request that published {@code to}, merged when its release started, with the checks it passed. */
    static ChangeRequest merged(WorkflowRecord to, WorkflowRecord from, Story s, Instant now, int n) {
        WorkflowDefinition def = to.definition();
        String diff = UnifiedDiff.of(from == null ? "" : from.yaml(), to.yaml(), to.path());
        String previous = from == null ? null : from.version();
        List<Check> checks = List.of(
                PublishChecks.schema(def),
                PublishChecks.checks(List.of()),
                PublishChecks.policy(PolicyDecision.allow("mock")),
                PublishChecks.replay(new ReplayResult(true, 5, List.of()), previous),
                PublishChecks.diff(diff, previous),
                PublishChecks.yaml(to));
        Instant mergedAt = s.startedAt(now);
        String title = (previous == null ? "Publish " : "Update ") + def.title() + " " + to.version();
        return new ChangeRequest(String.format("cr_demo%02d", n), ChangeRequest.Kind.WORKFLOW, to.name(), to.version(),
                VersionRules.branch(to.name(), to.version()), title, s.startedBy(), ChangeRequest.Status.OPEN, checks, s.strategy(),
                to.yaml(), diff, mergedAt.minus(Duration.ofHours(3)), null, null, null)
                .withStatus(ChangeRequest.Status.MERGED, mergedAt, s.startedBy());
    }

    /**
     * The change request left open: a draft of the next minor version saved and published by its
     * author through the commands, so it carries the real checks. Runs once everything is wired.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void openDraft() {
        if (!props.getDemo().isSeedHistory()) {
            return;
        }
        Optional<WorkflowRecord> published = store.published(OPEN_DRAFT.workflow());
        Optional<Identity> author = users.byId(OPEN_DRAFT.author());
        if (published.isEmpty() || author.isEmpty() || store.get(OPEN_DRAFT.workflow(), OPEN_DRAFT.version()).isPresent()) {
            return;
        }
        WorkflowDefinition def = published.get().definition().withVersion(OPEN_DRAFT.version());
        Metadata m = def.metadata();
        def = def.withMetadata(new Metadata(m.name(), m.title(), m.noun(), m.verb(), m.persona(), m.owner(), m.folder(), m.tags(), m.version(),
                OPEN_DRAFT.description()));
        String yaml = WorkflowYaml.toYaml(def);
        try {
            commands.saveDraft(OPEN_DRAFT.workflow(), WorkflowSource.ofYaml(yaml))
                    .then(commands.publish(OPEN_DRAFT.workflow(), new PublishWorkflow(null, "Clearer description for the catalog")))
                    .contextWrite(ctx -> CurrentIdentity.with(ctx, author.get()))
                    .block(Duration.ofSeconds(30));
            log.info("demo change request opened for {} {}", OPEN_DRAFT.workflow(), OPEN_DRAFT.version());
        } catch (RuntimeException e) {
            log.warn("demo change request for {} could not be opened: {}", OPEN_DRAFT.workflow(), e.toString());
        }
    }
}
