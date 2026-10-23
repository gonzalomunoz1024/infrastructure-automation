package com.iaf.console.bff.assistant.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.assistant.domain.AssistantAsk;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Account;
import com.iaf.console.bff.assistant.domain.AssistantRequest.HistoryTurn;
import com.iaf.console.bff.assistant.domain.AssistantRequest.ResourceInfo;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Subject;
import com.iaf.console.bff.assistant.domain.AssistantRequest.TypeInfo;
import com.iaf.console.bff.assistant.domain.AssistantRequest.VerbInfo;
import com.iaf.console.bff.assistant.domain.AssistantTurn;
import com.iaf.console.bff.assistant.domain.Conversation;
import com.iaf.console.bff.assistant.domain.InputForm;
import com.iaf.console.bff.assistant.domain.PlainText;
import com.iaf.console.bff.cost.application.Costs;
import com.iaf.console.bff.cost.domain.Money;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.resources.domain.ActivityEntry;
import com.iaf.console.bff.resources.domain.BlastRadius;
import com.iaf.console.bff.resources.domain.ResourceView;
import com.iaf.console.bff.resources.domain.StateQuery;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleAction;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What a turn's assistant gets, read with the person's own session and nothing more: the types
 * the person may list or create (the descriptors' greying, and {@code <service>:list} on each
 * type's pattern), the resources the question names (exact names, of types the person may list),
 * and for {@code explain} the resource's facts: its detail, who created it and when and its last
 * change from its activity, its dependents from the blast radius, its drift from the open
 * finding, and its monthly cost when the cost service answers one. Nothing here changes anything.
 */
public class ContextGatherer {

    private static final Logger log = LoggerFactory.getLogger(ContextGatherer.class);

    static final String LIST = "list";
    static final String CREATE = "create";
    /** Earlier turns sent with a question. */
    static final int HISTORY = 10;
    static final int HISTORY_TEXT = 1000;
    /** The most names of a question looked up, and resources sent per name. */
    static final int NAMES = 5;
    static final int PER_NAME = 5;
    /** The activity read for an explanation: at most this many pages of 200. */
    static final int ACTIVITY_PAGES = 5;
    static final int NAMED_DEPENDENTS = 10;
    /** The events that are no change: an observation of what runs. */
    static final Set<String> NO_CHANGE = Set.of("Observed");
    /** The events that bring a resource into the console. */
    static final Set<String> ORIGINS = Set.of("ResourceRequested", "Imported");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*[A-Za-z0-9]|[A-Za-z0-9]");
    private static final Pattern NAME_LIKE = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,62}$");
    private static final Set<String> NAMING = Set.of("called", "named", "name");

    private final Types types;
    private final Access access;
    private final Resources resources;
    private final Costs costs;
    private final PlatformProperties platform;

    public ContextGatherer(Types types, Access access, Resources resources, Costs costs, PlatformProperties platform) {
        this.types = types;
        this.access = access;
        this.resources = resources;
        this.costs = costs;
        this.platform = platform;
    }

    /**
     * The request for one turn of an {@code ask} or {@code explain}: {@code detail} is the
     * resource the turn explains, read when the turn was accepted, else null.
     */
    public Mono<AssistantRequest> gather(Session session, AssistantAsk ask, Conversation conversation, String personTurnId, String turnId,
            Resources.Detail detail) {
        List<HistoryTurn> history = history(conversation, personTurnId);
        return typesOf(session).flatMap(known -> {
            Mono<List<ResourceInfo>> named = ask.message() == null ? Mono.just(List.of()) : named(session, ask.message(), known);
            Mono<Optional<Subject>> subject = detail == null || detail.view() == null ? Mono.just(Optional.empty())
                    : subject(session, detail.view()).map(Optional::of);
            return Mono.zip(named, subject).map(t -> new AssistantRequest(conversation.conversationId(), turnId, ask.intent(), ask.message(), ask.context(),
                    account(session), known, t.getT1(), t.getT2().orElse(null), null, history));
        });
    }

    /** The session's account and region with the person's roles there. */
    static Account account(Session session) {
        return new Account(session.accountId(), session.currentAccount().name(), session.region(), session.currentAccount().roles());
    }

    /**
     * The types of the region's catalogue the person may list, create or run a verb of, each with
     * its create form and its verbs (forms without their secret fields).
     */
    public Mono<List<TypeInfo>> typesOf(Session session) {
        return types.list(session).flatMap(all -> {
            if (all.isEmpty()) {
                return Mono.just(List.<TypeInfo>of());
            }
            List<AccessCheck> checks = new ArrayList<>(all.size());
            for (ConsoleType t : all) {
                checks.add(new AccessCheck(AccessCheck.action(t.service(), LIST),
                        AccessCheck.typePattern(platform.partition(), t.service(), session.region(), session.accountId(), t.type())));
            }
            return access.decide(session, checks).map(decisions -> {
                List<TypeInfo> known = new ArrayList<>();
                for (int i = 0; i < all.size(); i++) {
                    TypeInfo info = info(all.get(i), decisions.get(i));
                    if (info != null) {
                        known.add(info);
                    }
                }
                return known;
            });
        });
    }

    private static TypeInfo info(ConsoleType t, Decision list) {
        ConsoleAction create = null;
        List<VerbInfo> verbs = new ArrayList<>();
        for (ConsoleAction action : t.actions()) {
            if (!Boolean.TRUE.equals(action.allowed())) {
                continue;
            }
            if (CREATE.equals(action.verb())) {
                create = action;
            } else {
                verbs.add(new VerbInfo(action.verb(), action.title(), action.destructive(), InputForm.of(action.input()).forAgent()));
            }
        }
        if (!list.allowed() && create == null && verbs.isEmpty()) {
            return null;
        }
        return new TypeInfo(t.type(), t.service(), t.title(), t.plural(), list.allowed(), create != null,
                create == null ? null : InputForm.of(create.input()).forAgent(), verbs);
    }

    /**
     * The resources a question names, so "restart web-03" can say which: each word that looks like
     * a resource name (a digit or a separator in it) or follows "called", "named" or one of the
     * person's verbs, looked up by name in the account and region; exact names only, of types the
     * person may list. A lookup that fails is left out.
     */
    Mono<List<ResourceInfo>> named(Session session, String message, List<TypeInfo> known) {
        Set<String> listable = new LinkedHashSet<>();
        Set<String> verbs = new LinkedHashSet<>();
        for (TypeInfo t : known) {
            if (t.list()) {
                listable.add(t.type());
            }
            t.verbs().forEach(v -> verbs.add(v.verb()));
        }
        List<String> names = candidates(message, verbs);
        if (names.isEmpty() || listable.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(names)
                .concatMap(name -> resources.rows(session, new StateQuery(null, null, name, Map.of(), null, null, null, 50, null))
                        .map(page -> page.items().stream()
                                .filter(r -> r.name() != null && r.name().equalsIgnoreCase(name) && listable.contains(r.type()))
                                .limit(PER_NAME)
                                .map(ContextGatherer::info)
                                .toList())
                        .onErrorResume(e -> {
                            log.debug("the copilot could not look up '{}': {}", name, e.toString());
                            return Mono.just(List.of());
                        }))
                .concatMapIterable(found -> found)
                .distinct(ResourceInfo::wfrn)
                .collectList();
    }

    /** The words of a question that may be resource names, at most five. */
    static List<String> candidates(String message, Set<String> verbs) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(message);
        String previous = null;
        while (m.find() && names.size() < NAMES) {
            String token = m.group();
            String lower = token.toLowerCase(Locale.ROOT);
            boolean shaped = lower.chars().anyMatch(Character::isDigit) || lower.indexOf('-') > 0 || lower.indexOf('_') > 0 || lower.indexOf('.') > 0;
            boolean placed = previous != null && (NAMING.contains(previous) || verbs.contains(previous));
            if ((shaped || placed) && NAME_LIKE.matcher(lower).matches()) {
                names.add(token);
            }
            previous = lower;
        }
        return List.copyOf(names);
    }

    private static ResourceInfo info(ResourceView r) {
        return new ResourceInfo(r.wfrn(), r.type(), r.name(), r.state(), r.tags());
    }

    /**
     * The facts of the resource an explanation is about: the detail the person read, its activity
     * (who brought it in and when, its last change other than an observation), what depends on
     * it, and its monthly cost when the cost service answers one. A fact that cannot be read is
     * left out; the cost is never guessed.
     */
    Mono<Subject> subject(Session session, ResourceView view) {
        Mono<List<ActivityEntry>> activity = activity(session, view.type(), view.wfrn())
                .onErrorResume(e -> {
                    log.debug("no activity for the explanation of {}: {}", view.wfrn(), e.toString());
                    return Mono.just(List.of());
                });
        Mono<Optional<BlastRadius>> radius = resources.blastRadius(session, view.type(), view.wfrn(), null).map(Optional::of)
                .onErrorResume(e -> {
                    log.debug("no blast radius for the explanation of {}: {}", view.wfrn(), e.toString());
                    return Mono.just(Optional.empty());
                });
        Mono<Optional<Money>> cost = costs.monthlyCost(session, view.wfrn()).onErrorResume(e -> Mono.just(Optional.empty()));
        return Mono.zip(activity, radius, cost).map(t -> subject(view, t.getT1(), t.getT2().orElse(null), t.getT3().orElse(null)));
    }

    static Subject subject(ResourceView view, List<ActivityEntry> activity, BlastRadius radius, Money cost) {
        ActivityEntry origin = activity.stream().filter(e -> ORIGINS.contains(e.type())).findFirst().orElse(activity.isEmpty() ? null : activity.get(0));
        ActivityEntry last = null;
        for (ActivityEntry entry : activity) {
            if (!NO_CHANGE.contains(entry.type())) {
                last = entry;
            }
        }
        List<ResourceInfo> dependents = radius == null ? List.of()
                : radius.dependents().stream().limit(NAMED_DEPENDENTS).map(d -> new ResourceInfo(d.wfrn(), d.type(), d.name(), d.state(), Map.of())).toList();
        return new Subject(view.wfrn(), view.type(), view.service(), view.name(), view.region(), view.state(), view.tags(),
                InputForm.redacted(view.attributes()), InputForm.redacted(view.lastError()),
                origin != null ? origin.at() : view.createdAt(), origin != null && origin.actor() != null ? origin.actor() : view.requestedBy(),
                last != null ? last.at() : view.updatedAt(), last != null ? last.actor() : null,
                radius == null ? null : radius.total(), dependents,
                Boolean.TRUE.equals(view.drift()), view.finding() == null ? null : view.finding().fields(),
                cost == null ? null : cost.asDouble(), cost == null ? null : cost.currency());
    }

    /** The resource's activity, oldest first, as far as {@link #ACTIVITY_PAGES} pages go. */
    private Mono<List<ActivityEntry>> activity(Session session, String type, String wfrn) {
        return resources.activity(session, type, wfrn, 200, null)
                .expand(page -> page.next() == null ? Mono.empty() : resources.activity(session, type, wfrn, 200, page.next()))
                .take(ACTIVITY_PAGES)
                .concatMapIterable(Page::items)
                .collectList();
    }

    /** The turns before the question, in words, the last ten. */
    static List<HistoryTurn> history(Conversation conversation, String personTurnId) {
        List<HistoryTurn> history = new ArrayList<>();
        for (AssistantTurn turn : conversation.before(personTurnId, HISTORY)) {
            String text = PlainText.of(turn.text() != null ? turn.text() : turn.explanation() != null ? turn.explanation().summary() : null, HISTORY_TEXT);
            if (text != null) {
                history.add(new HistoryTurn(turn.role(), text));
            }
        }
        return history;
    }
}
