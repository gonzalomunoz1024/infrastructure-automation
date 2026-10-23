package com.iaf.console.bff.resourcegroups.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.application.AccessProperties;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.access.domain.Decisions;
import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.access.ports.outbound.AuthorizationPort;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resourcegroups.adapters.outbound.InMemoryResourceGroupStore;
import com.iaf.console.bff.resourcegroups.domain.CreateKeyReusedException;
import com.iaf.console.bff.resourcegroups.domain.GroupChanges;
import com.iaf.console.bff.resourcegroups.domain.GroupNameTakenException;
import com.iaf.console.bff.resourcegroups.domain.GroupQuery;
import com.iaf.console.bff.resourcegroups.domain.NewGroup;
import com.iaf.console.bff.resourcegroups.domain.ResourceGroup;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.resources.domain.ResourceView;
import com.iaf.console.bff.resources.domain.StateQuery;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.StepUpPolicy;
import com.iaf.console.bff.session.domain.StepUpRequiredException;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * The resource groups' rules over an in-memory store, with the catalogue, the policies and the
 * state service's rows faked: a create's Idempotency-Key, a change's If-Match, which rows a
 * group's page keeps, the account and region a group lives in, and who may do what.
 */
class ResourceGroupsTest {

    private static final String COMPUTE = "123456789012";
    private static final String DATA = "210987654321";
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final List<ConsoleType> CATALOGUE = List.of(type("compute", "vm"), type("platform", "volume"), type("data", "database"));
    private static final Map<String, Predicate<String>> POLICIES = Map.of(
            "user_gonzalo", action -> true,
            "user_dana", action -> !action.endsWith(":delete"),
            "user_mia", action -> action.endsWith(":list") || action.endsWith(":get"),
            "user_lena", action -> action.equals("resource-groups:get") || action.equals("compute:list"),
            "user_raj", action -> true);

    private final MovingClock clock = new MovingClock(NOW);
    private final List<StateQuery> queries = new ArrayList<>();
    private final List<ResourceView> rows = new ArrayList<>();
    private String next;
    private final ResourceGroups groups = new ResourceGroups(new InMemoryResourceGroupStore(), catalogue(), access(), sessions(), stateRows(), platform(), clock);

    private final Session gonzalo = session("gonzalo", COMPUTE, NOW);
    private final Session dana = session("dana", COMPUTE, NOW);
    private final Session mia = session("mia", COMPUTE, NOW);
    private final Session raj = session("raj", DATA, NOW);

    @Test
    void aRepeatedCreateAnswersTheFirstGroupForADay() {
        NewGroup body = group("web-tier", "vm");
        ResourceGroups.Created first = groups.create(gonzalo, body, "key-1").block();
        assertFalse(first.replayed());
        ResourceGroups.Created again = groups.create(gonzalo, body, " key-1 ").block();
        assertTrue(again.replayed(), "the same key and body answer the first group");
        assertEquals(first.group(), again.group());

        assertThrows(CreateKeyReusedException.class, () -> groups.create(gonzalo, group("web-tier-2", "vm"), "key-1").block(),
                "the same key with another body is a conflict");
        assertThrows(GroupNameTakenException.class, () -> groups.create(gonzalo, body, null).block());
        assertThrows(GroupNameTakenException.class, () -> groups.create(gonzalo, body, "key-2").block());
        assertThrows(GroupNameTakenException.class, () -> groups.create(dana, body, "key-1").block(), "a key answers its own sender only");
        assertEquals(1, groups.list(gonzalo).block().size());

        clock.advance(Duration.ofHours(25));
        assertThrows(GroupNameTakenException.class, () -> groups.create(gonzalo, body, "key-1").block(), "a day later the key answers no more");
        InvalidInputException longKey = assertThrows(InvalidInputException.class, () -> groups.create(gonzalo, group("other", "vm"), "k".repeat(256)).block());
        assertEquals("length", longKey.problems().get(0).code());
    }

