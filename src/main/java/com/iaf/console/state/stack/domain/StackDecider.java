package com.iaf.console.state.stack.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.ResourceState;

/**
 * Decides what happens to a stack (hub decision 33). Pure: it sees the stack, its blueprint, the
 * children as they are now and the clock, and answers the stack after and the events that took it
 * there, each one version. Progress is level-triggered: {@link #advance} looks at every child and
 * moves every step its child's state calls for, so running it twice, or late, changes nothing more.
 * <p>
 * A launch readies the steps whose dependencies are all done, with their references resolved; a
 * step takes its child when the BFF mints it ({@link #claim}); a child that is active makes its step
 * done, one that failed makes the step and the stack failed (nothing new starts, nothing is rolled
 * back) until the child is retried. A teardown skips what never started, keeps what is retained and
 * readies each delete once nothing in the stack that depends on it is left.
 */
public final class StackDecider {

    /** The actor of what the stack does by itself (the sweeper's announcements). */
    public static final String CONSOLE = "console";

    private StackDecider() {
    }

    /** The stack after a decision and the events, none when nothing changed. */
    public record Decision(Stack stack, List<StackEvent> events) {
        public boolean changed() {
            return !events.isEmpty();
        }
    }

    /** A claim's decision and the child it opens (or opened, on a replay). */
    public record Claim(Decision decision, String wfrn) {
    }

    /**
     * The blueprint a stack was launched with, as far as running it needs: its steps' types,
     * templates, dependencies and teardown, and its output templates.
     */
    public static Blueprint blueprintOf(Stack stack) {
        Map<String, BlueprintResource> resources = new LinkedHashMap<>();
        for (StackStep s : stack.steps()) {
            resources.put(s.step(), new BlueprintResource(s.step(), s.type(), s.title(), s.template(), Map.of(), s.dependsOn(), s.deletionPolicy(),
                    s.deleteTemplate()));
        }
        return new Blueprint(stack.blueprint(), stack.blueprintVersion(), stack.blueprint(), "", "", List.of(), Map.of(), resources,
                stack.outputTemplates());
    }

    // opening

    public static Decision launch(Blueprint bp, String wfrn, String accountId, String region, String name, ObjectNode parameters,
            Map<String, String> tags, Map<String, String> services, String actor, String requestId, Instant now) {
        List<StackStep> steps = new ArrayList<>();
        bp.resources().forEach((id, r) -> steps.add(new StackStep(id, r.type(), services.getOrDefault(r.type(), "platform"), r.title(),
                StepState.WAITING, null, 1, r.dependsOn(), r.deletionPolicy(), null, null, null, null, null, null, null, null, r.input(), r.deleteInput())));
        Stack opened = new Stack(wfrn, accountId, region, name, bp.name(), bp.version(), StackState.PENDING, StackOperation.LAUNCH, 0, parameters,
                tags, actor, null, null, steps, null, bp.outputs(), null, now, now, null, null);
        Work w = new Work(opened, bp, Map.of(), now, actor, requestId);
        List<ReadyStep> ready = w.readyWaiting();
        ObjectNode extra = Json.object();
        extra.put("blueprintVersion", bp.version());
        extra.set("parameters", parameters.deepCopy());
        w.emit(StackEventType.STACK_REQUESTED, null, extra, ready);
        w.flushUnresolved();
        return w.decision();
    }

    // the BFF's commands

