package com.iaf.console.bff.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.application.AccessProperties;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.access.domain.Decisions;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Account;
import com.iaf.console.bff.assistant.domain.AssistantRequest.TypeInfo;
import com.iaf.console.bff.assistant.domain.AssistantRequest.VerbInfo;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/** What the copilot's unit tests share: people, the vm type as the descriptors give it, and stand-ins for the policy engine and the resources. */
public final class AssistantFixtures {

    public static final String COMPUTE = "123456789012";
    public static final String DATA = "210987654321";
    public static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    public static final String WEB_01 = "wfrn:wf:compute:global:" + COMPUTE + ":vm/vm-1001";

    private AssistantFixtures() {
    }

    /** A person signed in to an account in the global region; they are also a member of the other demo account. */
    public static Session session(String user, String accountId) {
        Principal principal = new Principal("user_" + user, user + "@example.com", user, List.of("team-compute"));
        AccountMembership compute = new AccountMembership(COMPUTE, "Compute", "active", "global", List.of("owners"), List.of("admin"));
        AccountMembership data = new AccountMembership(DATA, "Data", "active", "global", List.of("members"), List.of("operator"));
        return new Session("s_" + user, principal, List.of(compute, data), accountId, "global", NOW, NOW, NOW, NOW.plus(Duration.ofHours(12)),
                "https://idp.test", user);
    }

    public static JsonNode createVmForm() {
        return json("""
                {"type": "object", "required": ["name", "image", "size"],
                 "properties": {
                   "name": {"type": "string", "title": "VM name", "pattern": "^[a-z][a-z0-9-]{2,30}$"},
                   "image": {"type": "string", "title": "Image"},
                   "size": {"type": "string", "enum": ["small", "medium", "large"], "default": "small"},
                   "environment": {"type": "string", "enum": ["dev", "test", "prod"], "default": "dev"},
                   "owner": {"type": "string"}
                 }}""");
    }

    public static JsonNode restartForm() {
        return json("""
                {"type": "object", "properties": {"reason": {"type": "string"}, "environment": {"type": "string", "enum": ["dev", "test", "prod"], "default": "dev"}}}""");
    }

    public static JsonNode updateVmForm() {
        return json("""
                {"type": "object", "properties": {"cpu": {"type": "integer", "enum": [2, 4, 8]}, "memoryGb": {"type": "integer", "enum": [8, 16, 32]},
                 "reason": {"type": "string"}}}""");
    }

    /** The vm type: listed, created and restarted as the flags say. */
    public static TypeInfo vm(boolean list, boolean create, boolean restart) {
        List<VerbInfo> verbs = restart ? List.of(new VerbInfo("restart", "VM restart", false, restartForm())) : List.of();
        return new TypeInfo("vm", "compute", "Virtual machines", "vms", list, create, create ? createVmForm() : null, verbs);
    }

    public static TypeInfo volume() {
        return new TypeInfo("volume", "compute", "Volumes", "volumes", true, false, null, List.of());
    }

    public static AssistantRequest ask(String message, List<TypeInfo> types) {
        return new AssistantRequest("conv_1", "turn_1", AssistantRequest.ASK, message, null, new Account(COMPUTE, "Compute", "global", List.of("admin")), types,
                List.of(), null, null, List.of());
    }

    /** The policy engine, allowing what {@code allowed} says. */
    public static Access access(Predicate<AccessCheck> allowed) {
        return new Access((scope, principal, groups, checks) -> Mono.just(new Decisions(checks.stream()
                .map(c -> allowed.test(c) ? Decision.allow() : Decision.deny(Denial.byPolicy(c.action(), c.resource(), "not in the test's policy", null, null,
                        null)))
                .toList(), 1)), new AccessProperties(Duration.ofSeconds(60), 1000));
    }

    /** The resources, with only the wfrns in {@code existing} there. */
    public static Resources resources(Set<String> existing) {
        return resources(existing, new AtomicInteger());
    }

    /** The resources, with only the wfrns in {@code existing} there, counting each read in {@code reads}. */
    public static Resources resources(Set<String> existing, AtomicInteger reads) {
        return new Resources(null, null, null, null, null, null, null, null, null, null, null, null, null, JSON) {
            @Override
            public Mono<Detail> get(Session session, String type, String wfrn, String ifNoneMatch) {
                reads.incrementAndGet();
                return existing.contains(wfrn) ? Mono.just(new Detail(null, "\"1\"", false)) : Mono.error(NotFoundException.of("resource", wfrn));
            }
        };
    }

    public static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A clock the test moves. */
    public static final class MovingClock extends Clock {

        private volatile Instant now;

        public MovingClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Every check allowed but those of the given actions. */
    public static Predicate<AccessCheck> allBut(Set<String> denied) {
        return c -> !denied.contains(c.action());
    }
}
