package com.iaf.console.bff.resourcegroups.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resourcegroups.domain.CreateKey;
import com.iaf.console.bff.resourcegroups.domain.CreateKeyReusedException;
import com.iaf.console.bff.resourcegroups.domain.GroupChanges;
import com.iaf.console.bff.resourcegroups.domain.GroupNameTakenException;
import com.iaf.console.bff.resourcegroups.domain.GroupQuery;
import com.iaf.console.bff.resourcegroups.domain.NewGroup;
import com.iaf.console.bff.resourcegroups.domain.ResourceGroup;
import com.iaf.console.bff.resourcegroups.ports.outbound.ResourceGroupStore;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.resources.domain.ResourceView;
import com.iaf.console.bff.resources.domain.StateQuery;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * Resource groups (the AWS Resource Groups analog): tag queries the console keeps per account and
 * region, and the resources each one matches, read from the state service as the resource lists
 * read them. The account's policies decide every call as {@code resource-groups:<verb>}: list and
 * create on the groups' pattern ({@code wfrn:<partition>:resource-groups:<region>:<account>:group/*}),
 * the others on the group's wfrn; a create, a change or a delete also needs the fresh sign-in the
 * step-up policy asks of its action. A group of another account or region is a 404, never a hint,
 * and a group's resources leave out the rows of the types the person may not list.
 */
public class ResourceGroups {

    static final String LIST = "list";
    static final String GET = "get";
    static final String CREATE = "create";
    static final String UPDATE = "update";
    static final String DELETE = "delete";
    /** A list's order: by name, case aside, then exactly. */
    static final Comparator<ResourceGroup> BY_NAME = Comparator.comparing(ResourceGroup::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ResourceGroup::name);

    private final ResourceGroupStore store;
    private final Types types;
    private final Access access;
    private final Sessions sessions;
    private final Resources resources;
    private final PlatformProperties platform;
    private final Clock clock;

    public ResourceGroups(ResourceGroupStore store, Types types, Access access, Sessions sessions, Resources resources, PlatformProperties platform,
            Clock clock) {
        this.store = store;
        this.types = types;
        this.access = access;
        this.sessions = sessions;
        this.resources = resources;
        this.platform = platform;
        this.clock = clock;
    }

    /** A create's answer: the group, and whether an earlier create with the same Idempotency-Key made it. */
    public record Created(ResourceGroup group, boolean replayed) {
    }

    /** Every group of the session's account and region, by name, as {@code resource-groups:list} allows. */
    public Mono<List<ResourceGroup>> list(Session session) {
        return access.require(session, action(LIST), pattern(session))
                .then(Mono.defer(() -> store.list(scope(session))))
                .map(groups -> groups.stream().sorted(BY_NAME).toList());
    }

    /**
     * A new group, as {@code resource-groups:create} allows. With an Idempotency-Key, a create the
     * same person repeats within a day answers the group the first one made ({@code replayed}); a
     * repeat with another body is a 409.
     */
    public Mono<Created> create(Session session, NewGroup body, String idempotencyKey) {
        String actionName = action(CREATE);
        return access.require(session, actionName, pattern(session))
                .then(sessions.requireFreshFor(session, actionName))
                .then(Mono.defer(() -> known(session, body.query())))
                .flatMap(known -> {
                    if (idempotencyKey != null && idempotencyKey.strip().length() > CreateKey.MAX_LENGTH) {
                        String sentence = "the Idempotency-Key is at most " + CreateKey.MAX_LENGTH + " characters";
                        return Mono.error(new InvalidInputException(sentence, List.of(Problem.error("length", null, sentence))));
                    }
                    List<Problem> problems = body.problems(known);
                    if (!problems.isEmpty()) {
                        return Mono.error(ResourceGroup.invalid(problems));
                    }
                    Instant now = clock.instant();
                    AccountScope scope = scope(session);
                    ResourceGroup group = ResourceGroup.of(platform.partition(), session.accountId(), session.region(), session.user().userId(), body, now);
                    CreateKey key = CreateKey.of(idempotencyKey, scope.principal(), body.fingerprint(), now);
                    Mono<Created> stored = Mono.defer(() -> store.create(scope, group, key)).map(g -> new Created(g, false));
                    if (key == null) {
                        return stored;
                    }
                    // a repeat that races the first create loses on the name, and then finds the first group by its key
                    return replay(scope, key, now)
                            .switchIfEmpty(stored.onErrorResume(GroupNameTakenException.class, taken -> replay(scope, key, now).switchIfEmpty(Mono.error(taken))));
                });
    }

    /** One group, as {@code resource-groups:get} on its wfrn allows; 404 when the session's account and region hold none of that id. */
    public Mono<ResourceGroup> get(Session session, String groupId) {
        return guarded(session, groupId, GET, false);
    }

    /**
     * Changes a group's description or query, as {@code resource-groups:update} on its wfrn allows.
     * {@code If-Match} names the version the change was made against (409 when the group moved on);
     * a change that changes nothing keeps the version.
     */
    public Mono<ResourceGroup> update(Session session, String groupId, GroupChanges changes, String ifMatch) {
        return guarded(session, groupId, UPDATE, true).flatMap(current -> {
            requireVersion(current, ifMatch);
            return known(session, changes.query()).flatMap(known -> {
                List<Problem> problems = changes.problems(current, known);
                if (!problems.isEmpty()) {
                    return Mono.error(ResourceGroup.invalid(problems));
                }
                ResourceGroup next = current.with(changes, clock.instant());
                if (next == current) {
                    return Mono.just(current);
                }
                AccountScope scope = scope(session);
                // nothing at the version read: another change came first, or a delete
                return store.update(scope, next, current.version())
                        .switchIfEmpty(Mono.defer(() -> store.get(scope, groupId))
                                .flatMap(moved -> Mono.<ResourceGroup>error(movedOn(moved)))
                                .switchIfEmpty(Mono.error(() -> notFound(groupId))));
            });
        });
    }