    /** A mint for a ready create step: the step takes the child, once per attempt; the same mint again answers the same child. */
    public static Claim claim(Stack stack, Blueprint bp, String step, Integer attempt, String type, String proposedWfrn, String actor,
            String requestId, Instant now) {
        StackStep current = stack.step(step).orElseThrow(() -> new InvalidInputException("the stack has no step " + step,
                List.of(Problem.error("stack.stepUnknown", "/stack/step", "a resource id of blueprint " + stack.blueprint()))));
        if (!current.type().equals(type)) {
            throw new InvalidInputException("the step creates another type",
                    List.of(Problem.error("stack.stepType", "/type", "step " + step + " creates a " + current.type())));
        }
        int at = attempt == null ? current.attempt() : attempt;
        if (current.action() == StepAction.CREATE && current.attempt() == at && current.wfrn() != null) {
            return new Claim(new Decision(stack, List.of()), current.wfrn());
        }
        if (stack.operation() != StackOperation.LAUNCH || current.state() != StepState.READY || current.action() != StepAction.CREATE
                || current.attempt() != at) {
            throw new ConflictException("stack.stepNotReady: step " + step + " takes no child at attempt " + at + ", it is " + current.state().wire(),
                    Map.of("state", current.state().wire(), "attempt", current.attempt()));
        }
        Work w = new Work(stack, bp, Map.of(), now, actor, requestId);
        StackStep.Builder s = w.steps.get(step);
        s.state = StepState.RUNNING;
        s.wfrn = proposedWfrn;
        s.childState = ResourceState.PENDING;
        s.startedAt = now;
        s.ready = null;
        ObjectNode extra = Json.object();
        extra.put("attempt", at);
        extra.put("wfrn", proposedWfrn);
        w.emit(StackEventType.STACK_STEP_STARTED, step, extra, List.of());
        return new Claim(w.decision(), proposedWfrn);
    }

    /** The BFF could not start a ready step: the step fails, and with it the launch. */
    public static Decision reportFailure(Stack stack, Blueprint bp, Map<String, ChildView> children, String step, int attempt, StepAction action,
            String code, String message, String actor, String requestId, Instant now) {
        StackStep current = stack.step(step).orElseThrow(() -> new InvalidInputException("the stack has no step " + step,
                List.of(Problem.error("stack.stepUnknown", "/step", "a resource id of blueprint " + stack.blueprint()))));
        if (current.state() == StepState.FAILED && current.action() == action && current.attempt() == attempt) {
            return new Decision(stack, List.of());
        }
        if (current.state() != StepState.READY || current.action() != action || current.attempt() != attempt) {
            throw new ConflictException("stack.stepMoved: step " + step + " is no longer ready to " + action.wire() + " at attempt " + attempt,
                    Map.of("state", current.state().wire(), "attempt", current.attempt()));
        }
        Work w = new Work(stack, bp, children, now, actor, requestId);
        StackStep.Builder s = w.steps.get(step);
        w.fail(s, action, code == null || code.isBlank() ? "bff.failed" : code, message);
        w.emitFailed(s, List.of());
        return w.decision();
    }

    /** Tears the stack down; on a stack already deleting it only retains the named steps. */
    public static Decision teardown(Stack stack, Blueprint bp, Map<String, ChildView> children, String reason, Set<String> retain, String actor,
            String requestId, Instant now) {
        if (stack.deleted()) {
            throw new ConflictException("stack.deleted: the stack is deleted", Map.of("deletedAt", String.valueOf(stack.deletedAt())));
        }
        for (String step : retain) {
            if (stack.step(step).isEmpty()) {
                throw new InvalidInputException("retain names a step the stack does not have",
                        List.of(Problem.error("stack.stepUnknown", "/retain", "no step " + step)));
            }
        }
        Work w = new Work(stack, bp, children, now, actor, requestId);
        if (stack.operation() == StackOperation.TEARDOWN) {
            for (String step : retain) {
                StackStep.Builder s = w.steps.get(step);
                if (!s.state.removed()) {
                    w.remove(s, StepState.RETAINED);
                    w.emitRemoved(s, w.readyDeletes());
                    w.flushUnresolved();
                }
            }
            w.finishTeardown(null);
            return w.decision();
        }
        w.operation = StackOperation.TEARDOWN;
        w.state = StackState.DELETING;
        w.teardownReason = reason;
        w.teardownBy = actor;
        w.outputs = null;
        ArrayNode removed = Json.MAPPER.createArrayNode();
        for (StackStep.Builder s : w.steps.values()) {
            ChildView c = w.child(s);
            boolean keep = s.deletionPolicy == DeletionPolicy.RETAIN || retain.contains(s.step);
            StepState outcome = null;
            if (s.wfrn == null || s.state == StepState.WAITING || s.state == StepState.READY) {
                outcome = s.state.removed() ? null : StepState.SKIPPED;
            } else if (c != null && c.state() == ResourceState.DELETED) {
                outcome = StepState.DELETED;
            } else if (keep && !s.state.removed()) {
                outcome = StepState.RETAINED;
            }
            if (outcome != null) {
                w.remove(s, outcome);
                ObjectNode row = removed.addObject();
                row.put("step", s.step);
                row.put("outcome", outcome.wire());
            }
        }
        List<ReadyStep> ready = w.readyDeletes();
        ObjectNode extra = Json.object();
        extra.put("reason", reason);
        ArrayNode kept = extra.putArray("retain");
        retain.forEach(kept::add);
        extra.set("removed", removed);
        w.emit(StackEventType.STACK_TEARDOWN_REQUESTED, null, extra, ready);
        w.flushUnresolved();
        w.finishTeardown(null);
        return w.decision();
    }

