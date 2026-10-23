package com.iaf.console.bff.tags.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.resources.domain.GoneException;
import com.iaf.console.bff.resources.domain.ResourceView;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.resources.domain.StateCommands;
import com.iaf.console.bff.resources.domain.StateQuery;
import com.iaf.console.bff.resources.ports.outbound.StatePort;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.tags.domain.RequiredTag;
import com.iaf.console.bff.tags.domain.TagChange;
import com.iaf.console.bff.tags.domain.TagChangeResult;
import com.iaf.console.bff.tags.domain.TagEditorRow;
import com.iaf.console.bff.tags.domain.TagPolicy;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.wfrn.Wfrn;
import com.iaf.console.wfrn.WfrnException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Tag Editor (WP 4.3), as AWS's: find the account's resources across types by their tags, in
 * the session's account and region, each row saying whether the person may change its tags; and
 * change the tags of up to {@value TagChange#MAX_RESOURCES} resources at once, each as
 * {@code <service>:tag} on it allows, the required tags kept (none removed, none given a value it
 * may not take).
 */
public class TagEditor {

    private static final Logger log = LoggerFactory.getLogger(TagEditor.class);
    private static final String TAG_NAME = "[a-z0-9-]{1,32}";
    private static final int TAG_CONCURRENCY = 8;

    private final Types types;
    private final Access access;
    private final Resources resources;
    private final StatePort state;
    private final TagPolicies policies;
    private final PlatformProperties platform;

    public TagEditor(Types types, Access access, Resources resources, StatePort state, TagPolicies policies, PlatformProperties platform) {
        this.types = types;
        this.access = access;
        this.resources = resources;
        this.state = state;
        this.policies = policies;
        this.platform = platform;
    }

    /**
     * One page of the resources of these types (every type when none is named) that carry every
     * one of these tags, as the state service orders them. A type the person may not list
     * ({@code <service>:list}) is left out, so a page can hold fewer rows than {@code limit} while
     * {@code next} goes on; an unknown type is a 400 {@code type.unknown}.
     */
    public Mono<Page<TagEditorRow>> find(Session session, List<String> named, Map<String, String> tags, Integer limit, String after) {
        int size = CursorPaging.pageSize(limit);
        List<String> wanted = named == null ? List.of() : List.copyOf(new LinkedHashSet<>(named));
        return types.list(session).flatMap(catalogue -> {
            Set<String> known = new HashSet<>();
            catalogue.forEach(t -> known.add(t.type()));
            List<Problem> problems = new ArrayList<>();
            for (int i = 0; i < wanted.size(); i++) {
                if (!known.contains(wanted.get(i))) {
                    problems.add(Problem.error("type.unknown", "/types/" + i, "no type '" + wanted.get(i) + "' in this region"));
                }
            }
            if (!problems.isEmpty()) {
                return Mono.error(new InvalidInputException(problems.get(0).message(), problems));
            }
            return listable(session, catalogue, wanted).flatMap(listable -> {
                if (listable.isEmpty()) {
                    return Mono.just(new Page<TagEditorRow>(List.of(), null));
                }
                StateQuery query = new StateQuery(wanted.size() == 1 ? wanted.get(0) : null, null, null, tags, null, null, null, size, after);
                return resources.rows(session, query).flatMap(page -> {
                    List<ResourceView> rows = page.items().stream().filter(r -> listable.contains(r.type())).toList();
                    List<AccessCheck> checks = rows.stream().map(r -> new AccessCheck(AccessCheck.action(r.service(), Resources.TAG_VERB), r.wfrn())).toList();
                    return access.decide(session, checks).map(decisions -> {
                        List<TagEditorRow> out = new ArrayList<>(rows.size());
                        for (int i = 0; i < rows.size(); i++) {
                            out.add(new TagEditorRow(rows.get(i), decisions.get(i).allowed()));
                        }
                        return new Page<>(out, page.next());
                    });
                });
            });
        });
    }

    /**
     * Sets and removes tags on each resource, each as its own state service command, as
     * {@code <service>:tag} on it allows; what the body gets wrong is a 400 before anything
     * changes, what a resource refuses is named in {@code failed}.
     */
    public Mono<TagChangeResult> apply(Session session, TagChange change, String requestId, String idempotencyKey) {
        return policies.forAccount(session.accountId()).flatMap(policy -> {
            List<Problem> problems = problems(change, policy);
            if (!problems.isEmpty()) {
                return Mono.error(new InvalidInputException(problems.size() == 1 ? problems.get(0).message()
                        : "the change has " + problems.size() + " problems", problems));
            }
            List<String> wfrns = List.copyOf(new LinkedHashSet<>(change.resources()));
            List<TagChangeResult.Failed> failed = new ArrayList<>();
            List<String> named = new ArrayList<>();
            List<AccessCheck> checks = new ArrayList<>();
            for (String wfrn : wfrns) {
                Wfrn parsed = parse(wfrn);
                if (parsed == null || !parsed.in(session.accountId(), session.region())) {
                    failed.add(new TagChangeResult.Failed(wfrn, "not-found", "no resource " + wfrn + " in this account and region", null));
                    continue;
                }
                named.add(wfrn);
                checks.add(new AccessCheck(AccessCheck.action(parsed.service(), Resources.TAG_VERB), wfrn));
            }
            return access.decide(session, checks).flatMap(decisions -> {
                List<String> allowed = new ArrayList<>();
                for (int i = 0; i < named.size(); i++) {
                    Decision decision = decisions.get(i);
                    if (decision.allowed()) {
                        allowed.add(named.get(i));
                    } else {
                        failed.add(new TagChangeResult.Failed(named.get(i), "denied", decision.denial() == null ? "denied" : decision.denial().reason(),
                                decision.denial()));
                    }
                }
                return Flux.fromIterable(allowed).flatMapSequential(wfrn -> tag(session, wfrn, change, requestId, idempotencyKey), TAG_CONCURRENCY)
                        .collectList()
                        .map(outcomes -> {
                            List<TagChangeResult.Applied> applied = new ArrayList<>();
                            for (Outcome o : outcomes) {
                                if (o.applied() != null) {
                                    applied.add(o.applied());
                                } else {
                                    failed.add(o.failed());
                                }
                            }
                            List<TagChangeResult.Failed> ordered = failed.stream()
                                    .sorted((a, b) -> Integer.compare(wfrns.indexOf(a.wfrn()), wfrns.indexOf(b.wfrn()))).toList();
                            return new TagChangeResult(applied, ordered);
                        });
            });
        });
    }

    private record Outcome(TagChangeResult.Applied applied, TagChangeResult.Failed failed) {
    }

    private Mono<Outcome> tag(Session session, String wfrn, TagChange change, String requestId, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey + ":" + wfrn;
        StateCommands.Tag command = new StateCommands.Tag(null, session.user().userId(), change.set(), change.remove());
        StateCaller caller = new StateCaller(session.user().userId(), session.currentAccount().groups(), session.currentAccount().roles());
        return state.tag(CurrentSession.scope(session), caller, wfrn, command, new StateCommands.Meta(key, requestId))
                .map(r -> new Outcome(new TagChangeResult.Applied(wfrn, r.tags()), null))
                .onErrorResume(NotFoundException.class, e -> Mono.just(failed(wfrn, "not-found", "no resource " + wfrn + " in this account and region")))
                .onErrorResume(GoneException.class, e -> Mono.just(failed(wfrn, "gone", "the resource was deleted")))
                .onErrorResume(ConflictException.class, e -> Mono.just(failed(wfrn, "conflict", e.getMessage())))
                .onErrorResume(InvalidInputException.class, e -> Mono.just(failed(wfrn, "invalid", e.getMessage())))
                .onErrorResume(e -> !(e instanceof Error), e -> {
                    log.warn("tagging {} failed: {}", wfrn, e.toString());
                    return Mono.just(failed(wfrn, "error", e.getMessage()));
                });
    }

    private static Outcome failed(String wfrn, String error, String message) {
        return new Outcome(null, new TagChangeResult.Failed(wfrn, error, message, null));
    }

    /** What the body gets wrong, whatever the resources: their number, the tags' names and values, the required tags' rules. */
    private static List<Problem> problems(TagChange change, TagPolicy policy) {
        List<Problem> problems = new ArrayList<>();
        if (change.resources().isEmpty()) {
            problems.add(Problem.error("required", "/resources", "at least one resource"));
        } else if (change.resources().size() > TagChange.MAX_RESOURCES) {
            problems.add(Problem.error("tags.too-many-resources", "/resources",
                    "at most " + TagChange.MAX_RESOURCES + " resources at once, not " + change.resources().size()));
        }
        if (change.set().isEmpty() && change.remove().isEmpty()) {
            problems.add(Problem.error("required", "/set", "a tag to set or one to remove"));
        }
        change.set().forEach((name, value) -> {
            if (!name.matches(TAG_NAME)) {
                problems.add(Problem.error("tag.name", "/set/" + name, "a tag name is " + TAG_NAME));
            } else if (value == null || value.length() > 256) {
                problems.add(Problem.error("tag.value", "/set/" + name, "a tag value has at most 256 characters"));
            } else if (change.remove().contains(name)) {
                problems.add(Problem.error("tags.conflict", "/set/" + name, name + " is both set and removed"));
            }
        });
        for (RequiredTag tag : policy.required()) {
            String value = change.set().get(tag.key());
            if (value != null && !tag.accepts(value)) {
                problems.add(Problem.error(TagPolicy.INVALID, "/set/" + tag.key(), tag.allowedSentence()));
            }
            int at = change.remove().indexOf(tag.key());
            if (at >= 0) {
                problems.add(Problem.error(TagPolicy.REQUIRED, "/remove/" + at, tag.title() + " is required and stays on the resources"));
            }
        }
        return problems;
    }

    /** The types of the catalogue the person may list, of those named, or of all when none is. */
    private Mono<Set<String>> listable(Session session, List<ConsoleType> catalogue, List<String> named) {
        List<ConsoleType> candidates = catalogue.stream().filter(t -> named.isEmpty() || named.contains(t.type())).toList();
        if (candidates.isEmpty()) {
            return Mono.just(Set.of());
        }
        List<AccessCheck> checks = candidates.stream().map(t -> new AccessCheck(AccessCheck.action(t.service(), "list"),
                AccessCheck.typePattern(platform.partition(), t.service(), session.region(), session.accountId(), t.type()))).toList();
        return access.decide(session, checks).map(decisions -> {
            Set<String> allowed = new HashSet<>();
            for (int i = 0; i < candidates.size(); i++) {
                if (decisions.get(i).allowed()) {
                    allowed.add(candidates.get(i).type());
                }
            }
            return Set.copyOf(allowed);
        });
    }

    private static Wfrn parse(String wfrn) {
        try {
            return wfrn == null ? null : Wfrn.parse(wfrn);
        } catch (WfrnException e) {
            return null;
        }
    }
}
