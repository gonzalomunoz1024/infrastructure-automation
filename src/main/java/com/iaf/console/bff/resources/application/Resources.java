package com.iaf.console.bff.resources.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.jobs.application.JobRetries;
import com.iaf.console.bff.jobs.application.Jobs;
import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.access.domain.DeniedRow;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.jobs.domain.JobNotFailedException;
import com.iaf.console.bff.jobs.domain.JobView;
import com.iaf.console.bff.platform.application.PausedJobs;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resources.domain.ActivityEntry;
import com.iaf.console.bff.resources.domain.BatchAccepted;
import com.iaf.console.bff.resources.domain.BatchInput;
import com.iaf.console.bff.resources.domain.BatchPreview;
import com.iaf.console.bff.resources.domain.BlastRadius;
import com.iaf.console.bff.resources.domain.DependentsExistException;
import com.iaf.console.bff.resources.domain.CommandAccepted;
import com.iaf.console.bff.resources.domain.CloneDraft;
import com.iaf.console.bff.resources.domain.CommandInput;
import com.iaf.console.bff.resources.domain.FindingView;
import com.iaf.console.bff.resources.domain.GoneException;
import com.iaf.console.bff.resources.domain.LineageView;
import com.iaf.console.bff.resources.domain.ListSorts;
import com.iaf.console.bff.resources.domain.PlatformStates;
import com.iaf.console.bff.resources.domain.PreviewView;
import com.iaf.console.bff.resources.domain.ResourceRef;
import com.iaf.console.bff.resources.domain.ResourceView;
import com.iaf.console.bff.resources.domain.StateAnswer;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.resources.domain.StateCommands;
import com.iaf.console.bff.resources.domain.StateQuery;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.bff.resources.ports.outbound.StatePort;
import com.iaf.console.bff.search.domain.SearchHit;
import com.iaf.console.bff.search.domain.SearchQuery;
import com.iaf.console.bff.search.ports.outbound.SearchPort;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.tags.application.TagPolicies;
import com.iaf.console.bff.tags.domain.TagPolicy;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleAction;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.JobFilter;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.model.BatchContext;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.JobDetail;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.platform.model.JobSummary;
import com.iaf.console.platform.model.Lineage;
import com.iaf.console.platform.model.PolicyDecision;
import com.iaf.console.platform.model.PolicyRule;
import com.iaf.console.platform.model.RerunRequest;
import com.iaf.console.platform.model.Resource;
import com.iaf.console.platform.model.RetryRequest;
import com.iaf.console.platform.model.RolloutPolicy;
import com.iaf.console.wfrn.Wfrn;
import com.iaf.console.wfrn.WfrnException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The resource pages (WP 3.2, 3.3): the state service is the source of what exists and in which
 * state, the platform runs the workflows. A create mints the name first and starts the create
 * workflow with the name in the job's context, so the registry binds it; an update or a verb
 * starts the workflow on the registry's record and then tells the state service what is
 * underway; the feed brings the outcome back. Every read is scoped to the session's account and
 * region, every command asks the account's policies, a destructive one a fresh sign-in and an
 * explicit confirmation.
 */
public class Resources {