    /** Announces again what is ready, and readies what could not start: childless failed creates and failed deletes, at the next attempt. */
    public static Decision resume(Stack stack, Blueprint bp, Map<String, ChildView> children, String actor, String requestId, Instant now) {
        if (stack.deleted()) {
            throw new ConflictException("stack.deleted: the stack is deleted", Map.of("deletedAt", String.valueOf(stack.deletedAt())));
        }
        Work w = new Work(stack, bp, children, now, actor, requestId);
        List<ReadyStep> ready = new ArrayList<>();
        for (StackStep.Builder s : w.steps.values()) {
            ChildView c = w.child(s);
            if (s.state == StepState.READY) {
                s.announcedAt = now;
                ready.add(s.ready);
            } else if (s.state == StepState.FAILED && s.action == StepAction.CREATE && w.operation == StackOperation.LAUNCH
                    && (s.wfrn == null || c == null || c.state() == ResourceState.DELETED)) {
                s.attempt++;
                s.wfrn = null;
                s.childState = null;
                s.error = null;
                s.state = StepState.WAITING;
                w.touched = true;
            } else if (s.state == StepState.FAILED && s.action == StepAction.DELETE && c != null
                    && (c.state() == ResourceState.ACTIVE || c.state() == ResourceState.FAILED)) {
                s.attempt++;
                ReadyStep r = w.readyDelete(s);
                if (r != null) {
                    ready.add(r);
                }
            }
        }
        ready.addAll(w.operation == StackOperation.LAUNCH ? w.readyWaiting() : w.readyDeletes());
        if (ready.isEmpty() && !w.dirty()) {
            return new Decision(stack, List.of());
        }
        w.emit(StackEventType.STACK_RESUMED, null, null, ready);
        w.flushUnresolved();
        return w.decision();
    }

    // by itself

    /**
     * Moves every step its child's state calls for; with {@code reannounceAfter}, also announces
     * again the steps that have been ready that long without being started (a lost message).
     */
    public static Decision advance(Stack stack, Blueprint bp, Map<String, ChildView> children, Instant now, Duration missingAfter,
            Duration reannounceAfter, Duration retention) {
        if (stack.deleted()) {
            return new Decision(stack, List.of());
        }
        Work w = new Work(stack, bp, children, now, CONSOLE, null);
        w.retention = retention;
        if (w.operation == StackOperation.LAUNCH) {
            w.advanceLaunch(missingAfter);
        } else {
            w.advanceTeardown(missingAfter);
        }
        if (reannounceAfter != null && !w.deleted()) {
            List<ReadyStep> stale = new ArrayList<>();
            for (StackStep.Builder s : w.steps.values()) {
                if (s.state == StepState.READY && s.ready != null && (s.announcedAt == null || s.announcedAt.plus(reannounceAfter).isBefore(now))) {
                    s.announcedAt = now;
                    stale.add(s.ready);
                }
            }
            if (!stale.isEmpty()) {
                w.emit(StackEventType.STACK_RESUMED, null, null, stale);
            }
        }
        return w.decision();
    }

    // the working copy

    private static final class Work {
        final Stack before;
        final Blueprint bp;
        final Map<String, ChildView> children;
        final Instant now;
        final String actor;
        final String requestId;
        final LinkedHashMap<String, StackStep.Builder> steps = new LinkedHashMap<>();
        final List<StackEvent> events = new ArrayList<>();
        final List<StackStep.Builder> unresolved = new ArrayList<>();
        StackState state;
        StackOperation operation;
        long version;
        String teardownReason;
        String teardownBy;
        Map<String, BlueprintOutput> outputs;
        Stack.Failure failure;
        Instant updatedAt;
        Instant deletedAt;
        Instant expiresAt;
        Duration retention = Duration.ofDays(90);
        boolean touched;

