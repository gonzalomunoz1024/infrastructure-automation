package com.iaf.console.bff.resourcegroups.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/** A group's rules: the name, the description, the query's types and tags, a change, and what a create's key remembers. */
class ResourceGroupTest {

    private static final Set<String> CATALOGUE = Set.of("vm", "volume", "image");
    private static final Instant AT = Instant.parse("2026-10-23T12:00:00Z");
    private static final String COMPUTE = "123456789012";

    private static GroupQuery query(List<String> types, Map<String, String> tags) {
        return new GroupQuery(types, tags);
    }

    private static NewGroup group(String name, String description, GroupQuery query) {
        return new NewGroup(name, description, query);
    }

    private static NewGroup valid() {
        return group("web-tier", "the web VMs", query(List.of("vm"), Map.of("owner", "team-compute")));
    }

    private static Problem only(List<Problem> problems) {
        assertEquals(1, problems.size(), problems.toString());
        return problems.get(0);
    }

    @Test
    void aValidGroupIsMadeAtVersionOneWithItsNameInTheAccount() {
        assertEquals(List.of(), valid().problems(CATALOGUE));
        NewGroup body = group("web.tier_1", null, query(List.of("vm", "volume", "vm"), Map.of("owner", "team-compute", "env", "dev")));
        assertEquals(List.of(), body.problems(CATALOGUE));
        ResourceGroup made = ResourceGroup.of("wf", COMPUTE, "global", "user_gonzalo", body, AT);
        assertTrue(ResourceGroup.isGroupId(made.groupId()), made.groupId());
        assertEquals("wfrn:wf:resource-groups:global:" + COMPUTE + ":group/" + made.groupId(), made.wfrn());
        assertEquals("web.tier_1", made.name());
        assertEquals("", made.description(), "no description is an empty one");
        assertEquals(List.of("vm", "volume"), made.query().types(), "each type once, in the order given");
        assertEquals(List.of("env", "owner"), List.copyOf(made.query().tags().keySet()), "tags sorted by name");
        assertEquals(COMPUTE, made.accountId());
        assertEquals("global", made.region());
        assertEquals("user_gonzalo", made.createdBy());
        assertEquals(AT, made.createdAt());
        assertEquals(AT, made.updatedAt());
        assertEquals(1, made.version());
        assertTrue(made.in(COMPUTE, "global"));
        assertFalse(made.in("210987654321", "global"));
        assertFalse(made.in(COMPUTE, "eu-west"));
        assertNotEquals(made.groupId(), ResourceGroup.of("wf", COMPUTE, "global", "user_gonzalo", body, AT).groupId());
        assertEquals(List.of(), group("a", null, query(List.of(), Map.of("owner", "x"))).problems(Set.of()), "no type is every type");
    }

    @Test
    void theNameIsOneTo128LettersDigitsDotsUnderscoresOrDashes() {
        assertEquals("required", only(group(null, null, valid().query()).problems(CATALOGUE)).code());
        assertEquals("/name", only(group("", null, valid().query()).problems(CATALOGUE)).field());
        for (String bad : List.of("web tier", "web/tier", "wéb", "web:tier", "x".repeat(129))) {
            Problem p = only(group(bad, null, valid().query()).problems(CATALOGUE));
            assertEquals(ResourceGroup.NAME_RULE, p.code(), bad);
            assertEquals("/name", p.field());
        }
        assertEquals(List.of(), group("X".repeat(128), null, valid().query()).problems(CATALOGUE));
        assertEquals(List.of(), group("Web-Tier.v2_old", null, valid().query()).problems(CATALOGUE));
    }

    @Test
    void theDescriptionIsAtMost512Characters() {
        assertEquals(List.of(), group("a", "d".repeat(512), valid().query()).problems(CATALOGUE));
        Problem p = only(group("a", "d".repeat(513), valid().query()).problems(CATALOGUE));
        assertEquals("length", p.code());
        assertEquals("/description", p.field());
    }