    /** Deletes a group, as {@code resource-groups:delete} on its wfrn allows. */
    public Mono<Void> delete(Session session, String groupId) {
        return Mono.defer(() -> {
            String actionName = action(DELETE);
            return access.require(session, actionName, wfrnOf(session, groupId))
                    .then(sessions.requireFreshFor(session, actionName))
                    .then(Mono.defer(() -> store.delete(scope(session), groupId)))
                    .flatMap(deleted -> deleted ? Mono.<Void>empty() : Mono.<Void>error(notFound(groupId)));
        });
    }

    /**
     * One page of the group's resources, after {@code resource-groups:get} on the group: those of
     * its types (every type when it names none) in the session's account and region that carry
     * every tag of its query, as the state service lists them. One type goes to the state service
     * as its filter; with several or none the page is read without one and keeps the group's
     * types. Rows of a type the person may not list ({@code <service>:list}) are left out, so a page
     * can hold fewer rows than {@code limit} while {@code next} goes on.
     */
    public Mono<Page<ResourceView>> resources(Session session, String groupId, Integer limit, String after) {
        return Mono.defer(() -> {
            int size = CursorPaging.pageSize(limit);
            return get(session, groupId).flatMap(group -> Mono.defer(() -> types.list(session))
                    .flatMap(catalogue -> listable(session, catalogue, group.query().types()))
                    .flatMap(listable -> {
                        if (listable.isEmpty()) {
                            return Mono.just(new Page<ResourceView>(List.of(), null));
                        }
                        List<String> named = group.query().types();
                        StateQuery query = new StateQuery(named.size() == 1 ? named.get(0) : null, null, null, group.query().tags(), null, null, null,
                                size, after);
                        return resources.rows(session, query)
                                .map(page -> new Page<>(page.items().stream().filter(row -> listable.contains(row.type())).toList(), page.next()));
                    }));
        });
    }

    // ---- the pieces

    /** The group, as {@code resource-groups:<verb>} on its wfrn allows, and for a change after the step-up its action asks for. */
    private Mono<ResourceGroup> guarded(Session session, String groupId, String verb, boolean command) {
        return Mono.defer(() -> {
            String actionName = action(verb);
            Mono<Void> allowed = access.require(session, actionName, wfrnOf(session, groupId));
            if (command) {
                allowed = allowed.then(sessions.requireFreshFor(session, actionName));
            }
            return allowed.then(Mono.defer(() -> store.get(scope(session), groupId))).switchIfEmpty(Mono.error(() -> notFound(groupId)));
        });
    }

    /** The types of the catalogue the person may list ({@code <service>:list} on the type's pattern), of those named, or of all when none is. */
    private Mono<Set<String>> listable(Session session, List<ConsoleType> catalogue, List<String> named) {
        List<ConsoleType> candidates = catalogue.stream().filter(t -> named.isEmpty() || named.contains(t.type())).toList();
        if (candidates.isEmpty()) {
            return Mono.just(Set.of());
        }
        List<AccessCheck> checks = candidates.stream().map(t -> new AccessCheck(AccessCheck.action(t.service(), LIST),
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

    /** The type names of the region's catalogue, read only when a query names a type. */
    private Mono<Set<String>> known(Session session, GroupQuery query) {
        if (query == null || query.types().isEmpty()) {
            return Mono.just(Set.of());
        }
        return Mono.defer(() -> types.list(session)).map(all -> all.stream().map(ConsoleType::type).collect(Collectors.toUnmodifiableSet()));
    }

    /** The group an earlier create with the same key made, while the key answers; a 409 when that create had another body. */
    private Mono<Created> replay(AccountScope scope, CreateKey key, Instant now) {
        return Mono.defer(() -> store.findCreated(scope, key.key()))
                .filter(found -> found.key().answers(now))
                .flatMap(found -> found.key().fingerprint().equals(key.fingerprint()) ? Mono.just(new Created(found.group(), true))
                        : Mono.<Created>error(new CreateKeyReusedException()));
    }

    /** Refuses a change made against another version than the group's; no {@code If-Match}, or {@code *}, takes any. */
    static void requireVersion(ResourceGroup group, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return;
        }
        for (String candidate : ifMatch.split(",")) {
            String tag = candidate.strip();
            tag = (tag.startsWith("W/") ? tag.substring(2) : tag).replace("\"", "").strip();
            if (tag.equals("*") || tag.equals(String.valueOf(group.version()))) {
                return;
            }
        }
        throw movedOn(group);
    }

    private static ConflictException movedOn(ResourceGroup group) {
        return new ConflictException("the group moved on to version " + group.version(), Map.of("version", group.version()));
    }

    /** The groups' pattern in the session's account and region. */
    private String pattern(Session session) {
        return AccessCheck.typePattern(platform.partition(), ResourceGroup.SERVICE, session.region(), session.accountId(), ResourceGroup.TYPE);
    }

    /** The wfrn a group id names in the session's account and region; a 404 for what is no group id. */
    private String wfrnOf(Session session, String groupId) {
        if (!ResourceGroup.isGroupId(groupId)) {
            throw notFound(groupId);
        }
        return ResourceGroup.wfrnOf(platform.partition(), session.region(), session.accountId(), groupId);
    }

    private static String action(String verb) {
        return AccessCheck.action(ResourceGroup.SERVICE, verb);
    }

    private static NotFoundException notFound(String groupId) {
        return NotFoundException.of("resource group", groupId);
    }

    private static AccountScope scope(Session session) {
        return CurrentSession.scope(session);
    }
}