        Work(Stack before, Blueprint bp, Map<String, ChildView> children, Instant now, String actor, String requestId) {
            this.before = before;
            this.bp = bp;
            this.children = children;
            this.now = now;
            this.actor = actor;
            this.requestId = requestId;
            before.steps().forEach(s -> steps.put(s.step(), s.toBuilder()));
            state = before.state();
            operation = before.operation();
            version = before.version();
            teardownReason = before.teardownReason();
            teardownBy = before.teardownBy();
            outputs = before.outputs();
            failure = before.failure();
            updatedAt = before.updatedAt();
            deletedAt = before.deletedAt();
            expiresAt = before.expiresAt();
        }

        ChildView child(StackStep.Builder s) {
            return s.wfrn == null ? null : children.get(s.step);
        }

        boolean deleted() {
            return state == StackState.DELETED;
        }

        boolean dirty() {
            return touched;
        }

        boolean hasFailed() {
            return steps.values().stream().anyMatch(s -> s.state == StepState.FAILED);
        }

        // launch

        void advanceLaunch(Duration missingAfter) {
            for (StackStep.Builder s : steps.values()) {
                ChildView c = child(s);
                if (c != null && c.state() != s.childState) {
                    s.childState = c.state();
                    touched = true;
                }
                switch (s.state) {
                    case RUNNING -> {
                        if (c == null) {
                            if (s.startedAt != null && s.startedAt.plus(missingAfter).isBefore(now)) {
                                fail(s, StepAction.CREATE, "child.missing", "the child was never recorded");
                                s.wfrn = null;
                                emitFailed(s, List.of());
                            }
                        } else if (c.state() == ResourceState.ACTIVE) {
                            done(s);
                            emitStep(StackEventType.STACK_STEP_SUCCEEDED, s, readyWaiting());
                            flushUnresolved();
                        } else if (c.state() == ResourceState.FAILED) {
                            fail(s, StepAction.CREATE, "child.failed", c.error());
                            emitFailed(s, List.of());
                        } else if (c.state() == ResourceState.DELETED) {
                            fail(s, StepAction.CREATE, "child.deleted", "the resource was deleted outside the stack");
                            emitFailed(s, List.of());
                        }
                    }
                    case FAILED -> {
                        if (s.action == StepAction.CREATE && c != null && c.state() == ResourceState.PENDING) {
                            s.state = StepState.RUNNING;
                            s.error = null;
                            emitStep(StackEventType.STACK_STEP_RETRIED, s, readyWaiting());
                            flushUnresolved();
                        } else if (s.action == StepAction.CREATE && c != null && c.state() == ResourceState.ACTIVE) {
                            done(s);
                            emitStep(StackEventType.STACK_STEP_SUCCEEDED, s, readyWaiting());
                            flushUnresolved();
                        }
                    }
                    case DONE -> {
                        if (c != null && c.state() == ResourceState.DELETED) {
                            fail(s, StepAction.CREATE, "child.deleted", "the resource was deleted outside the stack");
                            emitFailed(s, List.of());
                        }
                    }
                    default -> {
                    }
                }
            }
            if (state != StackState.ACTIVE && steps.values().stream().allMatch(s -> s.state == StepState.DONE)) {
                state = StackState.ACTIVE;
                failure = null;
                outputs = resolveOutputs();
                ObjectNode extra = Json.object();
                ObjectNode values = extra.putObject("outputs");
                outputs.forEach((k, v) -> values.put(k, v.value()));
                emit(StackEventType.STACK_LAUNCHED, null, extra, List.of());
            }
        }

        /** Readies every waiting step whose dependencies are all done, unless a failed step stopped the launch. */
        List<ReadyStep> readyWaiting() {
            List<ReadyStep> out = new ArrayList<>();
            if (operation != StackOperation.LAUNCH) {
                return out;
            }
            for (StackStep.Builder s : steps.values()) {
                if (hasFailed()) {
                    break;
                }
                if (s.state == StepState.WAITING && s.dependsOn.stream().allMatch(d -> steps.get(d).state == StepState.DONE)) {
                    ReadyStep r = readyCreate(s);
                    if (r != null) {
                        out.add(r);
                    }
                }
            }
            return out;
        }