    @Test
    void anUnknownTypeIsNamedOnItsPointer() {
        Problem p = only(group("a", null, query(List.of("vm", "toaster"), Map.of("owner", "x"))).problems(CATALOGUE));
        assertEquals(GroupQuery.TYPE_UNKNOWN, p.code());
        assertEquals("/query/types/1", p.field());
        assertTrue(p.message().contains("'toaster'"), p.message());
        assertEquals("/query/types/0", only(group("a", null, query(Arrays.asList((String) null), Map.of("owner", "x"))).problems(CATALOGUE)).field());
        assertEquals(GroupQuery.TYPE_UNKNOWN, only(group("a", null, query(List.of("compute/vm"), Map.of("owner", "x"))).problems(CATALOGUE)).code(),
                "a type is its name, as /console/v1/r/{type} takes it");
    }

    @Test
    void aQueryNamesOneToTwentyWellFormedTags() {
        Problem none = only(group("a", null, query(List.of("vm"), Map.of())).problems(CATALOGUE));
        assertEquals(GroupQuery.TAGS_REQUIRED, none.code());
        assertEquals("/query/tags", none.field());
        assertEquals(GroupQuery.TAGS_REQUIRED, only(group("a", null, null).problems(CATALOGUE)).code(), "no query is no tag");

        Map<String, String> twenty = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            twenty.put("tag-" + i, "v");
        }
        assertEquals(List.of(), group("a", null, query(List.of(), twenty)).problems(CATALOGUE));
        Map<String, String> many = new HashMap<>(twenty);
        many.put("tag-20", "v");
        Problem tooMany = only(group("a", null, query(List.of(), many)).problems(CATALOGUE));
        assertEquals("length", tooMany.code());
        assertEquals("/query/tags", tooMany.field());