    @Test
    void aCreateChecksItsTypesAgainstTheRegionsCatalogue() {
        InvalidInputException unknown = assertThrows(InvalidInputException.class, () -> groups.create(gonzalo, group("bad", "vm", "toaster"), null).block());
        assertEquals(GroupQuery.TYPE_UNKNOWN, unknown.problems().get(0).code());
        assertEquals("/query/types/1", unknown.problems().get(0).field());
        InvalidInputException untagged = assertThrows(InvalidInputException.class,
                () -> groups.create(gonzalo, new NewGroup("bare", null, new GroupQuery(List.of("vm"), Map.of())), null).block());
        assertEquals(GroupQuery.TAGS_REQUIRED, untagged.problems().get(0).code());
        assertEquals(List.of(), groups.list(gonzalo).block(), "nothing was stored");
        ResourceGroup every = groups.create(gonzalo, group("everything"), null).block().group();
        assertEquals(List.of(), every.query().types());
        assertEquals("wfrn:wf:resource-groups:global:" + COMPUTE + ":group/" + every.groupId(), every.wfrn());
        assertEquals("user_gonzalo", every.createdBy());
        assertEquals(NOW, every.createdAt());
    }

    @Test
    void aListIsTheAccountAndRegionsGroupsByName() {
        for (String name : List.of("beta", "alpha", "Alpha", "gamma")) {
            groups.create(gonzalo, group(name, "vm"), null).block();
        }
        groups.create(raj, group("delta", "vm"), null).block();
        assertEquals(List.of("Alpha", "alpha", "beta", "gamma"), groups.list(gonzalo).block().stream().map(ResourceGroup::name).toList());
        assertEquals(List.of("delta"), groups.list(raj).block().stream().map(ResourceGroup::name).toList());
        assertEquals(List.of("delta"), groups.list(session("gonzalo", DATA, NOW)).block().stream().map(ResourceGroup::name).toList(),
                "in another account the same person sees that account's groups");
        assertTrue(groups.list(gonzalo.switchedTo(COMPUTE, "eu-west")).block().isEmpty(), "a group lives in one region");
    }

    @Test
    void aChangeIsMadeAgainstTheVersionItWasReadAt() {
        ResourceGroup made = groups.create(gonzalo, group("web-tier", "vm"), null).block().group();
        String id = made.groupId();

        ConflictException stale = assertThrows(ConflictException.class, () -> groups.update(gonzalo, id, described("x"), "\"7\"").block());
        assertEquals(Map.of("version", 1L), stale.detail());
        clock.advance(Duration.ofMinutes(1));
        ResourceGroup second = groups.update(gonzalo, id, described("web VMs"), "\"1\"").block();
        assertEquals(2, second.version());
        assertEquals("web VMs", second.description());
        assertEquals(NOW.plus(Duration.ofMinutes(1)), second.updatedAt());
        assertEquals(3, groups.update(gonzalo, id, described("again"), "W/\"2\"").block().version(), "a weak tag names the version too");
        assertEquals(4, groups.update(gonzalo, id, described("any"), "*").block().version());
        assertEquals(5, groups.update(gonzalo, id, described("listed"), "\"9\", \"4\"").block().version(), "one of a list matches");
        assertEquals(6, groups.update(dana, id, new GroupChanges(null, null, new GroupQuery(List.of("vm", "volume"), Map.of("env", "dev"))), null)
                .block().version(), "an operator changes a group");
        assertEquals(6, groups.update(gonzalo, id, GroupChanges.none(), "\"6\"").block().version(), "a change of nothing keeps the version");
        assertEquals(List.of("vm", "volume"), groups.get(mia, id).block().query().types());

        InvalidInputException renamed = assertThrows(InvalidInputException.class, () -> groups.update(gonzalo, id, new GroupChanges("other", null, null), null).block());
        assertEquals(GroupChanges.NAME_IMMUTABLE, renamed.problems().get(0).code());
        InvalidInputException unknown = assertThrows(InvalidInputException.class,
                () -> groups.update(gonzalo, id, new GroupChanges(null, null, new GroupQuery(List.of("toaster"), Map.of("a", "b"))), null).block());
        assertEquals(GroupQuery.TYPE_UNKNOWN, unknown.problems().get(0).code());
        assertEquals(6, groups.get(gonzalo, id).block().version());
    }