        ReadyStep readyCreate(StackStep.Builder s) {
            BlueprintResource r = bp.resources().get(s.step);
            JsonNode input;
            try {
                input = r == null || r.input() == null ? Json.object() : References.resolve(r.input(), this::resolve);
            } catch (References.Unresolved e) {
                fail(s, StepAction.CREATE, "input.unresolved", e.getMessage());
                unresolved.add(s);
                return null;
            }
            s.state = StepState.READY;
            s.action = StepAction.CREATE;
            s.error = null;
            s.announcedAt = now;
            s.ready = new ReadyStep(s.step, StepAction.CREATE, s.attempt, ReadyStep.keyOf(before.wfrn(), s.step, StepAction.CREATE, s.attempt), s.type,
                    s.service, (ObjectNode) input, null, null);
            touched = true;
            return s.ready;
        }

        Map<String, BlueprintOutput> resolveOutputs() {
            Map<String, BlueprintOutput> out = new LinkedHashMap<>();
            bp.outputs().forEach((k, o) -> out.put(k, new BlueprintOutput(o.title(), o.description(),
                    References.partial(com.fasterxml.jackson.databind.node.TextNode.valueOf(o.value()), this::resolve).asText())));
            return out;
        }

        // teardown

        void advanceTeardown(Duration missingAfter) {
            for (StackStep.Builder s : steps.values()) {
                ChildView c = child(s);
                if (c != null && c.state() != s.childState) {
                    s.childState = c.state();
                    touched = true;
                }
                boolean gone = s.wfrn != null && (c == null || c.state() == ResourceState.DELETED);
                switch (s.state) {
                    case READY -> {
                        if (s.action == StepAction.DELETE && gone) {
                            remove(s, StepState.DELETED);
                            emitRemoved(s, readyDeletes());
                        } else if (s.action == StepAction.DELETE && c != null && c.state() == ResourceState.DELETING) {
                            deleting(s, c);
                        }
                    }
                    case DELETING -> {
                        if (gone) {
                            remove(s, StepState.DELETED);
                            emitRemoved(s, readyDeletes());
                        } else if (c != null && (c.state() == ResourceState.ACTIVE || c.state() == ResourceState.FAILED)
                                && (s.childVersion == null || c.version() > s.childVersion)) {
                            fail(s, StepAction.DELETE, "delete.failed", c.error());
                            emitFailed(s, List.of());
                        }
                    }
                    case FAILED -> {
                        if (s.action == StepAction.DELETE && gone) {
                            remove(s, StepState.DELETED);
                            emitRemoved(s, readyDeletes());
                        } else if (s.action == StepAction.DELETE && c != null && c.state() == ResourceState.DELETING) {
                            deleting(s, c);
                        } else if (s.action == StepAction.CREATE && gone) {
                            remove(s, StepState.DELETED);
                            emitRemoved(s, readyDeletes());
                        }
                    }
                    case RUNNING -> {
                        if (c == null) {
                            if (s.startedAt == null || s.startedAt.plus(missingAfter).isBefore(now)) {
                                remove(s, StepState.SKIPPED);
                                emitRemoved(s, readyDeletes());
                            }
                        } else if (c.state() == ResourceState.DELETED) {
                            remove(s, StepState.DELETED);
                            emitRemoved(s, readyDeletes());
                        } else if (c.state() == ResourceState.ACTIVE) {
                            done(s);
                            emitStep(StackEventType.STACK_STEP_SUCCEEDED, s, readyDeletes());
                        } else if (c.state() == ResourceState.FAILED) {
                            fail(s, StepAction.CREATE, "child.failed", c.error());
                            emitFailed(s, readyDeletes());
                        }
                    }
                    case DONE -> {
                        if (gone) {
                            remove(s, StepState.DELETED);
                            emitRemoved(s, readyDeletes());
                        }
                    }
                    default -> {
                    }
                }
                flushUnresolved();
            }
            List<ReadyStep> late = readyDeletes();
            if (!late.isEmpty()) {
                emit(StackEventType.STACK_RESUMED, null, null, late);
            }
            finishTeardown(null);
        }

        void deleting(StackStep.Builder s, ChildView c) {
            s.state = StepState.DELETING;
            s.childVersion = c.version();
            s.ready = null;
            s.error = null;
            ObjectNode extra = Json.object();
            extra.put("wfrn", s.wfrn);
            emit(StackEventType.STACK_STEP_DELETING, s.step, extra, List.of());
        }

