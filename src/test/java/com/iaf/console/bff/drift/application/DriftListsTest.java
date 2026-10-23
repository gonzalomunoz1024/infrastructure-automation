package com.iaf.console.bff.drift.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.application.AccessProperties;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.access.domain.Decisions;
import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.access.ports.outbound.AuthorizationPort;
import com.iaf.console.bff.drift.domain.Finding;
import com.iaf.console.bff.drift.domain.UnmanagedObject;
import com.iaf.console.bff.drift.ports.outbound.DriftPort;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.StepUpPolicy;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * Which rows a drift list keeps, with the drift service and the policies faked: a row of a type
 * the person may not list, of another account or region, or of a type the region does not know is
 * left out; {@code can} follows what the person may do; and a page costs one question to the
 * policies, however many rows it has.
 */
class DriftListsTest {

    private static final String COMPUTE = "123456789012";
    private static final String DATA = "210987654321";
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final List<ConsoleType> CATALOGUE = List.of(type("compute", "vm"), type("data", "database"));
    /** lena may list and read virtual machines and nothing else; dana may do everything but delete. */
    private static final Map<String, Predicate<String>> POLICIES = Map.of(
            "user_lena", action -> action.equals("compute:list") || action.equals("compute:get"),
            "user_dana", action -> !action.endsWith(":delete"));

    private final AtomicInteger questions = new AtomicInteger();
    private final List<Finding> findingRows = new ArrayList<>();
    private final List<UnmanagedObject> objectRows = new ArrayList<>();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DriftPolicies policies = new DriftPolicies(catalogue(), access(), sessions(), platform());
    private final Findings findings = new Findings(drift(), policies, null, clock);
    private final Unmanaged unmanaged = new Unmanaged(drift(), policies);

    @Test
    void aFindingsPageKeepsTheRowsThePersonMayListAndAsksOnce() {
        findingRows.addAll(List.of(finding("fnd-vm", "wfrn:wf:compute:global:" + COMPUTE + ":vm/web-01", "open"),
                finding("fnd-db", "wfrn:wf:data:global:" + COMPUTE + ":database/orders-db", "open"),
                finding("fnd-closed", "wfrn:wf:compute:global:" + COMPUTE + ":vm/web-02", "closed"),
                finding("fnd-elsewhere", "wfrn:wf:compute:global:" + DATA + ":vm/other-1", "open"),
                finding("fnd-other-region", "wfrn:wf:compute:eu-1:" + COMPUTE + ":vm/web-03", "open"),
                finding("fnd-garbled", "not a wfrn", "open")));

        Page<Finding> lenas = findings.list(session("lena"), "all", null, null, null, null, null, null).block();
        assertEquals(List.of("fnd-vm", "fnd-closed"), lenas.items().stream().map(Finding::findingId).toList(),
                "no database rows for a person who may not list databases, and nothing of another account or region");
        assertEquals(List.of(), lenas.items().get(0).can(), "lena may not update the vm");
        assertEquals("next-1", lenas.next(), "the drift service's cursor goes on");
        assertEquals(1, questions.get(), "one question to the policies for the page");

        Page<Finding> danas = findings.list(session("dana"), "all", null, null, null, null, null, null).block();
        assertEquals(List.of("fnd-vm", "fnd-db", "fnd-closed"), danas.items().stream().map(Finding::findingId).toList());
        assertEquals(Finding.DECISIONS, danas.items().get(0).can());
        assertEquals(List.of(), danas.items().get(2).can(), "nothing to decide on a closed finding");
        assertEquals(2, questions.get());

        assertThrows(DeniedException.class, () -> findings.list(session("lena"), null, null, "database", null, null, null, null).block(),
                "a list of one type the person may not list is refused as that type's own list is");
        InvalidInputException unknown = assertThrows(InvalidInputException.class,
                () -> findings.list(session("lena"), null, null, "gadget", null, null, null, null).block());
        assertEquals("type.unknown", unknown.problems().get(0).code());
        assertThrows(InvalidInputException.class, () -> findings.list(session("lena"), "sideways", null, null, null, null, null, null).block());
    }

