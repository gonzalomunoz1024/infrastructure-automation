package com.iaf.console.state.stack.application;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.application.InventoryService;
import com.iaf.console.state.inventory.application.ResourceLoader;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.StackRef;
import com.iaf.console.state.inventory.domain.TagRules;
import com.iaf.console.state.inventory.ports.outbound.ResourceTypesPort;
import com.iaf.console.state.inventory.ports.outbound.StackMembershipPort;
import com.iaf.console.state.stack.domain.Blueprint;
import com.iaf.console.state.stack.domain.ChildView;
import com.iaf.console.state.stack.domain.ParameterCheck;
import com.iaf.console.state.stack.domain.References;
import com.iaf.console.state.stack.domain.Stack;
import com.iaf.console.state.stack.domain.StackDecider;
import com.iaf.console.state.stack.domain.StackDecider.Decision;
import com.iaf.console.state.stack.domain.StepAction;
import com.iaf.console.state.stack.ports.outbound.StackEventStorePort;
import com.iaf.console.state.stack.ports.outbound.StackStorePort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The command side of stacks (hub decision 33). This service decides and the BFF executes: a
 * launch records the stack and announces the steps that may start; the BFF mints each child naming
 * its step ({@link #claim}, through the inventory) and starts its job; every commit of a child
 * brings the stack to look at its children again ({@link #changed}), as does the sweeper, so the
 * stack moves on from its children's states whatever message was lost. Every change is decided by
 * {@link StackDecider} on the stack as read and written over that version, retried when another
 * replica moved it first; then its events are logged and put on the outbox ({@code state.stacks}).
 */
public final class Stacks implements StackMembershipPort {

    /** The service segment of a stack's wfrn and of its IAM actions. */
    public static final String SERVICE = "blueprints";
    /** The type segment of a stack's wfrn. */
    public static final String TYPE = "stack";
    public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{2,24}$");

    private static final Logger log = LoggerFactory.getLogger(Stacks.class);

    /** How long a claimed child may stay unrecorded, how long a ready step waits before it is announced again, how long a deleted stack is kept. */
    public record Settings(Duration missingAfter, Duration reannounceAfter, Duration retention) {
        public Settings {
            missingAfter = missingAfter == null ? Duration.ofMinutes(10) : missingAfter;
            reannounceAfter = reannounceAfter == null ? Duration.ofMinutes(5) : reannounceAfter;
            retention = retention == null ? Duration.ofDays(90) : retention;
        }
    }

    /** {@code POST /v1/stacks}. */
    public record Launch(String blueprint, String version, String name, ObjectNode parameters, Map<String, String> tags, String actor,
            String requestId) {
    }

    /** {@code POST /v1/blueprints/{name}:plan}'s answer. */
    public record Plan(String blueprint, String version, String name, ObjectNode parameters, List<PlannedStep> steps, Map<String, Integer> creates,
            List<Authorization> authorize) {
    }

    public record PlannedStep(String step, String type, String service, String title, int wave, List<String> dependsOn, JsonNode input,
            String deletionPolicy) {
    }

    public record Authorization(String action, String resource) {
    }

    private final StackStorePort store;
    private final StackEventStorePort log_;
    private final BlueprintCatalog catalog;
    private final ResourceLoader loader;
    private final ResourceTypesPort types;
    private final StackEventPublisher publisher;
    private final Clock clock;
    private final String partition;
    private final Settings settings;

    public Stacks(StackStorePort store, StackEventStorePort eventLog, BlueprintCatalog catalog, ResourceLoader loader, ResourceTypesPort types,
            StackEventPublisher publisher, Clock clock, String partition, Settings settings) {
        this.store = store;
        this.log_ = eventLog;
        this.catalog = catalog;
        this.loader = loader;
        this.types = types;
        this.publisher = publisher;
        this.clock = clock;
        this.partition = partition;
        this.settings = settings;
    }

    // launching

    public Mono<Stack> launch(AccountScope scope, Launch command) {
        Blueprint bp = catalog.require(command.blueprint());
        if (command.version() != null && !command.version().equals(bp.version())) {
            return Mono.error(new ConflictException("blueprint.versionChanged: blueprint " + bp.name() + " is at version " + bp.version(),
                    Map.of("version", bp.version())));
        }
        requireName(command.name());
        Map<String, String> tags = command.tags() == null ? Map.of() : command.tags();
        TagRules.require(tags, List.of(), tags.size());
        ObjectNode parameters = ParameterCheck.check(bp, command.parameters());
        return services(bp).flatMap(services -> {
            ResourceIdentity id = ResourceIdentity.mint(partition, SERVICE, scope.region(), scope.accountId(), TYPE);
            Decision d = StackDecider.launch(bp, id.wfrn(), scope.accountId(), scope.region(), command.name(), parameters, tags, services,
                    command.actor(), command.requestId(), clock.instant());
            return store.insert(d.stack()).flatMap(saved -> record(d).thenReturn(saved));
        });
    }

    /** What a launch would do, with nothing changed: the steps in their waves, the creates per type and what the BFF authorizes. */
    public Mono<Plan> plan(AccountScope scope, String blueprint, String name, ObjectNode given) {
        Blueprint bp = catalog.require(blueprint);
        requireName(name);
        ObjectNode parameters = ParameterCheck.check(bp, given);
        return services(bp).map(services -> {
            List<PlannedStep> steps = new ArrayList<>();
            References.Resolver resolver = expression -> {
                String[] p = expression.split("\\.");
                if (p[0].equals("params") && p.length == 2 && parameters.has(p[1])) {
                    return parameters.get(p[1]);
                }
                if (p[0].equals("stack") && p.length == 2) {
                    switch (p[1]) {
                        case "name" -> {
                            return com.fasterxml.jackson.databind.node.TextNode.valueOf(name);
                        }
                        case "accountId" -> {
                            return com.fasterxml.jackson.databind.node.TextNode.valueOf(scope.accountId());
                        }
                        case "region" -> {
                            return com.fasterxml.jackson.databind.node.TextNode.valueOf(scope.region());
                        }
                        default -> {
                        }
                    }
                }
                throw new References.Unresolved(expression, "known once the stack runs");
            };
            bp.resources().forEach((id, r) -> steps.add(new PlannedStep(id, r.type(), services.get(r.type()), r.title(), bp.waveOf(id), r.dependsOn(),
                    r.input() == null ? null : References.partial(r.input(), resolver), r.deletionPolicy().wire())));
            List<Authorization> authorize = new ArrayList<>();
            authorize.add(new Authorization(SERVICE + ":launch", stackPattern(scope)));
            new TreeMap<>(bp.creates()).keySet().forEach(type -> authorize.add(new Authorization(services.get(type) + ":create",
                    "wfrn:" + partition + ":" + services.get(type) + ":" + scope.region() + ":" + scope.accountId() + ":" + type + "/*")));
            return new Plan(bp.name(), bp.version(), name, parameters, steps, bp.creates(), authorize);
        });
    }

    // the commands on a stack

    public Mono<Stack> teardown(AccountScope scope, String wfrn, String reason, List<String> retain, String actor, String requestId) {
        if (reason == null || reason.strip().length() < 10 || reason.length() > 1000) {
            return Mono.error(new InvalidInputException("say why the stack is torn down",
                    List.of(Problem.error("reason", "/reason", "10 to 1000 characters"))));
        }
        Set<String> kept = retain == null ? Set.of() : new LinkedHashSet<>(retain);
        return mutate(scoped(scope, wfrn), (stack, children) -> StackDecider.teardown(stack, StackDecider.blueprintOf(stack), children, reason.strip(),
                kept, actor, requestId, clock.instant()));
    }

    public Mono<Stack> resume(AccountScope scope, String wfrn, String actor, String requestId) {
        return mutate(scoped(scope, wfrn), (stack, children) -> StackDecider.resume(stack, StackDecider.blueprintOf(stack), children, actor, requestId,
                clock.instant()));
    }

    public Mono<Stack> reportFailure(AccountScope scope, String wfrn, String step, Integer attempt, String action, String code, String message,
            String actor, String requestId) {
        StepAction a = StepAction.of(action);
        if (attempt == null || attempt < 1 || a == null) {
            return Mono.error(new InvalidInputException("name the attempt and the action",
                    List.of(Problem.error("required", attempt == null || attempt < 1 ? "/attempt" : "/action", "attempt 1 or more, action create or delete"))));
        }
        return mutate(scoped(scope, wfrn), (stack, children) -> StackDecider.reportFailure(stack, StackDecider.blueprintOf(stack), children, step,
                attempt, a, code, message == null ? null : message.substring(0, Math.min(message.length(), 2000)), actor, requestId, clock.instant()));
    }

    /** Looks at a stack's children again and moves it on, announcing again what has waited too long. */
    public Mono<Stack> advance(String wfrn) {
        return mutate(store.findByWfrn(wfrn), (stack, children) -> StackDecider.advance(stack, StackDecider.blueprintOf(stack), children,
                clock.instant(), settings.missingAfter(), settings.reannounceAfter(), settings.retention()));
    }

    // the inventory's port

    @Override
    public Mono<String> claim(AccountScope scope, StackRef ref, String type, String region, String proposedWfrn, String actor, String requestId) {
        if (!scope.covers(ResourceIdentity.of(ref.wfrn()).accountId(), region)) {
            return Mono.error(NotFoundException.of("stack", ref.wfrn()));
        }
        String[] taken = new String[1];
        return mutate(scoped(scope, ref.wfrn()), (stack, children) -> {
            StackDecider.Claim claim = StackDecider.claim(stack, StackDecider.blueprintOf(stack), ref.step(), ref.attempt(), type, proposedWfrn, actor,
                    requestId, clock.instant());
            taken[0] = claim.wfrn();
            return claim.decision();
        }).map(stack -> taken[0]);
    }

    @Override
    public Mono<Void> changed(Resource resource) {
        if (resource.stack() == null) {
            return Mono.empty();
        }
        return advance(resource.stack().wfrn()).then();
    }

    @Override
    public Mono<Boolean> holds(Resource resource) {
        if (resource.stack() == null) {
            return Mono.just(false);
        }
        return store.findByWfrn(resource.stack().wfrn())
                .map(stack -> !stack.deleted() && stack.step(resource.stack().step())
                        .filter(s -> resource.wfrn().equals(s.wfrn()))
                        .map(s -> !s.state().removed())
                        .orElse(false))
                .defaultIfEmpty(false);
    }

    // the common path

    private interface Decide extends java.util.function.BiFunction<Stack, Map<String, ChildView>, Decision> {
    }

    private Mono<Stack> scoped(AccountScope scope, String wfrn) {
        ResourceIdentity id;
        try {
            id = ResourceIdentity.of(wfrn);
        } catch (IllegalArgumentException e) {
            return Mono.error(new InvalidInputException("not a wfrn", List.of(Problem.error("wfrn", "/wfrn", e.getMessage()))));
        }
        if (!SERVICE.equals(id.service()) || !TYPE.equals(id.type()) || !scope.covers(id.accountId(), id.region())) {
            return Mono.error(NotFoundException.of("stack", wfrn));
        }
        return store.find(scope, wfrn).switchIfEmpty(Mono.error(() -> NotFoundException.of("stack", wfrn)));
    }

    /** Reads the stack and its children, decides, and writes over the version read; another replica's change first means read again. */
    private Mono<Stack> mutate(Mono<Stack> load, Decide decide) {
        return Mono.defer(() -> load.flatMap(stack -> children(stack).flatMap(children -> {
            Decision d = decide.apply(stack, children);
            if (!d.changed()) {
                return Mono.just(d.stack());
            }
            return store.update(d.stack(), stack.version()).flatMap(saved -> record(d).thenReturn(saved));
        }))).retryWhen(Retry.max(5).filter(e -> e instanceof ConflictException c && c.getMessage().startsWith(InventoryService.VERSION_CONFLICT))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    private Mono<Void> record(Decision d) {
        return log_.append(d.events()).then(Flux.fromIterable(d.events()).concatMap(publisher::publish).then());
    }

    /** What the stack sees of each child it took: the record as the inventory holds it now, a tombstone included. */
    private Mono<Map<String, ChildView>> children(Stack stack) {
        AccountScope scope = AccountScope.of(stack.accountId(), stack.region(), StackDecider.CONSOLE);
        return Flux.fromIterable(stack.steps())
                .filter(s -> s.wfrn() != null)
                .concatMap(s -> loader.load(scope, s.wfrn()).map(r -> Map.entry(s.step(), view(r))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new);
    }

    private static ChildView view(Resource r) {
        String error = r.lastError() == null ? null : r.lastError().message() != null ? r.lastError().message() : r.lastError().status();
        return new ChildView(r.wfrn(), r.state(), r.version(), r.registryId(), r.name(), r.attributes(), error);
    }

    /** The service of each type a blueprint creates, as the blueprint's page shows it. */
    public Mono<Map<String, String>> servicesOf(Blueprint bp) {
        return services(bp);
    }

    /** The service of each type the blueprint creates, as the platform publishes it (P-06); 400 for a type the platform does not have. */
    private Mono<Map<String, String>> services(Blueprint bp) {
        return Flux.fromIterable(new TreeMap<>(bp.creates()).keySet())
                .concatMap(type -> types.find(type).map(t -> Map.entry(type, t.service()))
                        .switchIfEmpty(Mono.error(() -> new InvalidInputException("the platform has no resource type " + type,
                                List.of(Problem.error("blueprint.typeUnknown", "/blueprint", "blueprint " + bp.name() + " creates a " + type
                                        + ", which the platform does not publish"))))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private String stackPattern(AccountScope scope) {
        return "wfrn:" + partition + ":" + SERVICE + ":" + scope.region() + ":" + scope.accountId() + ":" + TYPE + "/*";
    }

    private static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new InvalidInputException("a stack name is [a-z][a-z0-9-]{2,24}",
                    List.of(Problem.error("pattern", "/name", "3 to 25 lower-case letters, digits and dashes, starting with a letter")));
        }
    }
}