        /** Readies the delete of every child nothing left in the stack depends on, once the child is settled. */
        List<ReadyStep> readyDeletes() {
            List<ReadyStep> out = new ArrayList<>();
            if (operation != StackOperation.TEARDOWN) {
                return out;
            }
            for (StackStep.Builder s : steps.values()) {
                boolean holding = s.state == StepState.DONE || s.state == StepState.FAILED && s.action == StepAction.CREATE && s.wfrn != null;
                ChildView c = child(s);
                boolean settled = c != null && (c.state() == ResourceState.ACTIVE || c.state() == ResourceState.FAILED);
                if (!holding || !settled || s.deletionPolicy == DeletionPolicy.RETAIN) {
                    continue;
                }
                boolean free = bp.dependentsOf(s.step).stream().map(steps::get).allMatch(d -> d == null || d.state.removed());
                if (free) {
                    s.action = StepAction.DELETE;
                    s.attempt = 1;
                    ReadyStep r = readyDelete(s);
                    if (r != null) {
                        out.add(r);
                    }
                }
            }
            return out;
        }

        ReadyStep readyDelete(StackStep.Builder s) {
            BlueprintResource r = bp.resources().get(s.step);
            JsonNode input;
            try {
                input = r == null || r.deleteInput() == null ? Json.object() : References.resolve(r.deleteInput(), this::resolve);
            } catch (References.Unresolved e) {
                fail(s, StepAction.DELETE, "input.unresolved", e.getMessage());
                unresolved.add(s);
                return null;
            }
            s.state = StepState.READY;
            s.action = StepAction.DELETE;
            s.error = null;
            s.announcedAt = now;
            s.ready = new ReadyStep(s.step, StepAction.DELETE, s.attempt, ReadyStep.keyOf(before.wfrn(), s.step, StepAction.DELETE, s.attempt), s.type,
                    s.service, (ObjectNode) input, s.wfrn, teardownReason);
            touched = true;
            return s.ready;
        }

        void finishTeardown(String unused) {
            if (operation == StackOperation.TEARDOWN && state != StackState.DELETED && steps.values().stream().allMatch(s -> s.state.removed())) {
                state = StackState.DELETED;
                deletedAt = now;
                expiresAt = now.plus(retention);
                failure = null;
                emit(StackEventType.STACK_DELETED, null, null, List.of());
            }
        }

        // steps

        void done(StackStep.Builder s) {
            s.state = StepState.DONE;
            s.endedAt = now;
            s.error = null;
            s.ready = null;
        }

        void fail(StackStep.Builder s, StepAction action, String code, String message) {
            s.state = StepState.FAILED;
            s.action = action;
            s.ready = null;
            s.endedAt = now;
            s.error = new StackStep.StepError(code, message, now);
            touched = true;
        }

        void remove(StackStep.Builder s, StepState outcome) {
            s.state = outcome;
            s.ready = null;
            s.error = null;
            s.endedAt = now;
            touched = true;
        }

        // references

        JsonNode resolve(String expression) {
            String[] p = expression.split("\\.");
            switch (p[0]) {
                case "params" -> {
                    JsonNode v = before.parameters() == null || p.length != 2 ? null : before.parameters().get(p[1]);
                    if (v == null) {
                        throw new References.Unresolved(expression, "no value");
                    }
                    return v;
                }
                case "stack" -> {
                    return switch (p.length == 2 ? p[1] : "") {
                        case "name" -> Json.node(before.name());
                        case "wfrn" -> Json.node(before.wfrn());
                        case "accountId" -> Json.node(before.accountId());
                        case "region" -> Json.node(before.region());
                        default -> throw new References.Unresolved(expression, "not a field of a stack");
                    };
                }
                case "teardown" -> {
                    if (teardownReason == null) {
                        throw new References.Unresolved(expression, "no teardown");
                    }
                    return Json.node(teardownReason);
                }
                case "resources" -> {
                    StackStep.Builder s = p.length >= 3 ? steps.get(p[1]) : null;
                    ChildView c = s == null ? null : child(s);
                    if (s == null || s.state != StepState.DONE || c == null || c.state() != ResourceState.ACTIVE) {
                        throw new References.Unresolved(expression, "the resource is not active");
                    }
                    if (p.length == 3) {
                        String v = switch (p[2]) {
                            case "wfrn" -> c.wfrn();
                            case "registryId" -> c.registryId();
                            case "name" -> c.name();
                            default -> null;
                        };
                        if (v == null) {
                            throw new References.Unresolved(expression, "the resource has no " + p[2]);
                        }
                        return Json.node(v);
                    }
                    JsonNode v = p.length == 4 && p[2].equals("attributes") && c.attributes() != null ? c.attributes().get(p[3]) : null;
                    if (v == null || v.isNull()) {
                        throw new References.Unresolved(expression, "the resource has no " + (p.length == 4 ? p[3] : expression));
                    }
                    if (v.isObject() && v.has("$enc")) {
                        throw new References.Unresolved(expression, "a sensitive field never leaves its resource");
                    }
                    return v;
                }
                default -> throw new References.Unresolved(expression, "not a reference");
            }
        }