    public static final String TAG_VERB = "tag";
    static final String LIST_VERB = "list";
    static final String GET_VERB = "get";
    private static final Logger log = LoggerFactory.getLogger(Resources.class);
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([A-Za-z0-9_-]+)}");
    private static final int RECENT_JOBS = 10;
    private static final int MAX_QUERY = 256;
    private static final int HYDRATE_CONCURRENCY = 16;
    private static final int RADIUS_CONCURRENCY = 8;

    private final Types types;
    private final Access access;
    private final Sessions sessions;
    private final Trust trust;
    private final PlatformClients clients;
    private final PlatformProperties platform;
    private final StatePort state;
    private final SearchPort search;
    private final BlastRadii radii;
    private final Jobs jobs;
    private final JobRetries retries;
    private final TagPolicies tagPolicies;
    private final ObjectMapper json;

    public Resources(Types types, Access access, Sessions sessions, Trust trust, PlatformClients clients, PlatformProperties platform,
            StatePort state, SearchPort search, BlastRadii radii, Jobs jobs, JobRetries retries, TagPolicies tagPolicies, ObjectMapper json) {
        this.types = types;
        this.access = access;
        this.sessions = sessions;
        this.trust = trust;
        this.clients = clients;
        this.platform = platform;
        this.state = state;
        this.search = search;
        this.radii = radii;
        this.jobs = jobs;
        this.retries = retries;
        this.tagPolicies = tagPolicies;
        this.json = json;
    }

    /** The filters of a list. */
    public record ListFilters(String q, String state, Map<String, String> tags, Boolean drift, String sort, Integer limit, String after) {
        public ListFilters {
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }
    }

    /** A resource page's answer: the view and its ETag, or only the ETag when the page's copy still stands. */
    public record Detail(ResourceView view, String etag, boolean notModified) {
    }

    /** A resource with the type it belongs to, as every command starts. */
    private record Subject(ConsoleType type, StateResource resource, String etag) {
    }

    // ---- reads

    /**
     * One page of the account's resources of a type, as {@code <service>:list} on the type's
     * pattern allows: from the state service, or with free text from the search index, each hit
     * read again from the state service so the row shows what the resource is now (a hit the
     * index still holds but the state service no longer does is left out).
     */
    public Mono<Page<ResourceView>> list(Session session, String type, ListFilters filters) {
        return types.byType(session, type).flatMap(t -> {
            boolean searched = filters.q() != null && !filters.q().isBlank();
            String sort = searched ? ListSorts.toIndex(filters.sort()) : ListSorts.toState(filters.sort());
            if (searched && filters.q().strip().length() > MAX_QUERY) {
                throw new InvalidInputException("q is at most " + MAX_QUERY + " characters",
                        List.of(Problem.error("length", "/q", "q is at most " + MAX_QUERY + " characters")));
            }
            int size = CursorPaging.pageSize(filters.limit());
            Mono<Page<ResourceView>> page = searched
                    ? Mono.defer(() -> searched(session, t, new SearchQuery(filters.q().strip(), t.type(), filters.state(), filters.tags(), filters.drift(),
                            sort, size, filters.after())))
                    : Mono.defer(() -> state.list(scope(session), caller(session),
                            new StateQuery(t.type(), filters.state(), null, filters.tags(), null, filters.drift(), sort, size, filters.after())))
                            .map(p -> new Page<>(p.items().stream().map(ResourceView::summary).toList(), p.next()));
            return access.require(session, AccessCheck.action(t.service(), LIST_VERB), typePattern(session, t)).then(page);
        });
    }

    /** A page the search index found, each hit read from the state service, in the index's order. */
    private Mono<Page<ResourceView>> searched(Session session, ConsoleType t, SearchQuery query) {
        boolean deleted = StateResource.DELETED.equals(query.state());
        return search.search(scope(session), caller(session), query).flatMap(found -> Flux.fromIterable(found.items())
                .flatMapSequential(hit -> current(session, t, hit, deleted), HYDRATE_CONCURRENCY)
                .collectList()
                .map(items -> new Page<>(items, found.next())));
    }

    /** What a hit is now: the live resource, or with {@code state=deleted} its tombstoned record; nothing when the state service has none. */
    private Mono<ResourceView> current(Session session, ConsoleType t, SearchHit hit, boolean deleted) {
        Mono<StateResource> read = deleted
                ? state.list(scope(session), caller(session), new StateQuery(t.type(), StateResource.DELETED, null, Map.of(), hit.wfrn(), null, null, 1, null))
                        .flatMap(p -> Mono.justOrEmpty(p.items().stream().findFirst()))
                : state.get(scope(session), caller(session), hit.wfrn(), null).flatMap(answer -> Mono.justOrEmpty(answer.resource()));
        return read.filter(r -> t.type().equals(r.type()))
                .map(ResourceView::summary)
                .onErrorResume(e -> e instanceof NotFoundException || e instanceof GoneException, e -> Mono.empty());
    }

    /**
     * One resource, as {@code <service>:get} on its wfrn allows. Its ETag is
     * {@code "<version>.<digest>"}: the state service's version, which {@code If-Match} on an update
     * is checked against, and a digest of the whole page, so a 304 never hides a last job, jobs,
     * lineage or verbs that moved on while that version stood.
     */
    public Mono<Detail> get(Session session, String type, String wfrn, String ifNoneMatch) {
        return types.byType(session, type).flatMap(t -> {
            checkName(session, t, wfrn);
            return access.require(session, AccessCheck.action(t.service(), GET_VERB), wfrn)
                    .then(Mono.defer(() -> state.get(scope(session), caller(session), wfrn, null))).flatMap(answer -> {
                StateResource r = answer.resource();
                return Mono.zip(can(session, t, r), recentJobs(session, r), lineageOf(session, r)).map(tuple -> {
                    List<String> can = tuple.getT1();
                    List<JobView> jobs = tuple.getT2();
                    Optional<Lineage> lineage = tuple.getT3();
                    JobView lastJob = jobs.stream().filter(j -> j.jobId().equals(r.lastJobId())).findFirst().orElse(jobs.isEmpty() ? null : jobs.get(0));
                    FindingView finding = FindingView.of(r, findingCan(can));
                    List<ResourceRef> builtFrom = lineage.map(l -> refs(l.builtFrom())).orElse(List.of());
                    List<ResourceRef> dependents = lineage.map(l -> refs(l.dependents())).orElse(List.of());
                    ResourceView view = ResourceView.detail(r, lastJob, finding, jobs, builtFrom, dependents, can);
                    String etag = pageETag(r.version(), view);
                    return new Detail(view, etag, etag.equals(ifNoneMatch == null ? null : ifNoneMatch.replace("W/", "").trim()));
                });
            });
        });
    }

    /** The resource's log, oldest first; readable after the resource is gone, as the tombstone keeps it. */
    public Mono<Page<ActivityEntry>> activity(Session session, String type, String wfrn, Integer limit, String after) {
        return types.byType(session, type).flatMap(t -> {
            checkName(session, t, wfrn);
            return access.require(session, AccessCheck.action(t.service(), GET_VERB), wfrn)
                    .then(Mono.defer(() -> state.events(scope(session), caller(session), wfrn, CursorPaging.pageSize(limit), after)))
                    .map(page -> new Page<>(page.items().stream().map(ActivityEntry::of).filter(e -> e != null).toList(), page.next()));
        });
    }

    /** The open finding; 404 when the resource has none. */
    public Mono<FindingView> drift(Session session, String type, String wfrn) {
        return readable(session, type, wfrn).flatMap(s -> {
            if (s.resource().drift() == null) {
                return Mono.error(new NotFoundException("no open finding on " + wfrn));
            }
            return can(session, s.type(), s.resource()).map(can -> FindingView.of(s.resource(), findingCan(can)));
        });
    }

    public Mono<LineageView> lineage(Session session, String type, String wfrn) {
        return readable(session, type, wfrn).flatMap(s -> lineageOf(session, s.resource()).map(lineage -> lineage
                .map(l -> lineageView(s.resource(), l)).orElseGet(() -> LineageView.alone(s.resource()))));
    }

    /**
     * One page of the session's account and region's resources as the state service filters them
     * (a null type is every type), as list rows, with no policy asked: the caller leaves out the
     * rows the person may not see, as a resource group's page does per type.
     */
    public Mono<Page<ResourceView>> rows(Session session, StateQuery query) {
        return Mono.defer(() -> state.list(scope(session), caller(session), query))
                .map(page -> new Page<>(page.items().stream().map(ResourceView::summary).toList(), page.next()));
    }

    // ---- commands

    /**
     * Checks the required tags (400 {@code tags.required} or {@code tags.invalid} with a problem on
     * each {@code /tags/<key>}), mints the name, sets the tags on it, then starts the type's create
     * workflow with the name in the job's context.
     */
    public Mono<CommandAccepted> create(Session session, String type, CommandInput body, String requestId, String idempotencyKey) {
        return types.byType(session, type).flatMap(t -> {
            ConsoleAction action = actionOf(t, "create");
            String actionName = AccessCheck.action(t.service(), action.verb());
            return access.require(session, actionName, AccessCheck.typePattern(platform.partition(), t.service(), session.region(), session.accountId(), t.type()))
                    .then(sessions.requireFreshFor(session, actionName))
                    .then(Mono.fromRunnable(() -> requireFields(action, body.input())))
                    .then(requireTags(session, body.tags(), "/tags"))
                    .then(types.entry(session, action.workflow()))
                    .flatMap(entry -> {
                        Map<String, Object> input = inputFor(action, entry, body, null);
                        StateCommands.Mint mint = new StateCommands.Mint(t.type(), session.region(), nameOf(input), entry.workflow(),
                                json.valueToTree(input), session.user().userId());
                        return state.mint(scope(session), caller(session), mint, new StateCommands.Meta(idempotencyKey, requestId))
                                .flatMap(minted -> tagMinted(session, minted, body.tags(), idempotencyKey, requestId))
                                .flatMap(minted -> start(session, entry, Map.of(), input, options(session, requestId, idempotencyKey, minted.wfrn(), body))
                                        .map(accepted -> new CommandAccepted(minted.wfrn(), accepted.jobId(), requestId,
                                                approval(action, accepted.status()))));
                    });
        });
    }

    /** The create workflow's preview, with the console's {@code tags.required} or {@code tags.invalid} deny while the tags fall short. */
    public Mono<PreviewView> previewCreate(Session session, String type, CommandInput body, String requestId) {
        return types.byType(session, type).flatMap(t -> {
            ConsoleAction action = actionOf(t, "create");
            String actionName = AccessCheck.action(t.service(), action.verb());
            return access.require(session, actionName, AccessCheck.typePattern(platform.partition(), t.service(), session.region(), session.accountId(), t.type()))
                    .then(types.entry(session, action.workflow()))
                    .flatMap(entry -> Mono.zip(preview(session, entry, Map.of(), inputFor(action, entry, body, null), options(session, requestId, null, null, body),
                            Map.of()), tagPolicies.forAccount(session.accountId())))
                    .map(both -> withTagRules(both.getT1(), both.getT2().check(body.tags(), "/tags")));
        });
    }

    /** The preview with a deny per kind of tag problem: {@code tags.required} names the missing keys, {@code tags.invalid} the wrong ones. */
    private static PreviewView withTagRules(PreviewView view, List<Problem> problems) {
        PreviewView out = view;
        for (String code : List.of(TagPolicy.REQUIRED, TagPolicy.INVALID)) {
            List<Problem> these = problems.stream().filter(p -> code.equals(p.code())).toList();
            if (!these.isEmpty()) {
                out = out.withRule(code, PreviewView.DENY, (TagPolicy.REQUIRED.equals(code) ? "required tags are missing: " : "tags with values they may not take: ")
                        + String.join(", ", TagPolicy.keysOf(these)));
            }
        }
        return out;
    }

    /** 400 when the tags lack a required one or give one a value it may not take. */
    private Mono<Void> requireTags(Session session, Map<String, String> tags, String prefix) {
        return tagPolicies.forAccount(session.accountId()).flatMap(policy -> {
            List<Problem> problems = policy.check(tags, prefix);
            return problems.isEmpty() ? Mono.<Void>empty() : Mono.<Void>error(new InvalidInputException(tagSentence(problems), problems));
        });
    }

    private static String tagSentence(List<Problem> problems) {
        boolean missing = problems.stream().anyMatch(p -> TagPolicy.REQUIRED.equals(p.code()));
        return missing ? "a create names every required tag: " + String.join(", ", TagPolicy.keysOf(problems)) : "some tags have values they may not take";
    }

    /** Sets a minted resource's tags before its workflow starts (none given, nothing to set). */
    private Mono<StateResource> tagMinted(Session session, StateResource minted, Map<String, String> tags, String idempotencyKey, String requestId) {
        if (tags == null || tags.isEmpty()) {
            return Mono.just(minted);
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey + ":tags";
        StateCommands.Tag command = new StateCommands.Tag(null, session.user().userId(), tags, List.of());
        return state.tag(scope(session), caller(session), minted.wfrn(), command, new StateCommands.Meta(key, requestId));
    }

    /** Starts the update workflow on the registry's record, then records the requested changes on the state service. */
    public Mono<CommandAccepted> update(Session session, String type, String wfrn, CommandInput body, String ifMatch, String requestId,
            String idempotencyKey) {
        return subject(session, type, wfrn).flatMap(s -> {
            ConsoleAction action = actionOf(s.type(), "update");
            String actionName = AccessCheck.action(s.type().service(), action.verb());
            return guard(session, actionName, wfrn, action, body)
                    .then(Mono.fromRunnable(() -> requireState(s.resource(), ifMatch, StateResource.ACTIVE, StateResource.FAILED)))
                    .then(types.entry(session, action.workflow()))
                    .flatMap(entry -> {
                        Map<String, Object> input = inputFor(action, entry, body, s.resource());
                        return start(session, entry, pathParams(entry, s.resource()), input, options(session, requestId, idempotencyKey, wfrn, body))
                                .flatMap(accepted -> {
                                    // no expected version: the feed already moved it when the job started
                                    StateCommands.Update update = new StateCommands.Update(null, session.user().userId(),
                                            accepted.jobId(), entry.workflow(), entry.version(), json.valueToTree(changesOf(action, input)));
                                    return state.requestUpdate(scope(session), caller(session), wfrn, update, new StateCommands.Meta(idempotencyKey, requestId))
                                            .onErrorResume(ConflictException.class, e -> {
                                                log.warn("job {} runs on {} but the state service refused the update: {}", accepted.jobId(), wfrn, e.getMessage());
                                                return Mono.just(s.resource());
                                            })
                                            .onErrorResume(GoneException.class, e -> {
                                                log.warn("job {} runs on {}, which was deleted meanwhile", accepted.jobId(), wfrn);
                                                return Mono.just(s.resource());
                                            })
                                            .thenReturn(new CommandAccepted(wfrn, accepted.jobId(), requestId, approval(action, accepted.status())));
                                });
                    });
        });
    }

    /** Runs a verb of the type's catalog on the resource; a delete also tells the state service the resource is going. */
    public Mono<CommandAccepted> run(Session session, String type, String wfrn, String verb, CommandInput body, String requestId, String idempotencyKey) {
        return subject(session, type, wfrn).flatMap(s -> {
            if ("create".equals(verb) || "update".equals(verb)) {
                return Mono.error(NotFoundException.of("verb", verb));
            }
            ConsoleAction action = actionOf(s.type(), verb);
            String actionName = AccessCheck.action(s.type().service(), action.verb());
            boolean delete = "delete".equals(verb);
            return guard(session, actionName, wfrn, action, body)
                    .then(Mono.fromRunnable(() -> {
                        if (delete) {
                            requireState(s.resource(), null, StateResource.ACTIVE, StateResource.FAILED);
                        } else if (!s.resource().live()) {
                            throw new ConflictException("the resource is " + s.resource().state());
                        }
                    }))
                    .then(delete ? refuseDependents(session, s.resource()) : Mono.empty())
                    .then(types.entry(session, action.workflow()))
                    .flatMap(entry -> {
                        Map<String, Object> input = inputFor(action, entry, body, s.resource());
                        return start(session, entry, pathParams(entry, s.resource()), input, options(session, requestId, idempotencyKey, wfrn, body))
                                .flatMap(accepted -> {
                                    CommandAccepted answer = new CommandAccepted(wfrn, accepted.jobId(), requestId, approval(action, accepted.status()));
                                    if (!delete) {
                                        return Mono.just(answer);
                                    }
                                    StateCommands.Delete request = new StateCommands.Delete(null, session.user().userId(), accepted.jobId(),
                                            entry.workflow(), entry.version(), body.reason());
                                    return state.requestDelete(scope(session), caller(session), wfrn, request, new StateCommands.Meta(idempotencyKey, requestId))
                                            .onErrorResume(ConflictException.class, e -> {
                                                log.warn("job {} runs on {} but the state service refused the delete: {}", accepted.jobId(), wfrn, e.getMessage());
                                                return Mono.just(s.resource());
                                            })
                                            // the delete already finished: the state service holds the tombstone
                                            .onErrorResume(GoneException.class, e -> Mono.just(s.resource()))
                                            .thenReturn(answer);
                                });
                    });
        });
    }

    /**
     * The platform's preview of a verb on the resource. Before a delete or a destructive verb it
     * carries the resource's blast radius; a delete of a resource that has live dependents also
     * carries the {@code dependents.exist} deny. A delete whose workflow publishes no preview still
     * answers, with the console's part alone.
     */
    public Mono<PreviewView> previewVerb(Session session, String type, String wfrn, String verb, CommandInput body, String requestId) {
        return subject(session, type, wfrn).flatMap(s -> {
            if ("create".equals(verb)) {
                return Mono.error(NotFoundException.of("verb", verb));
            }
            ConsoleAction action = actionOf(s.type(), verb);
            String actionName = AccessCheck.action(s.type().service(), action.verb());
            boolean delete = "delete".equals(verb);
            boolean radius = delete || action.destructive();
            return access.require(session, actionName, wfrn)
                    .then(types.entry(session, action.workflow()))
                    .flatMap(entry -> {
                        Map<String, String> known = s.resource().registryId() == null ? Map.of() : Map.of(s.resource().registryId(), wfrn);
                        Mono<PreviewView> platformPreview = radius && !entry.hasPreview()
                                ? Mono.just(PreviewView.unpublished(entry.workflow(), entry.version(), ResourceRef.of(s.resource())))
                                : preview(session, entry, pathParams(entry, s.resource()), inputFor(action, entry, body, s.resource()),
                                        options(session, requestId, null, wfrn, body), known);
                        if (!radius) {
                            return platformPreview;
                        }
                        return Mono.zip(platformPreview, radii.of(session, s.resource(), BlastRadii.DEFAULT_DEPTH))
                                .map(both -> both.getT1().withBlastRadius(both.getT2(), delete));
                    });
        });
    }

    /**
     * Launch more like this (WP 4.6): the type's create form filled from the resource, for the
     * session's account or {@code account} (another of the person's, else 404), as
     * {@code <service>:get} on the resource allows. Each target account says whether the person
     * may create the type there ({@code <service>:create} on its pattern).
     */
    public Mono<CloneDraft> cloneDraft(Session session, String type, String wfrn, String account) {
        String target = account == null || account.isBlank() ? session.accountId() : account;
        if (session.membership(target).isEmpty()) {
            return Mono.error(NotFoundException.of("account", target));
        }
        return readable(session, type, wfrn).flatMap(s -> {
            ConsoleAction create = actionOf(s.type(), "create");
            return Mono.zip(tagPolicies.forAccount(session.accountId()), tagPolicies.forAccount(target), targets(session, s.type()),
                    createInput(session, wfrn))
                    .map(all -> {
                        List<CloneDraft.Note> notes = new ArrayList<>();
                        Map<String, Object> input = cloneInput(create, s.resource(), all.getT4(), !target.equals(session.accountId()), notes);
                        Map<String, String> tags = cloneTags(s.resource().tags(), all.getT1(), all.getT2());
                        return new CloneDraft(s.type().type(), create.workflow(),
                                new CloneDraft.Source(s.resource().wfrn(), s.resource().name(), session.accountId(), session.region()), target, input, tags,
                                notes, all.getT3(), all.getT2());
                    });
        });
    }

    /** Every account of the person's, with whether {@code <service>:create} on the type's pattern there is allowed. */
    private Mono<List<CloneDraft.Target>> targets(Session session, ConsoleType t) {
        return Flux.fromIterable(session.accounts()).flatMapSequential(m -> {
            Session there = m.accountId().equals(session.accountId()) ? session : session.switchedTo(m.accountId(), session.region());
            String pattern = AccessCheck.typePattern(platform.partition(), t.service(), session.region(), m.accountId(), t.type());
            return access.decide(there, List.of(new AccessCheck(AccessCheck.action(t.service(), "create"), pattern)))
                    .map(d -> new CloneDraft.Target(m.accountId(), m.name(), m.accountId().equals(session.accountId()), d.get(0).allowed(),
                            d.get(0).allowed() ? null : d.get(0).denial()));
        }, 4).collectList();
    }

    /**
     * The create workflow's fields with the source's values: a secret ({@code format: password},
     * {@code writeOnly}, {@code x-secret}) and a read-only field are left out with a note, the name
     * is suggested anew ({@code <name>-copy} when it fits the field), and a field naming another
     * resource gets a note when the copy goes to another account.
     */
    private Map<String, Object> cloneInput(ConsoleAction create, StateResource source, JsonNode createdWith, boolean elsewhere,
            List<CloneDraft.Note> notes) {
        Map<String, Object> input = new LinkedHashMap<>();
        JsonNode properties = create.input() == null ? null : create.input().get("properties");
        JsonNode attributes = source.attributes() == null ? json.createObjectNode() : source.attributes();
        if (properties == null || !properties.isObject()) {
            return input;
        }
        properties.properties().forEach(field -> {
            String name = field.getKey();
            JsonNode schema = field.getValue();
            JsonNode value = attributes.hasNonNull(name) ? attributes.get(name) : createdWith.get(name);
            if (value == null || value.isNull()) {
                return;
            }
            if ("password".equals(schema.path("format").asText()) || schema.path("writeOnly").asBoolean() || schema.path("x-secret").asBoolean()) {
                notes.add(new CloneDraft.Note(name, "secret", name + " is a secret and is not copied; enter it again"));
                return;
            }
            if (schema.path("readOnly").asBoolean()) {
                return;
            }
            if ("name".equals(name)) {
                String suggested = copyName(value.asText(), schema);
                if (suggested == null) {
                    notes.add(new CloneDraft.Note(name, "name", "choose a name for the copy"));
                } else {
                    input.put(name, suggested);
                }
                return;
            }
            if (elsewhere && schema.has("x-resource")) {
                notes.add(new CloneDraft.Note(name, "reference", name + " names a " + schema.path("x-resource").path("type").asText("resource")
                        + " of this account; check that the target account can use it"));
            }
            input.put(name, json.convertValue(value, Object.class));
        });
        return input;
    }

    /**
     * The input the resource was created with, as its {@code ResourceRequested} carries it (after
     * redaction), from the first page of its activity; empty for an imported resource. The current
     * attributes win over it, field by field.
     */
    private Mono<JsonNode> createInput(Session session, String wfrn) {
        return state.events(scope(session), caller(session), wfrn, 20, null).map(page -> {
            for (JsonNode event : page.items()) {
                if ("ResourceRequested".equals(event.path("type").asText()) && event.path("data").path("input").isObject()) {
                    return event.path("data").path("input");
                }
            }
            return (JsonNode) json.createObjectNode();
        }).onErrorResume(e -> !(e instanceof Error), e -> {
            log.warn("the activity of {} is unavailable, so the copy starts from its attributes: {}", wfrn, e.getMessage());
            return Mono.just(json.createObjectNode());
        });
    }

    /** {@code <name>-copy}, the name shortened until it fits the field's pattern and length; null when nothing fits. */
    static String copyName(String name, JsonNode schema) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String pattern = schema.path("pattern").asText(null);
        int max = schema.path("maxLength").asInt(Integer.MAX_VALUE);
        for (int keep = name.length(); keep > 0; keep--) {
            String candidate = name.substring(0, keep).replaceAll("-+$", "") + "-copy";
            if (candidate.length() <= max && (pattern == null || Pattern.matches(pattern, candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /** The source's tags, each required tag that carried the source account's own default given the target account's. */
    private static Map<String, String> cloneTags(Map<String, String> source, TagPolicy here, TagPolicy there) {
        Map<String, String> tags = new LinkedHashMap<>(source == null ? Map.of() : source);
        if (here.accountId() != null && here.accountId().equals(there.accountId())) {
            return tags;
        }
        Map<String, String> hereDefaults = here.defaults();
        Map<String, String> thereDefaults = there.defaults();
        there.required().forEach(tag -> {
            String value = tags.get(tag.key());
            boolean accountDefault = "account".equals(tag.defaultFrom());
            if (accountDefault && thereDefaults.get(tag.key()) != null && (value == null || value.equals(hereDefaults.get(tag.key())))) {
                tags.put(tag.key(), thereDefaults.get(tag.key()));
            }
        });
        return tags;
    }

    /** What depends on the resource, up to {@code depth} steps (1 to 5, 3 by default), as {@code <service>:get} on it allows. */
    public Mono<BlastRadius> blastRadius(Session session, String type, String wfrn, Integer depth) {
        int steps = depth == null ? BlastRadii.DEFAULT_DEPTH : depth;
        if (steps < 1 || steps > BlastRadii.MAX_DEPTH) {
            return Mono.error(new InvalidInputException("depth is 1 to " + BlastRadii.MAX_DEPTH,
                    List.of(Problem.error("range", "/depth", "depth is 1 to " + BlastRadii.MAX_DEPTH))));
        }
        return readable(session, type, wfrn).flatMap(s -> radii.of(session, s.resource(), steps));
    }

    /** Replaces the resource's tags: what the body names is set, what it leaves out is removed. */
    public Mono<Map<String, String>> setTags(Session session, String type, String wfrn, Map<String, String> tags, String requestId, String idempotencyKey) {
        Map<String, String> wanted = tags == null ? Map.of() : tags;
        return subject(session, type, wfrn).flatMap(s -> {
            String actionName = AccessCheck.action(s.type().service(), TAG_VERB);
            return access.require(session, actionName, wfrn).then(tagPolicies.forAccount(session.accountId())).flatMap(policy -> {
                List<String> removed = s.resource().tags().keySet().stream().filter(name -> !wanted.containsKey(name)).toList();
                List<Problem> problems = policy.checkChange(s.resource().tags(), wanted, removed, "/tags");
                if (!problems.isEmpty()) {
                    return Mono.error(new InvalidInputException("the required tags stay on the resource with values they may take", problems));
                }
                StateCommands.Tag command = new StateCommands.Tag(s.resource().version(), session.user().userId(), wanted, removed);
                return state.tag(scope(session), caller(session), wfrn, command, new StateCommands.Meta(idempotencyKey, requestId))
                        .map(StateResource::tags);
            });
        });
    }

    /** One verb over many rows; a create mints one name per row and binds each to its row. */
    public Mono<BatchAccepted> batch(Session session, String type, BatchInput body, String requestId, String idempotencyKey) {
        return types.byType(session, type).flatMap(t -> {
            if (body.verb() == null || body.items().isEmpty()) {
                return Mono.error(new InvalidInputException("a batch names a verb and at least one row",
                        List.of(Problem.error("required", "/items", "at least one row"))));
            }
            ConsoleAction action = actionOf(t, body.verb());
            if (!action.bulk()) {
                return Mono.error(new InvalidInputException("the verb runs one resource at a time",
                        List.of(Problem.error("bulk.unsupported", "/verb", action.verb() + " has no batch route"))));
            }
            String actionName = AccessCheck.action(t.service(), action.verb());
            boolean create = "create".equals(body.verb());
            if (!create) {
                requireRowWfrns(session, t, action, body);
            }
            return (create ? access.require(session, actionName, typePattern(session, t)) : requireEveryRow(session, actionName, body))
                    .then(sessions.requireFreshFor(session, actionName))
                    .then(Mono.fromRunnable(() -> {
                        if (action.destructive() && !body.confirmed()) {
                            throw confirmRequired(action);
                        }
                    }))
                    .then(types.entry(session, action.workflow()))
                    .flatMap(entry -> {
                        Integer max = entry.batch() == null ? null : entry.batch().max();
                        if (max != null && body.items().size() > max) {
                            return Mono.error(new InvalidInputException("the batch is larger than " + entry.workflow() + " takes",
                                    List.of(Problem.error("batch.too-large", "/items", entry.workflow() + " takes at most " + max + " rows in one batch, not "
                                            + body.items().size()))));
                        }
                        Mono<List<Row>> rows = create ? requireRowTags(session, body).then(mintRows(session, t, entry, action, body, requestId, idempotencyKey))
                                : existingRows(session, t, entry, action, body)
                                        .flatMap(found -> "delete".equals(body.verb()) ? refuseDependents(session, found) : Mono.just(found));
                        return rows.flatMap(prepared -> {
                            List<Map<String, Object>> items = prepared.stream().map(Row::input).toList();
                            List<BatchContext> contexts = prepared.stream().map(r -> new BatchContext(r.wfrn(), null)).toList();
                            RolloutPolicy rollout = body.rollout() == null ? null
                                    : new RolloutPolicy(body.rollout().waves(), body.rollout().maxParallel(), body.rollout().stopIfFailedPercent());
                            RequestOptions options = OnBehalfOf.options(trust, session, requestId, idempotencyKey);
                            return clients.forAccount(scope(session))
                                    .flatMap(p -> p.batch(entry, items, body.defaults(), rollout, contexts, options))
                                    .map(accepted -> new BatchAccepted(accepted.body().jobId(), accepted.body().rows(), requestId,
                                            create ? prepared.stream().map(Row::wfrn).toList() : null));
                        });
                    });
        });
    }

    /**
     * A batch's preview (WP 4.5), before anything runs: every row's resource with its own direct
     * dependents, what the rows reach together, and the console's decision (a deny when a delete
     * row has live dependents); as {@code <service>:<verb>} on the type's pattern allows. A row
     * whose resource cannot be read or is in the wrong state carries a problem instead of failing
     * the call.
     */
    public Mono<BatchPreview> previewBatch(Session session, String type, BatchInput body) {
        return types.byType(session, type).flatMap(t -> {
            if (body.verb() == null || body.items().isEmpty()) {
                return Mono.error(new InvalidInputException("a batch names a verb and at least one row",
                        List.of(Problem.error("required", "/items", "at least one row"))));
            }
            ConsoleAction action = actionOf(t, body.verb());
            String actionName = AccessCheck.action(t.service(), action.verb());
            boolean delete = "delete".equals(body.verb());
            return access.require(session, actionName, typePattern(session, t)).then(Mono.defer(() -> {
                if ("create".equals(body.verb())) {
                    return tagPolicies.forAccount(session.accountId()).map(policy -> {
                        List<BatchPreview.Row> rows = new ArrayList<>();
                        for (int i = 0; i < body.items().size(); i++) {
                            Map<String, Object> input = new LinkedHashMap<>(body.defaults());
                            input.putAll(body.items().get(i).input());
                            List<Problem> problems = new ArrayList<>(missingFields(action, input, "/items/" + i + "/input"));
                            problems.addAll(policy.check(body.tagsOf(i), "/items/" + i + "/tags"));
                            rows.add(new BatchPreview.Row(i, null, nameOf(input), null, null, null, problems));
                        }
                        return new BatchPreview(body.verb(), rows, null, decision(rows));
                    });
                }
                return Flux.range(0, body.items().size()).flatMapSequential(i -> previewRow(session, t, body.items().get(i), i, delete), RADIUS_CONCURRENCY)
                        .collectList()
                        .flatMap(read -> {
                            List<StateResource> found = read.stream().map(PreviewRow::resource).filter(Objects::nonNull).toList();
                            return radii.ofAll(session, found, BlastRadii.DEFAULT_DEPTH).map(combined -> {
                                List<BatchPreview.Row> rows = new ArrayList<>();
                                int k = 0;
                                for (PreviewRow row : read) {
                                    if (row.resource() == null) {
                                        rows.add(new BatchPreview.Row(row.index(), row.wfrn(), null, null, null, null, row.problems()));
                                        continue;
                                    }
                                    BlastRadius own = combined.each().get(k++);
                                    List<Problem> problems = new ArrayList<>(row.problems());
                                    if (delete && own.hasDependents()) {
                                        problems.add(Problem.error(DependentsExistException.CODE, "/items/" + row.index() + "/wfrn", DependentsExistException.sentence(own)));
                                    }
                                    StateResource r = row.resource();
                                    rows.add(new BatchPreview.Row(row.index(), r.wfrn(), r.name(), r.state(), own.direct(), own.directDependents(), problems));
                                }
                                return new BatchPreview(body.verb(), rows, combined.together(), decision(rows));
                            });
                        });
            }));
        });
    }

    /** A batch preview's row as read: its resource, or why it cannot run. */
    private record PreviewRow(int index, String wfrn, StateResource resource, List<Problem> problems) {
    }

    private Mono<PreviewRow> previewRow(Session session, ConsoleType t, BatchInput.Row row, int index, boolean delete) {
        String field = "/items/" + index + "/wfrn";
        if (row.wfrn() == null || row.wfrn().isBlank()) {
            return Mono.just(new PreviewRow(index, null, null, List.of(Problem.error("required", field, "the resource's wfrn"))));
        }
        try {
            checkName(session, t, row.wfrn());
        } catch (NotFoundException e) {
            return Mono.just(new PreviewRow(index, row.wfrn(), null, List.of(Problem.error("not-found", field, "no " + t.type() + " " + row.wfrn() + " here"))));
        }
        return state.get(scope(session), caller(session), row.wfrn(), null).map(answer -> {
            StateResource r = answer.resource();
            boolean runnable = delete ? r.is(StateResource.ACTIVE) || r.is(StateResource.FAILED) : r.live();
            List<Problem> problems = runnable ? List.of() : List.of(Problem.error("state", field, "the resource is " + r.state()));
            return new PreviewRow(index, row.wfrn(), r, problems);
        }).onErrorResume(e -> e instanceof NotFoundException || e instanceof GoneException,
                e -> Mono.just(new PreviewRow(index, row.wfrn(), null, List.of(Problem.error("not-found", field, "no " + t.type() + " " + row.wfrn() + " here")))));
    }

    /** The console's decision on a batch: a deny for each kind of blocking problem its rows carry. */
    private static PolicyDecision decision(List<BatchPreview.Row> rows) {
        Map<String, Long> blocking = new LinkedHashMap<>();
        for (BatchPreview.Row row : rows) {
            if (row.problems() != null) {
                for (Problem p : row.problems()) {
                    blocking.merge(p.code(), 1L, Long::sum);
                }
            }
        }
        List<PolicyRule> rules = new ArrayList<>();
        blocking.forEach((code, count) -> rules.add(new PolicyRule(code, PreviewView.DENY,
                (count == 1 ? "1 row" : count + " rows") + " cannot run: " + code)));
        return new PolicyDecision(rules.isEmpty() ? "allow" : PreviewView.DENY, rules, "console");
    }

    /** The workflow's required fields a form leaves out, as problems on their fields. */
    private static List<Problem> missingFields(ConsoleAction action, Map<String, Object> input, String prefix) {
        List<Problem> problems = new ArrayList<>();
        try {
            requireFields(action, input);
        } catch (InvalidInputException e) {
            for (Problem p : e.problems()) {
                problems.add(Problem.error(p.code(), prefix + (p.field() == null ? "" : p.field()), p.message()));
            }
        }
        return problems;
    }

    private record Row(String wfrn, Map<String, Object> input, StateResource resource) {
    }

    private Mono<List<Row>> mintRows(Session session, ConsoleType t, CatalogEntry entry, ConsoleAction action, BatchInput body, String requestId,
            String idempotencyKey) {
        List<Mono<Row>> mints = new ArrayList<>();
        for (int i = 0; i < body.items().size(); i++) {
            BatchInput.Row row = body.items().get(i);
            Map<String, Object> input = new LinkedHashMap<>(body.defaults());
            input.putAll(row.input());
            requireFields(action, input);
            String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey + "#" + i;
            StateCommands.Mint mint = new StateCommands.Mint(t.type(), session.region(), nameOf(input), entry.workflow(), json.valueToTree(input),
                    session.user().userId());
            Map<String, String> tags = body.tagsOf(i);
            mints.add(state.mint(scope(session), caller(session), mint, new StateCommands.Meta(key, requestId))
                    .flatMap(minted -> tagMinted(session, minted, tags, key, requestId))
                    .map(minted -> new Row(minted.wfrn(), row.input(), minted)));
        }
        return Flux.concat(mints).collectList();
    }

    /** A create batch's rows each name every required tag (the batch's tags, the row's over them); 400 with the problems of every row. */
    private Mono<Void> requireRowTags(Session session, BatchInput body) {
        return tagPolicies.forAccount(session.accountId()).flatMap(policy -> {
            List<Problem> problems = new ArrayList<>();
            for (int i = 0; i < body.items().size(); i++) {
                problems.addAll(policy.check(body.tagsOf(i), "/items/" + i + "/tags"));
            }
            return problems.isEmpty() ? Mono.<Void>empty() : Mono.<Void>error(new InvalidInputException(tagSentence(problems), problems));
        });
    }

    /** Every row of a batch on existing resources names one of the path's type in the session's account and region. */
    private static void requireRowWfrns(Session session, ConsoleType t, ConsoleAction action, BatchInput body) {
        for (int i = 0; i < body.items().size(); i++) {
            BatchInput.Row row = body.items().get(i);
            if (row.wfrn() == null || row.wfrn().isBlank()) {
                throw new InvalidInputException("every row of a " + action.verb() + " batch names its resource",
                        List.of(Problem.error("required", "/items/" + i + "/wfrn", "the resource's wfrn")));
            }
            checkName(session, t, row.wfrn());
        }
    }

    /**
     * The verb decided on every row's own wfrn, as a single command would be (a deny on some
     * resources never matches a check on the type's {@code *}), before anything starts. One refused
     * row refuses the whole batch: a 403 with the first refused row's denial and every refused row
     * in {@code deniedRows}.
     */
    private Mono<Void> requireEveryRow(Session session, String actionName, BatchInput body) {
        List<AccessCheck> checks = body.items().stream().map(row -> new AccessCheck(actionName, row.wfrn())).toList();
        return access.decide(session, checks).flatMap(decisions -> {
            List<DeniedRow> denied = new ArrayList<>();
            Denial first = null;
            for (int i = 0; i < decisions.size(); i++) {
                if (!decisions.get(i).allowed()) {
                    denied.add(new DeniedRow(i, checks.get(i).resource()));
                    if (first == null) {
                        first = decisions.get(i).denial();
                    }
                }
            }
            if (denied.isEmpty()) {
                return Mono.empty();
            }
            Denial d = first;
            String reason = (denied.size() == 1 ? "1 row of the batch is" : denied.size() + " rows of the batch are") + " refused, nothing was started; "
                    + d.reason();
            return Mono.error(new DeniedException(new Denial(d.layer(), d.action(), d.resource(), reason, d.role(), d.policyId(), d.statement(),
                    d.scope(), d.requestable(), d.suggestedRole()), denied));
        });
    }

    private Mono<List<Row>> existingRows(Session session, ConsoleType t, CatalogEntry entry, ConsoleAction action, BatchInput body) {
        List<Mono<Row>> rows = new ArrayList<>();
        for (int i = 0; i < body.items().size(); i++) {
            BatchInput.Row row = body.items().get(i);
            rows.add(state.get(scope(session), caller(session), row.wfrn(), null).map(answer -> {
                StateResource r = answer.resource();
                Map<String, Object> input = new LinkedHashMap<>(row.input());
                if (entry.subjectInput() != null && r.registryId() != null) {
                    input.putIfAbsent(entry.subjectInput(), r.registryId());
                }
                return new Row(r.wfrn(), input, r);
            }));
        }
        return Flux.concat(rows).collectList();
    }

    /** A delete is refused while anything live depends on the resource (409 {@code dependents.exist}). */
    private Mono<Void> refuseDependents(Session session, StateResource subject) {
        return radii.direct(session, subject).flatMap(r -> r.hasDependents() ? Mono.<Void>error(DependentsExistException.of(r)) : Mono.<Void>empty());
    }

    /** A batch delete is refused, naming the rows, while anything live depends on one of them. */
    private Mono<List<Row>> refuseDependents(Session session, List<Row> rows) {
        return Flux.fromIterable(rows).flatMapSequential(row -> radii.direct(session, row.resource()), RADIUS_CONCURRENCY).collectList()
                .flatMap(found -> found.stream().anyMatch(BlastRadius::hasDependents) ? Mono.<List<Row>>error(DependentsExistException.ofRows(found))
                        : Mono.just(rows));
    }

    // ---- retries (WP 4.2)

    /**
     * Runs a failed request again: the job's workflow with the job's input on the same resource, as
     * the original action allows (a create's on the type's pattern; a delete also needs a fresh
     * sign-in and still no live dependents). A paused job resumes ({@code :retry}); a failed one is
     * rerun from its failed step and a rolled-back one from its first ({@code :rerun}, a new job with
     * the source job's context and input); a finished batch runs its failed rows again. Then the
     * state service hears of it as of the first request (hub decision 24): a failed create's rerun
     * as {@code :request-retry} naming the new job, which puts the resource back to pending, a
     * retried update or delete as {@code :request-update} or {@code :request-delete}. Rerunning
     * first means a rerun the platform refuses records nothing and the resource keeps its failure.
     */
    public Mono<CommandAccepted> retry(Session session, String jobId, RetryFix fix, String requestId, String idempotencyKey) {
        RetryFix given = fix == null ? RetryFix.NONE : fix;
        return jobs.detail(session, jobId).flatMap(job -> {
            if (!JobRetries.retryable(job)) {
                return Mono.error(new JobNotFailedException(jobId, job.status() == null ? "unknown" : job.status().id()));
            }
            return retries.target(session, job).flatMap(target -> access.require(session, target.actionName(), target.resource())
                    .then(sessions.requireFreshFor(session, target.actionName()))
                    .then(Mono.fromRunnable(() -> checkFix(job, target, given)))
                    .then(Mono.defer(() -> retrySubject(session, target, job.status() == JobStatus.NEEDS_ATTENTION)))
                    .flatMap(subject -> retryOnPlatform(session, job, target, given, requestId, idempotencyKey)
                            .flatMap(started -> retried(session, job, target, given, subject, started, requestId, idempotencyKey))));
        });
    }

    /**
     * A retry's fix (Retry with fix): {@code input} merged over the job's input, as the platform's
     * {@code :rerun} and {@code :retry} merge it, and {@code fromStep}, the step to run again from
     * (the failed step, or the first for a rolled-back job, when left out).
     */
    public record RetryFix(Map<String, Object> input, String fromStep) {

        public static final RetryFix NONE = new RetryFix(null, null);

        public RetryFix {
            input = input == null ? Map.of() : Map.copyOf(input);
            fromStep = fromStep == null || fromStep.isBlank() ? null : fromStep;
        }

        boolean empty() {
            return input.isEmpty() && fromStep == null;
        }
    }

    /** 400 for a fix the retry cannot take: a batch's rows, a step the job does not have, a create's new name. */
    private static void checkFix(JobDetail job, JobRetries.Target target, RetryFix fix) {
        if (fix.empty()) {
            return;
        }
        if (target.batch()) {
            throw new InvalidInputException("a batch runs its failed rows again as they were",
                    List.of(Problem.error("retry.fix-unsupported", fix.input().isEmpty() ? "/fromStep" : "/input", "a batch's rows run again as they were")));
        }
        if (fix.fromStep() != null && (job.steps() == null || job.steps().stream().noneMatch(step -> fix.fromStep().equals(step.id())))) {
            throw new InvalidInputException("the job has no step " + fix.fromStep(),
                    List.of(Problem.error("step.unknown", "/fromStep", "the job has no step " + fix.fromStep())));
        }
        if (target.is("create") && fix.input().containsKey("name")) {
            Object before = job.input() == null ? null : job.input().get("name");
            if (!Objects.equals(before, fix.input().get("name"))) {
                throw new InvalidInputException("a retried create keeps its name",
                        List.of(Problem.error("retry.name-fixed", "/input/name", "the resource is named " + before + "; a retry keeps the name")));
            }
        }
    }

    /**
     * The resource a retry runs on, as the state service holds it now, checked like the first
     * request was; a paused job ({@code resume}) is still the resource's change underway, so its
     * state is the one that job left (updating, deleting, pending).
     */
    private Mono<Optional<StateResource>> retrySubject(Session session, JobRetries.Target target, boolean resume) {
        if (target.wfrn() == null || target.batch()) {
            return Mono.just(Optional.empty());
        }
        return state.get(scope(session), caller(session), target.wfrn(), null).flatMap(answer -> {
            StateResource r = answer.resource();
            if (target.is("delete")) {
                if (!resume) {
                    requireState(r, null, StateResource.ACTIVE, StateResource.FAILED);
                }
                return refuseDependents(session, r).thenReturn(Optional.of(r));
            }
            if (target.is("update") && !resume) {
                requireState(r, null, StateResource.ACTIVE, StateResource.FAILED);
            } else if (target.is("create") && !resume) {
                requireState(r, null, StateResource.FAILED);
            } else if (!target.is("create") && !r.live()) {
                throw new ConflictException("the resource is " + r.state(), Map.of("state", r.state()));
            }
            return Mono.just(Optional.of(r));
        });
    }

    private Mono<Started> retryOnPlatform(Session session, JobDetail job, JobRetries.Target target, RetryFix fix, String requestId,
            String idempotencyKey) {
        RequestOptions options = OnBehalfOf.options(trust, session, requestId, idempotencyKey);
        if (target.wfrn() != null) {
            options = options.withResourceWfrn(target.wfrn());
        }
        if (job.context() != null && job.context().labels() != null) {
            for (Map.Entry<String, String> label : job.context().labels().entrySet()) {
                options = options.withLabel(label.getKey(), label.getValue());
            }
        }
        final RequestOptions chosen = options;
        return clients.forAccount(scope(session)).flatMap(p -> {
            if (target.batch()) {
                return p.retryFailedRows(job.id(), chosen);
            }
            Map<String, Object> input = fix.input().isEmpty() ? null : fix.input();
            if (job.status() == JobStatus.NEEDS_ATTENTION) {
                return Mono.defer(() -> p.retryJob(job.id(), new RetryRequest(fix.fromStep(), input), chosen)).retryWhen(PausedJobs.NOT_YET_WAITING);
            }
            String from = fix.fromStep() != null ? fix.fromStep() : JobRetries.rerunFrom(job)
                    .orElseThrow(() -> new ConflictException("the job ran no step to run again from", Map.of("jobId", job.id())));
            return p.rerunJob(job.id(), new RerunRequest(from, input), chosen);
        }).map(accepted -> new Started(accepted.body().id() == null ? job.id() : accepted.body().id(), accepted.body().status()));
    }

    /**
     * After the platform took the retry: the new job is recorded on the state service like the
     * first request, a failed create's as {@code :request-retry} (failed back to pending, the trail
     * naming who retried), an update's or a delete's as theirs.
     */
    private Mono<CommandAccepted> retried(Session session, JobDetail job, JobRetries.Target target, RetryFix fix, Optional<StateResource> subject,
            Started started, String requestId, String idempotencyKey) {
        CommandAccepted answer = new CommandAccepted(target.wfrn(), started.jobId(), requestId,
                target.action() == null ? approvalOf(started.status()) : approval(target.action(), started.status()));
        boolean resumed = started.jobId().equals(job.id());
        boolean failedCreate = target.is("create") && subject.isPresent() && StateResource.FAILED.equals(subject.get().state());
        if (resumed || subject.isEmpty() || !(target.is("update") || target.is("delete") || failedCreate)) {
            return Mono.just(answer);
        }
        StateCommands.Meta meta = new StateCommands.Meta(idempotencyKey, requestId);
        Mono<StateResource> recorded;
        if (failedCreate) {
            recorded = state.requestRetry(scope(session), caller(session), target.wfrn(),
                    new StateCommands.Retry(null, session.user().userId(), started.jobId(), job.workflow()), meta);
        } else if (target.is("update")) {
            Map<String, Object> input = new LinkedHashMap<>(job.input() == null ? Map.of() : job.input());
            input.putAll(fix.input());
            recorded = state.requestUpdate(scope(session), caller(session), target.wfrn(), new StateCommands.Update(null, session.user().userId(),
                    started.jobId(), job.workflow(), job.version(), json.valueToTree(changesOf(target.action(), input))), meta);
        } else {
            String reason = job.context() != null && job.context().labels() != null ? job.context().labels().get("reason") : null;
            recorded = state.requestDelete(scope(session), caller(session), target.wfrn(), new StateCommands.Delete(null, session.user().userId(),
                    started.jobId(), job.workflow(), job.version(), reason), meta);
        }
        return recorded.onErrorResume(ConflictException.class, e -> {
            log.warn("retry job {} runs on {} but the state service refused to record it: {}", started.jobId(), target.wfrn(), e.getMessage());
            return Mono.just(subject.get());
        }).onErrorResume(GoneException.class, e -> Mono.just(subject.get())).thenReturn(answer);
    }

    private static Boolean approvalOf(String status) {
        return "waiting-approval".equals(status) || "waiting_approval".equals(status);
    }

    // ---- the pieces

    private Mono<Subject> subject(Session session, String type, String wfrn) {
        return types.byType(session, type).flatMap(t -> {
            checkName(session, t, wfrn);
            Mono<StateAnswer> read = state.get(scope(session), caller(session), wfrn, null);
            return read.map(answer -> new Subject(t, answer.resource(), answer.etag()));
        });
    }

    /** The resource for a read of one of its pages, as {@code <service>:get} on its wfrn allows. */
    private Mono<Subject> readable(Session session, String type, String wfrn) {
        return types.byType(session, type).flatMap(t -> {
            checkName(session, t, wfrn);
            return access.require(session, AccessCheck.action(t.service(), GET_VERB), wfrn)
                    .then(Mono.defer(() -> state.get(scope(session), caller(session), wfrn, null)))
                    .map(answer -> new Subject(t, answer.resource(), answer.etag()));
        });
    }

    private String typePattern(Session session, ConsoleType t) {
        return AccessCheck.typePattern(platform.partition(), t.service(), session.region(), session.accountId(), t.type());
    }

    /** The wfrn is the path's type, in the session's account and region; anything else is a 404, never a hint. */
    private static void checkName(Session session, ConsoleType type, String wfrn) {
        Wfrn parsed;
        try {
            parsed = Wfrn.parse(wfrn);
        } catch (WfrnException e) {
            throw NotFoundException.of("resource", wfrn);
        }
        if (!parsed.type().equals(type.type()) || !parsed.in(session.accountId(), session.region())) {
            throw NotFoundException.of("resource", wfrn);
        }
    }

    private static ConsoleAction actionOf(ConsoleType type, String verb) {
        return type.actions().stream().filter(a -> a.verb().equals(verb)).findFirst()
                .orElseThrow(() -> NotFoundException.of("action", type.type() + ":" + verb));
    }

    /** The policy, then the step-up, then the confirmation a destructive verb needs. */
    private Mono<Void> guard(Session session, String actionName, String wfrn, ConsoleAction action, CommandInput body) {
        return access.require(session, actionName, wfrn)
                .then(sessions.requireFreshFor(session, actionName))
                .then(Mono.fromRunnable(() -> {
                    if (action.destructive() && !body.confirmed()) {
                        throw confirmRequired(action);
                    }
                }));
    }

    private static InvalidInputException confirmRequired(ConsoleAction action) {
        return new InvalidInputException("confirm " + action.verb() + ": it is destructive",
                List.of(Problem.error("confirm.required", "/confirm", "set confirm to true to run " + action.verb())));
    }

    /** A resource page's ETag: the state version, then a digest of everything the page shows. */
    private String pageETag(Long version, ResourceView view) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(view));
            return "\"" + version + "." + java.util.HexFormat.of().formatHex(digest, 0, 8) + "\"";
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("the resource page's ETag could not be computed", e);
        }
    }

    private static void requireState(StateResource r, String ifMatch, String... allowed) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            // a page's ETag is "<version>.<digest>"; only the version is compared
            String version = ifMatch.replace("\"", "").replace("W/", "").trim().split("\\.", 2)[0];
            if (!version.equals(String.valueOf(r.version()))) {
                throw new ConflictException("the resource moved on to version " + r.version(), Map.of("version", r.version()));
            }
        }
        for (String s : allowed) {
            if (r.is(s)) {
                return;
            }
        }
        throw new ConflictException("the resource is " + r.state(), Map.of("state", r.state()));
    }

    private static void requireFields(ConsoleAction action, Map<String, Object> input) {
        JsonNode required = action.input() == null ? null : action.input().get("required");
        if (required == null || !required.isArray()) {
            return;
        }
        List<Problem> problems = new ArrayList<>();
        for (JsonNode field : required) {
            String name = field.asText();
            if (action.subjectInput() != null && action.subjectInput().equals(name)) {
                continue;
            }
            if (input.get(name) == null) {
                problems.add(Problem.error("required", "/" + name, name + " is required"));
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("the form misses required fields", problems);
        }
    }

    /** The form's values, with the subject's registry id where the workflow wants it and the reason where the input has a place for it. */
    private static Map<String, Object> inputFor(ConsoleAction action, CatalogEntry entry, CommandInput body, StateResource subject) {
        Map<String, Object> input = new LinkedHashMap<>(body.input());
        if (subject != null && entry.subjectInput() != null && subject.registryId() != null) {
            input.putIfAbsent(entry.subjectInput(), subject.registryId());
        }
        if (body.reason() != null && !body.reason().isBlank() && action.input() != null && action.input().path("properties").has("reason")) {
            input.putIfAbsent("reason", body.reason());
        }
        return input;
    }

    /** What an update asks to change: the form's values without the subject. */
    private static Map<String, Object> changesOf(ConsoleAction action, Map<String, Object> input) {
        Map<String, Object> changes = new LinkedHashMap<>(input);
        if (action.subjectInput() != null) {
            changes.remove(action.subjectInput());
        }
        return changes;
    }

    private static Map<String, String> pathParams(CatalogEntry entry, StateResource subject) {
        Map<String, String> params = new HashMap<>();
        Matcher m = PATH_PARAM.matcher(entry.path() == null ? "" : entry.path());
        while (m.find()) {
            if (subject.registryId() == null) {
                throw new ConflictException("the platform has not registered " + subject.wfrn() + " yet; wait for it to become active",
                        Map.of("state", subject.state()));
            }
            params.put(m.group(1), subject.registryId());
        }
        return params;
    }

    private static String nameOf(Map<String, Object> input) {
        Object name = input.get("name");
        return name == null ? null : name.toString();
    }

    private RequestOptions options(Session session, String requestId, String idempotencyKey, String wfrn, CommandInput body) {
        RequestOptions options = OnBehalfOf.options(trust, session, requestId, idempotencyKey);
        if (wfrn != null) {
            options = options.withResourceWfrn(wfrn);
        }
        for (Map.Entry<String, String> label : body.labels().entrySet()) {
            options = options.withLabel(label.getKey(), label.getValue());
        }
        if (body.reason() != null && !body.reason().isBlank() && !body.labels().containsKey("reason")) {
            options = options.withLabel("reason", body.reason());
        }
        return options;
    }

    private record Started(String jobId, String status) {
    }

    private Mono<Started> start(Session session, CatalogEntry entry, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options) {
        return clients.forAccount(scope(session)).flatMap(p -> p.start(entry, pathParams, input, options))
                .map(accepted -> new Started(accepted.body().jobId(), accepted.body().status()));
    }

    private Mono<PreviewView> preview(Session session, CatalogEntry entry, Map<String, String> pathParams, Map<String, Object> input,
            RequestOptions options, Map<String, String> wfrnsByRegistryId) {
        if (!entry.hasPreview()) {
            return Mono.error(new NotFoundException("the workflow " + entry.workflow() + " publishes no preview"));
        }
        return clients.forAccount(scope(session)).flatMap(p -> p.preview(entry, pathParams, input, options))
                .map(preview -> PreviewView.of(preview, wfrnsByRegistryId));
    }

    private static Boolean approval(ConsoleAction action, String status) {
        return action.approval() || "waiting-approval".equals(status) || "waiting_approval".equals(status);
    }

    /** The verbs the user may run on this resource, from the account's policies, plus {@code tag}. */
    private Mono<List<String>> can(Session session, ConsoleType type, StateResource r) {
        if (!r.live()) {
            return Mono.just(List.of());
        }
        List<String> verbs = new ArrayList<>();
        List<AccessCheck> checks = new ArrayList<>();
        for (ConsoleAction action : type.actions()) {
            if ("create".equals(action.verb())) {
                continue;
            }
            verbs.add(action.verb());
            checks.add(new AccessCheck(AccessCheck.action(type.service(), action.verb()), r.wfrn()));
        }
        verbs.add(TAG_VERB);
        checks.add(new AccessCheck(AccessCheck.action(type.service(), TAG_VERB), r.wfrn()));
        return access.decide(session, checks).map(decisions -> {
            List<String> can = new ArrayList<>();
            for (int i = 0; i < verbs.size(); i++) {
                Decision decision = decisions.get(i);
                if (decision.allowed()) {
                    can.add(verbs.get(i));
                }
            }
            return can;
        });
    }

    private static List<String> findingCan(List<String> can) {
        return can.contains("update") ? List.of("revert", "accept", "ignore") : List.of();
    }

    private Mono<List<JobView>> recentJobs(Session session, StateResource r) {
        JobFilter filter = JobFilter.ofAccount(session.accountId()).withRegion(session.region()).withWfrn(r.wfrn()).withLimit(RECENT_JOBS);
        return clients.forAccount(scope(session)).flatMap(p -> p.listJobs(filter))
                .map(page -> {
                    List<JobView> jobs = new ArrayList<>();
                    for (JobSummary job : page.items()) {
                        if (job.context() != null && session.accountId().equals(job.context().accountId())) {
                            jobs.add(JobView.summary(job));
                        }
                    }
                    return jobs;
                })
                .onErrorResume(e -> {
                    log.warn("the jobs of {} are unavailable: {}", r.wfrn(), e.getMessage());
                    return Mono.just(List.of());
                });
    }

    private Mono<Optional<Lineage>> lineageOf(Session session, StateResource r) {
        if (r.registryId() == null) {
            return Mono.just(Optional.empty());
        }
        return clients.forAccount(scope(session)).flatMap(p -> p.getResourceLineage(r.registryId()))
                .map(Optional::of)
                .onErrorResume(PlatformException.class, e -> {
                    if (!e.isNotFound()) {
                        log.warn("the lineage of {} is unavailable: {}", r.wfrn(), e.getMessage());
                    }
                    return Mono.just(Optional.empty());
                })
                .onErrorResume(e -> {
                    log.warn("the lineage of {} is unavailable: {}", r.wfrn(), e.getMessage());
                    return Mono.just(Optional.empty());
                });
    }

    private static LineageView lineageView(StateResource subject, Lineage lineage) {
        Map<String, String> wfrns = new HashMap<>();
        List<ResourceRef> nodes = new ArrayList<>();
        List<Resource> all = new ArrayList<>();
        if (lineage.nodes() != null) {
            all.addAll(lineage.nodes());
        }
        if (lineage.resource() != null && all.stream().noneMatch(n -> n.id() != null && n.id().equals(lineage.resource().id()))) {
            all.add(lineage.resource());
        }
        for (Resource node : all) {
            ResourceRef ref = ref(node);
            if (node.id() != null && subject.registryId() != null && node.id().equals(subject.registryId())) {
                ref = new ResourceRef(subject.wfrn(), subject.type(), subject.name() != null ? subject.name() : ref.name(), subject.state(), node.id());
            }
            if (ref.wfrn() != null && node.id() != null) {
                wfrns.put(node.id(), ref.wfrn());
            }
            nodes.add(ref);
        }
        List<LineageView.Edge> edges = new ArrayList<>();
        if (lineage.edges() != null) {
            for (Lineage.Edge edge : lineage.edges()) {
                edges.add(new LineageView.Edge(wfrns.getOrDefault(edge.from(), edge.from()), wfrns.getOrDefault(edge.to(), edge.to()), edge.via()));
            }
        }
        if (nodes.stream().noneMatch(n -> subject.wfrn().equals(n.wfrn()))) {
            nodes.add(0, ResourceRef.of(subject));
        }
        return new LineageView(ResourceRef.of(subject), nodes, edges);
    }

    private static List<ResourceRef> refs(List<Resource> resources) {
        return resources == null ? List.of() : resources.stream().map(Resources::ref).toList();
    }

    private static ResourceRef ref(Resource resource) {
        return PlatformStates.ref(resource);
    }

    private static AccountScope scope(Session session) {
        return CurrentSession.scope(session);
    }

    private static StateCaller caller(Session session) {
        return new StateCaller(session.user().userId(), session.currentAccount().groups(), session.currentAccount().roles(), session.rolesUntil());
    }
}