    @Test
    void aGroupOfAnotherAccountOrRegionIsNotFound() {
        String id = groups.create(gonzalo, group("web-tier", "vm"), null).block().group().groupId();
        assertThrows(NotFoundException.class, () -> groups.get(raj, id).block());
        assertThrows(NotFoundException.class, () -> groups.update(raj, id, described("mine now"), null).block());
        assertThrows(NotFoundException.class, () -> groups.delete(raj, id).block());
        assertThrows(NotFoundException.class, () -> groups.resources(raj, id, null, null).block());
        assertThrows(NotFoundException.class, () -> groups.get(gonzalo.switchedTo(COMPUTE, "eu-west"), id).block());
        assertThrows(NotFoundException.class, () -> groups.get(gonzalo, "web-tier").block(), "what is no group id is no group");
        assertThrows(NotFoundException.class, () -> groups.get(gonzalo, "group-01jbx7q2m4k9d3c8f5r6t1w0ya").block());
        assertEquals("web-tier", groups.get(gonzalo, id).block().name());
    }

    @Test
    void thePoliciesAndTheStepUpDecideWhoMayDoWhat() {
        DeniedException viewer = assertThrows(DeniedException.class, () -> groups.create(mia, group("mine", "vm"), null).block());
        assertEquals("resource-groups:create", viewer.denial().action());
        assertEquals("wfrn:wf:resource-groups:global:" + COMPUTE + ":group/*", viewer.denial().resource());

        ResourceGroup made = groups.create(dana, group("web-tier", "vm"), null).block().group();
        assertEquals("web-tier", groups.get(mia, made.groupId()).block().name(), "a viewer reads a group");
        assertEquals(1, groups.list(mia).block().size());
        assertThrows(DeniedException.class, () -> groups.update(mia, made.groupId(), described("x"), null).block());
        DeniedException operator = assertThrows(DeniedException.class, () -> groups.delete(dana, made.groupId()).block());
        assertEquals("resource-groups:delete", operator.denial().action());
        assertEquals(made.wfrn(), operator.denial().resource());

        Session signedInLongAgo = session("gonzalo", COMPUTE, NOW.minus(Duration.ofHours(1)));
        assertThrows(StepUpRequiredException.class, () -> groups.delete(signedInLongAgo, made.groupId()).block(), "a delete needs a fresh sign-in");
        assertEquals(2, groups.update(signedInLongAgo, made.groupId(), described("x"), null).block().version(), "a change does not");
        groups.delete(gonzalo, made.groupId()).block();
        assertThrows(NotFoundException.class, () -> groups.get(gonzalo, made.groupId()).block());
        assertThrows(NotFoundException.class, () -> groups.delete(gonzalo, made.groupId()).block());
    }

    @Test
    void aGroupsPageAsksTheStateServiceForOneTypeOrNoneAndKeepsWhatTheCallerMayList() {
        rows.addAll(List.of(row("vm", "web-01"), row("volume", "data-01"), row("database", "orders-db"), row("gadget", "g-1")));
        next = "cursor-2";
        ResourceGroup vms = groups.create(gonzalo, group("vms", "vm"), null).block().group();
        Page<ResourceView> page = groups.resources(gonzalo, vms.groupId(), 10, "cursor-1").block();
        StateQuery asked = queries.get(queries.size() - 1);
        assertEquals("vm", asked.type(), "one type is the state service's filter");
        assertEquals(Map.of("owner", "team-compute"), asked.tags());
        assertEquals(10, asked.limit());
        assertEquals("cursor-1", asked.after());
        assertNull(asked.state(), "live resources only, the state service's default");
        assertEquals(List.of("web-01"), names(page));
        assertEquals("cursor-2", page.next());

        ResourceGroup two = groups.create(gonzalo, group("two", "vm", "database"), null).block().group();
        Page<ResourceView> both = groups.resources(gonzalo, two.groupId(), null, null).block();
        assertNull(queries.get(queries.size() - 1).type(), "several types go without a type filter");
        assertEquals(50, queries.get(queries.size() - 1).limit(), "the default page size");
        assertEquals(List.of("web-01", "orders-db"), names(both), "and keep the group's types");

        ResourceGroup every = groups.create(gonzalo, group("every"), null).block().group();
        assertEquals(List.of("web-01", "data-01", "orders-db"), names(groups.resources(gonzalo, every.groupId(), null, null).block()),
                "every type of the catalogue; a type the catalogue does not know is left out");

        Session lena = session("lena", COMPUTE, NOW);
        Page<ResourceView> listable = groups.resources(lena, every.groupId(), null, null).block();
        assertEquals(List.of("web-01"), names(listable), "the rows of a type the caller may not list are left out");
        assertEquals("cursor-2", listable.next(), "while the cursor goes on");

        int asks = queries.size();
        ResourceGroup volumes = groups.create(gonzalo, group("volumes", "volume"), null).block().group();
        Page<ResourceView> none = groups.resources(lena, volumes.groupId(), null, null).block();
        assertEquals(List.of(), none.items());
        assertNull(none.next(), "nothing the caller may list: no page to go on to");
        assertEquals(asks, queries.size(), "and no question to the state service");

        assertThrows(IllegalArgumentException.class, () -> groups.resources(gonzalo, vms.groupId(), 0, null).block());
        groups.resources(gonzalo, vms.groupId(), 500, null).block();
        assertEquals(200, queries.get(queries.size() - 1).limit(), "a page holds 200 rows at most");
        assertThrows(DeniedException.class, () -> groups.resources(session("noone", COMPUTE, NOW), vms.groupId(), null, null).block());
    }