    @Test
    void anUnmanagedPageKeepsTheTypesTheRegionKnowsAndThePersonMayList() {
        objectRows.addAll(List.of(object("vm-1", "vm", "global"), object("db-1", "database", "global"), object("gadget-1", "gadget", "global"),
                object("vm-2", "vm", "eu-1")));

        Page<UnmanagedObject> lenas = unmanaged.list(session("lena"), null, null, null, null).block();
        assertEquals(List.of("vm-1"), lenas.items().stream().map(UnmanagedObject::providerId).toList());
        assertEquals(List.of(), lenas.items().get(0).can(), "lena may not create a vm, so neither import nor assign");
        assertEquals(1, questions.get());

        Page<UnmanagedObject> danas = unmanaged.list(session("dana"), null, "any", null, null).block();
        assertEquals(List.of("vm-1", "db-1"), danas.items().stream().map(UnmanagedObject::providerId).toList());
        assertEquals(UnmanagedObject.ACTIONS, danas.items().get(1).can());
        assertThrows(InvalidInputException.class, () -> unmanaged.list(session("dana"), null, "whose", null, null).block());
        assertThrows(NotFoundException.class, () -> unmanaged.run(session("dana"), "run-1").block(), "a run of another region is not found");
    }

    // ---- the fakes

    private static Finding finding(String id, String wfrn, String status) {
        return new Finding(id, wfrn, COMPUTE, "global", wfrn.contains(":database/") ? "database" : "vm", status, "warning", 1L,
                List.of(new Finding.Field("/cpu", JsonNodeFactory.instance.numberNode(4), JsonNodeFactory.instance.numberNode(8), null)), NOW, NOW,
                "closed".equals(status) ? NOW : null, "closed".equals(status) ? "accepted" : null, null, null, null, null, null, null);
    }

    private static UnmanagedObject object(String providerId, String type, String region) {
        return new UnmanagedObject(providerId, type, region, providerId, JsonNodeFactory.instance.objectNode(), NOW, NOW, null, null, null, null);
    }

    /** The drift service, answering the lists from the rows above and a discovery run in another region. */
    private DriftPort drift() {
        return (DriftPort) Proxy.newProxyInstance(DriftPort.class.getClassLoader(), new Class<?>[] {DriftPort.class}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "listFindings" -> Mono.just(new Page<>(List.copyOf(findingRows), "next-1"));
                    case "listUnmanaged" -> Mono.just(new Page<>(List.copyOf(objectRows), null));
                    case "getDiscovery" -> Mono.just(new com.iaf.console.bff.drift.domain.DiscoveryRun("run-1", "vm", "eu-1", "succeeded", NOW, NOW, 3, 1,
                            null));
                    case "toString" -> "the fake drift service";
                    case "hashCode" -> 0;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ConsoleType type(String service, String name) {
        return new ConsoleType(service + "/" + name, name, service, name + "s", name, "box", null, null, null, null, null, null, null, null, null);
    }

    private static Session session(String user) {
        Principal principal = new Principal("user_" + user, user + "@example.com", user, List.of());
        AccountMembership membership = new AccountMembership(COMPUTE, "Compute", "active", "global", List.of(), List.of("operator"));
        return new Session("s_" + user, principal, List.of(membership), COMPUTE, "global", NOW, NOW, NOW, NOW.plus(Duration.ofDays(2)), "https://idp.test",
                user);
    }

    private static Types catalogue() {
        return new Types(null, null, null, null, null) {
            @Override
            public Mono<List<ConsoleType>> list(Session session) {
                return Mono.just(CATALOGUE);
            }

            @Override
            public Mono<ConsoleType> byType(Session session, String type) {
                return CATALOGUE.stream().filter(t -> t.type().equals(type)).findFirst().map(Mono::just)
                        .orElseGet(() -> Mono.error(NotFoundException.of("type", type)));
            }
        };
    }

    private Access access() {
        AuthorizationPort port = (scope, principal, groups, checks) -> {
            questions.incrementAndGet();
            return Mono.just(new Decisions(checks.stream()
                    .map(c -> POLICIES.getOrDefault(principal.userId(), action -> false).test(c.action()) ? Decision.allow()
                            : Decision.deny(Denial.byPolicy(c.action(), c.resource(), "No statement allows " + c.action(), null, null, null)))
                    .toList(), 1));
        };
        return new Access(port, new AccessProperties(Duration.ofSeconds(60), 1000));
    }

    private Sessions sessions() {
        return new Sessions(null, null, new StepUpPolicy(Duration.ofMinutes(10), List.of("*:delete")), null, null, null, null, clock);
    }

    private static PlatformProperties platform() {
        return new PlatformProperties("http://localhost:18090", "", "wf", Duration.ofSeconds(30), Duration.ofMinutes(10), Map.of(),
                new PlatformProperties.Fake(false, 0, Duration.ofMillis(1), 1L));
    }
}