        Problem upper = only(group("a", null, query(List.of(), Map.of("Owner", "x"))).problems(CATALOGUE));
        assertEquals("tag.name", upper.code());
        assertEquals("/query/tags/Owner", upper.field());
        assertEquals("/query/tags/a~1b~0c", only(group("a", null, query(List.of(), Map.of("a/b~c", "x"))).problems(CATALOGUE)).field(),
                "the pointer escapes / and ~");
        assertEquals("tag.name", only(group("a", null, query(List.of(), Map.of("t".repeat(33), "x"))).problems(CATALOGUE)).code());
        Problem tooLong = only(group("a", null, query(List.of(), Map.of("owner", "v".repeat(257)))).problems(CATALOGUE));
        assertEquals("tag.value", tooLong.code());
        assertEquals("/query/tags/owner", tooLong.field());
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("owner", null);
        assertEquals("tag.value", only(group("a", null, query(List.of(), nullValue)).problems(CATALOGUE)).code());
        assertEquals(List.of(), group("a", null, query(List.of(), Map.of("owner", "v".repeat(256)))).problems(CATALOGUE));
        assertEquals(List.of(), group("a", null, query(List.of(), Map.of("env", ""))).problems(CATALOGUE), "an empty value matches an empty tag");
    }

    @Test
    void theRefusalSaysTheOneProblemOrCountsThem() {
        InvalidInputException one = ResourceGroup.invalid(group("a", null, query(List.of(), Map.of())).problems(CATALOGUE));
        assertEquals("a group's query names at least one tag", one.getMessage());
        List<Problem> several = group("bad name", "d".repeat(600), query(List.of("toaster"), Map.of())).problems(CATALOGUE);
        assertEquals(4, several.size(), several.toString());
        InvalidInputException all = ResourceGroup.invalid(several);
        assertTrue(all.getMessage().startsWith("the group has 4 problems: "), all.getMessage());
        assertEquals(several, all.problems());
    }

    @Test
    void aChangeReplacesTheDescriptionOrTheWholeQueryAndKeepsTheName() {
        ResourceGroup made = ResourceGroup.of("wf", COMPUTE, "global", "user_gonzalo", valid(), AT);
        Instant later = AT.plusSeconds(60);

        ResourceGroup described = made.with(new GroupChanges(null, "all of them", null), later);
        assertEquals("all of them", described.description());
        assertEquals(made.query(), described.query());
        assertEquals(2, described.version());
        assertEquals(later, described.updatedAt());
        assertEquals(AT, described.createdAt());
        assertEquals(made.name(), described.name());
        assertEquals(made.wfrn(), described.wfrn());

        ResourceGroup requeried = described.with(new GroupChanges(made.name(), null, query(List.of(), Map.of("env", "prod"))), later);
        assertEquals(List.of(), requeried.query().types(), "a query replaces the whole query");
        assertEquals(Map.of("env", "prod"), requeried.query().tags());
        assertEquals("all of them", requeried.description());
        assertEquals(3, requeried.version());

        assertSame(requeried, requeried.with(GroupChanges.none(), later), "nothing to change keeps the version");
        assertSame(requeried, requeried.with(new GroupChanges(null, "all of them", query(null, new LinkedHashMap<>(Map.of("env", "prod")))), later));
        assertEquals("", made.with(new GroupChanges(null, "", null), later).description(), "an empty description clears it");
        assertEquals(List.of(), new GroupChanges(made.name(), null, null).problems(made, CATALOGUE), "the group's own name may be sent");

        Problem renamed = only(new GroupChanges("other", null, null).problems(made, CATALOGUE));
        assertEquals(GroupChanges.NAME_IMMUTABLE, renamed.code());
        assertEquals("/name", renamed.field());
        assertEquals("length", only(new GroupChanges(null, "d".repeat(513), null).problems(made, CATALOGUE)).code());
        assertEquals(GroupQuery.TAGS_REQUIRED, only(new GroupChanges(null, null, query(List.of("vm"), Map.of())).problems(made, CATALOGUE)).code());
        assertEquals(GroupQuery.TYPE_UNKNOWN, only(new GroupChanges(null, null, query(List.of("toaster"), Map.of("a", "b"))).problems(made, CATALOGUE)).code());
    }

    @Test
    void theFingerprintTellsTheSameCreateFromAnother() {
        String first = valid().fingerprint();
        assertEquals(first, group("web-tier", "the web VMs", query(List.of("vm", "vm"), Map.of("owner", "team-compute"))).fingerprint());
        assertNotEquals(first, group("web-tier", "other", valid().query()).fingerprint());
        assertNotEquals(first, group("web-tier-2", "the web VMs", valid().query()).fingerprint());
        assertNotEquals(first, group("web-tier", "the web VMs", query(List.of(), Map.of("owner", "team-compute"))).fingerprint());
        assertNotEquals(first, group("web-tier", "the web VMs", query(List.of("vm"), Map.of("owner", "team-data"))).fingerprint());
        assertNotEquals(group("ab", "c", valid().query()).fingerprint(), group("a", "bc", valid().query()).fingerprint());
        assertEquals(group("a", null, valid().query()).fingerprint(), group("a", "", valid().query()).fingerprint());
    }

    @Test
    void aCreateKeyAnswersForADay() {
        assertNull(CreateKey.of(null, "user_gonzalo", "f", AT));
        assertNull(CreateKey.of("  ", "user_gonzalo", "f", AT));
        CreateKey key = CreateKey.of(" k-1 ", "user_gonzalo", "f", AT);
        assertEquals("k-1", key.key());
        assertTrue(key.answers(AT));
        assertTrue(key.answers(AT.plus(CreateKey.WINDOW)));
        assertFalse(key.answers(AT.plus(CreateKey.WINDOW).plusSeconds(1)));
    }

    @Test
    void aGroupIdIsGroupAndALowercaseUlid() {
        String id = ResourceGroup.of("wf", COMPUTE, "global", "user_gonzalo", valid(), AT).groupId();
        assertTrue(id.matches("^group-[0-9a-z]{26}$"), id);
        assertFalse(ResourceGroup.isGroupId(null));
        assertFalse(ResourceGroup.isGroupId("group-"));
        assertFalse(ResourceGroup.isGroupId(id.toUpperCase()));
        assertFalse(ResourceGroup.isGroupId("web-tier"));
        assertFalse(ResourceGroup.isGroupId(id.replace("group-", "grp-")));
    }
}