    // ---- the fakes

    private static NewGroup group(String name, String... types) {
        return new NewGroup(name, null, new GroupQuery(List.of(types), Map.of("owner", "team-compute")));
    }

    private static GroupChanges described(String description) {
        return new GroupChanges(null, description, null);
    }

    private static List<String> names(Page<ResourceView> page) {
        return page.items().stream().map(ResourceView::name).toList();
    }

    private static ResourceView row(String type, String name) {
        return ResourceView.summary(new StateResource("wfrn:wf:x:global:" + COMPUTE + ":" + type + "/" + name, COMPUTE, "global", type, "x", name, "active", 1L,
                null, null, null, null, null, null, null, Map.of("owner", "team-compute"), null, null, null, null, null, NOW, NOW, null, null));
    }

    private static ConsoleType type(String service, String name) {
        return new ConsoleType(service + "/" + name, name, service, name + "s", name, "box", null, null, null, null, null, null, null, null, null);
    }

    private static Session session(String user, String accountId, Instant authTime) {
        Principal principal = new Principal("user_" + user, user + "@example.com", user, List.of());
        AccountMembership membership = new AccountMembership(accountId, accountId, "active", "global", List.of(), List.of("operator"));
        AccountMembership elsewhere = new AccountMembership(accountId.equals(COMPUTE) ? DATA : COMPUTE, null, "active", "global", List.of(), List.of("operator"));
        return new Session("s_" + user, principal, List.of(membership, elsewhere), accountId, "global", authTime, authTime, authTime,
                authTime.plus(Duration.ofDays(2)), "https://idp.test", user);
    }

    private static Types catalogue() {
        return new Types(null, null, null, null, null) {
            @Override
            public Mono<List<ConsoleType>> list(Session session) {
                return Mono.just(CATALOGUE);
            }
        };
    }

    private static Access access() {
        AuthorizationPort port = (scope, principal, groups, checks) -> Mono.just(new Decisions(checks.stream()
                .map(c -> POLICIES.getOrDefault(principal.userId(), action -> false).test(c.action()) ? Decision.allow()
                        : Decision.deny(Denial.byPolicy(c.action(), c.resource(), "No statement allows " + c.action(), null, null, null)))
                .toList(), 1));
        return new Access(port, new AccessProperties(Duration.ofSeconds(60), 1000));
    }

    private Sessions sessions() {
        return new Sessions(null, null, new StepUpPolicy(Duration.ofMinutes(10), List.of("*:delete")), null, null, clock);
    }

    private Resources stateRows() {
        return new Resources(null, null, null, null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public Mono<Page<ResourceView>> rows(Session session, StateQuery query) {
                queries.add(query);
                List<ResourceView> matching = rows.stream().filter(r -> query.type() == null || query.type().equals(r.type())).toList();
                return Mono.just(new Page<>(matching, next));
            }
        };
    }

    private static PlatformProperties platform() {
        return new PlatformProperties("http://localhost:18090", "", "wf", Duration.ofSeconds(30), Duration.ofMinutes(10), Map.of(),
                new PlatformProperties.Fake(false, 0, Duration.ofMillis(1), 1L));
    }

    /** A clock the test moves. */
    private static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
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
}