        // events

        void settle() {
            Stack.Failure first = null;
            for (StackStep.Builder s : steps.values()) {
                if (s.state == StepState.FAILED && first == null) {
                    first = new Stack.Failure(s.step, s.error == null ? null : s.error.code(), s.error == null ? null : s.error.message());
                }
            }
            failure = first;
            if (operation == StackOperation.LAUNCH) {
                if (first != null) {
                    state = StackState.FAILED;
                } else if (state == StackState.FAILED) {
                    state = StackState.PENDING;
                }
            }
        }

        void emitStep(StackEventType type, StackStep.Builder s, List<ReadyStep> ready) {
            ObjectNode extra = Json.object();
            extra.put("attempt", s.attempt);
            Json.putIf(extra, "wfrn", s.wfrn);
            emit(type, s.step, extra, ready);
        }

        void emitFailed(StackStep.Builder s, List<ReadyStep> ready) {
            ObjectNode extra = Json.object();
            extra.put("attempt", s.attempt);
            Json.putIf(extra, "wfrn", s.wfrn);
            extra.put("action", (s.action == null ? StepAction.CREATE : s.action).wire());
            extra.put("code", s.error == null ? "failed" : s.error.code());
            Json.putIf(extra, "message", s.error == null ? null : s.error.message());
            emit(StackEventType.STACK_STEP_FAILED, s.step, extra, ready);
        }

        void emitRemoved(StackStep.Builder s, List<ReadyStep> ready) {
            ObjectNode extra = Json.object();
            Json.putIf(extra, "wfrn", s.wfrn);
            extra.put("outcome", s.state.wire());
            emit(StackEventType.STACK_STEP_REMOVED, s.step, extra, ready);
        }

        /** The steps whose references could not be resolved when readied: each failed, each its own event. */
        void flushUnresolved() {
            List<StackStep.Builder> failed = new ArrayList<>(unresolved);
            unresolved.clear();
            failed.forEach(s -> emitFailed(s, List.of()));
        }

        void emit(StackEventType type, String step, ObjectNode extra, List<ReadyStep> ready) {
            settle();
            version++;
            updatedAt = now;
            touched = true;
            ObjectNode data = Json.object();
            data.put("version", version);
            data.put("state", state.wire());
            data.put("operation", operation.wire());
            data.put("blueprint", before.blueprint());
            data.put("name", before.name());
            Json.putIf(data, "step", step);
            if (extra != null) {
                data.setAll(extra);
            }
            if (ready != null && !ready.isEmpty()) {
                data.set("ready", Json.MAPPER.valueToTree(ready));
            }
            events.add(new StackEvent(null, type, before.wfrn(), before.accountId(), before.region(), version, null, now, actor, requestId, data));
        }

        Decision decision() {
            if (events.isEmpty()) {
                return new Decision(before, List.of());
            }
            List<StackStep> built = steps.values().stream().map(StackStep.Builder::build).toList();
            Stack after = new Stack(before.wfrn(), before.accountId(), before.region(), before.name(), before.blueprint(), before.blueprintVersion(),
                    state, operation, version, before.parameters(), before.tags(), before.launchedBy(), teardownReason, teardownBy, built, outputs,
                    before.outputTemplates(), failure, before.createdAt(), updatedAt, deletedAt, expiresAt);
            return new Decision(after, List.copyOf(events));
        }
    }
}
