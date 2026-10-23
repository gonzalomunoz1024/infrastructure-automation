package com.iaf.console.bff.demo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.iaf.console.bff.demo.search.domain.DemoInterpreter;
import com.iaf.console.bff.demo.search.domain.DemoInterpreter.Filter;
import com.iaf.console.bff.demo.search.domain.DemoInterpreter.Reading;

/**
 * The demo's plain English readings: types by name, plural and title (one typo forgiven), states
 * by their everyday names, drift, tags, regions, times, sorts and the query syntax, and what it
 * leaves out, with how sure it is and one sentence that says how it read the text.
 */
class DemoInterpreterTest {

    /** A Friday: this week started on Monday 2026-10-19. */
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final DemoInterpreter.Vocabulary WORDS = new DemoInterpreter.Vocabulary(List.of(
            new DemoInterpreter.TypeName("vm", "compute", "vms", "Virtual machines"),
            new DemoInterpreter.TypeName("database", "data", "databases", "Databases"),
            new DemoInterpreter.TypeName("volume", "storage", "volumes", "Volumes"),
            new DemoInterpreter.TypeName("image", "compute", "images", "Images")),
            Map.of("env", Set.of("prod", "dev"), "team", Set.of("payments", "data-platform")), Set.of("global", "eu-west-1"));

    private final List<String> counted = new ArrayList<>();

    /** Seven resources for every filter but one whose free text holds "zzz", which lists none. */
    private Reading read(String text, String listType) {
        return DemoInterpreter.read(text, listType, WORDS, NOW, (type, filter) -> {
            counted.add(type + " " + filter);
            return filter.q() != null && filter.q().contains("zzz") ? 0 : 7;
        });
    }

    private Reading read(String text) {
        return read(text, null);
    }

    @Test
    void theContractsExampleIsReadWhole() {
        Reading r = read("failed vms tagged env=prod created this week, newest first");
        assertEquals("vm", r.type());
        assertEquals("type:vm state:failed tag:env=prod created:>=2026-10-19", r.q());
        assertEquals(new Filter("created:>=2026-10-19", "failed", null, null, Map.of("env", "prod")), r.filter());
        assertEquals("-created", r.sort());
        assertEquals("Virtual machines that failed, tagged env=prod, created this week (since 2026-10-19), newest first.", r.explanation());
        assertEquals(DemoInterpreter.HIGH, r.confidence());
        assertNull(r.unparsed());
        assertEquals(7, r.total());
        assertTrue(counted.get(counted.size() - 1).startsWith("vm "), "the total is the type's list: " + counted);
    }

    @Test
    void statesAndDriftGoByTheirEverydayNames() {
        Reading broken = read("broken databases");
        assertEquals("database", broken.type());
        assertEquals("failed", broken.filter().state());
        assertEquals("Databases that failed.", broken.explanation());
        assertEquals("active", read("running vms").filter().state());
        Reading going = read("vms being deleted");
        assertEquals("deleting", going.filter().state());
        assertEquals("Virtual machines that are being deleted.", going.explanation());
        assertEquals("deleted", read("removed volumes").filter().state());

        assertEquals(Boolean.TRUE, read("drifted vms").filter().drift());
        assertEquals(Boolean.TRUE, read("vms changed outside the console").filter().drift());
        Reading inSync = read("vms in sync");
        assertEquals(Boolean.FALSE, inSync.filter().drift());
        assertEquals("type:vm drift:false", inSync.q());
        assertEquals("Virtual machines in sync.", inSync.explanation());
        assertEquals(Boolean.FALSE, read("databases not drifted").filter().drift());
    }

    @Test
    void typesGoByNamePluralAndTitleWithOneTypoForgivenFromFiveLetters() {
        for (String text : List.of("vm", "vms", "virtual machines", "Virtual Machine", "show me all the VMs")) {
            Reading r = read(text);
            assertEquals("vm", r.type(), text);
            assertEquals(DemoInterpreter.HIGH, r.confidence(), text);
        }
        Reading typo = read("databse");
        assertEquals("database", typo.type());
        assertEquals(DemoInterpreter.MEDIUM, typo.confidence(), "a typo was fixed");
        assertEquals("vm", read("virtual machnes").type());
        Reading tooShort = read("vns");
        assertNull(tooShort.type(), "no typo is forgiven below five letters");
        assertEquals("vns", tooShort.filter().q(), "so it is free text");
    }

    @Test
    void timesBecomeCreatedAndUpdatedTerms() {
        assertTime("vms created today", "created:2026-10-23", "created today");
        assertTime("vms made yesterday", "created:2026-10-22", "created yesterday");
        assertTime("vms in the last 24 hours", "created:24h", "created in the last 24 hours");
        assertTime("vms created in the past hour", "created:1h", "created in the last hour");
        assertTime("vms updated in the last 7 days", "updated:7d", "updated in the last 7 days");
        assertTime("vms since October 1", "created:>=2026-10-01", "created since 2026-10-01");
        assertTime("vms since 1st December", "created:>=2025-12-01", "created since 2025-12-01");
        assertTime("vms created before 2026-09-01", "created:<2026-09-01", "created before 2026-09-01");
        assertTime("vms older than 90 days", "created:<90d", "created more than 90 days ago");
        assertTime("vms updated more than two weeks ago", "updated:<2w", "updated more than 2 weeks ago");
        assertTime("vms not updated in 30 days", "updated:<30d", "not updated in 30 days");
        assertTime("vms untouched for 3 weeks", "updated:<3w", "not updated in 3 weeks");
        assertTime("vms created last week", "created:2026-10-12..2026-10-18", "created last week (2026-10-12 to 2026-10-18)");
        assertTime("vms this month", "created:>=2026-10-01", "created this month (since 2026-10-01)");
        assertTime("vms created between Oct 1 and Oct 7", "created:2026-10-01..2026-10-07", "created between 2026-10-01 and 2026-10-07");

        Reading both = read("vms created this week not updated in 2 days");
        assertEquals("created:>=2026-10-19 updated:<2d", both.filter().q(), "two times, each kept");
        assertEquals(DemoInterpreter.HIGH, both.confidence());
    }

    private void assertTime(String text, String term, String said) {
        Reading r = read(text);
        assertEquals(term, r.filter().q(), text);
        assertEquals("type:vm " + term, r.q(), text);
        assertTrue(r.explanation().contains(said), text + ": " + r.explanation());
        assertEquals(DemoInterpreter.HIGH, r.confidence(), text);
    }

    @Test
    void sortsAndASecondSortIsLeftOut() {
        assertEquals("created", read("vms oldest first").sort());
        assertEquals("name", read("databases by name").sort());
        assertEquals("Databases, by name.", read("databases by name").explanation());
        assertEquals("-name", read("vms z to a").sort());
        Reading two = read("vms newest first by name");
        assertEquals("-created", two.sort());
        assertEquals(List.of("by name"), two.unparsed());
        assertEquals(DemoInterpreter.LOW, two.confidence());
    }

    @Test
    void tagsByNameAndValueOrTheAccountsOwnNames() {
        Reading named = read("vms tagged team payments");
        assertEquals(Map.of("team", "payments"), named.filter().tags());
        assertEquals(Map.of("team", "payments"), read("team payments databases").filter().tags());
        Reading any = read("vms tagged env");
        assertEquals(Map.of("env", ""), any.filter().tags(), "an empty value is any value");
        assertEquals("type:vm tag:env", any.q());
        assertEquals("Virtual machines tagged env (any value).", any.explanation());
        assertEquals(Map.of("env", ""), read("vms env=").filter().tags());
        Reading two = read("Env=prod owner=team-a vms");
        assertEquals(Map.of("env", "prod", "owner", "team-a"), two.filter().tags());
        assertEquals("Virtual machines tagged env=prod and owner=team-a.", two.explanation());
        Reading clash = read("env=prod env=dev vms");
        assertEquals(Map.of("env", "prod"), clash.filter().tags());
        assertEquals(List.of("env=dev"), clash.unparsed(), "a second value of one tag cannot hold");
        assertEquals(List.of("Bad_Name=x"), read("vms Bad_Name=x").unparsed(), "a tag name the syntax refuses");
    }

    @Test
    void regionsTheAccountHas() {
        assertEquals("eu-west-1", read("vms in eu-west-1").filter().region());
        Reading global = read("databases region global");
        assertEquals("global", global.filter().region());
        assertEquals("type:database region:global", global.q());
        assertEquals("Databases in global.", global.explanation());
    }

    @Test
    void quotedTextIsFreeTextAsTypedAndOtherWordsWhileTheyListSomething() {
        Reading quoted = read("\"blue green\" vms");
        assertEquals("\"blue green\"", quoted.filter().q());
        assertEquals(DemoInterpreter.MEDIUM, quoted.confidence(), "some words became free text");
        assertEquals("Virtual machines matching \"blue green\".", quoted.explanation());

        Reading web = read("web vms");
        assertEquals("web", web.filter().q());
        assertEquals("type:vm web", web.q());
        assertEquals(DemoInterpreter.MEDIUM, web.confidence());

        Reading nothing = read("zzz failed vms");
        assertEquals(List.of("zzz"), nothing.unparsed(), "a guess that would list nothing is left out");
        assertNull(nothing.filter().q());
        assertEquals("type:vm state:failed", nothing.q());
        assertEquals(DemoInterpreter.LOW, nothing.confidence());

        assertEquals(DemoInterpreter.LOW, read("web").confidence(), "free text alone");
    }

    @Test
    void unionsNegationsAndSecondsAreLeftOut() {
        Reading union = read("failed or deleting vms");
        assertEquals("failed", union.filter().state());
        assertEquals(List.of("or deleting"), union.unparsed());
        assertEquals(DemoInterpreter.LOW, union.confidence());
        Reading not = read("vms not being deleted");
        assertNull(not.filter().state());
        assertEquals(List.of("not being deleted"), not.unparsed());
        assertEquals(List.of("running"), read("failed running vms").unparsed(), "a second state");
        Reading types = read("vms and databases");
        assertEquals("vm", types.type());
        assertEquals(List.of("databases"), types.unparsed(), "a second type");
    }

    @Test
    void onAListTheReadingStaysInItsType() {
        Reading same = read("failed vms", "vm");
        assertEquals("vm", same.type());
        assertEquals(DemoInterpreter.HIGH, same.confidence());
        Reading other = read("failed databases", "vm");
        assertEquals("vm", other.type(), "the list's type");
        assertEquals(List.of("databases"), other.unparsed());
        assertEquals(DemoInterpreter.LOW, other.confidence(), "the text named another type than the list's");
        assertEquals("Virtual machines that failed.", other.explanation());
        Reading plain = read("newest first", "volume");
        assertEquals("type:volume", plain.q());
        assertEquals("Volumes, newest first.", plain.explanation());
    }

    @Test
    void fillerAloneAndTheSyntaxItself() {
        Reading filler = read("show me everything");
        assertEquals("", filler.q());
        assertNull(filler.type());
        assertEquals("Resources.", filler.explanation());
        assertEquals(DemoInterpreter.MEDIUM, filler.confidence(), "nothing but filler was typed");

        Reading syntax = read("type:vm state:failed tag:env=prod created:<30d wfrn:wf:compute:*");
        assertEquals("type:vm state:failed tag:env=prod created:<30d wfrn:wf:compute:*", syntax.q());
        assertEquals("created:<30d wfrn:wf:compute:*", syntax.filter().q());
        assertEquals(DemoInterpreter.HIGH, syntax.confidence());
        assertTrue(syntax.explanation().contains("created more than 30 days ago"), syntax.explanation());
        assertEquals(List.of("state:sideways"), read("vms state:sideways").unparsed());
        assertEquals(List.of("created:2026-10-01T12:00Z"), read("vms created:2026-10-01T12:00Z").unparsed(), "a time alone needs a comparison");
    }

    @Test
    void theAnswerIsTheIndexersInterpretationShape() throws Exception {
        JsonNode json = new ObjectMapper().valueToTree(read("vms in sync"));
        assertEquals("type:vm drift:false", json.path("q").asText());
        assertFalse(json.path("filter").path("drift").asBoolean(true), "false is kept: " + json);
        assertTrue(json.path("filter").has("drift"));
        assertFalse(json.path("filter").has("tags"), "absent when the reading sets none: " + json);
        assertFalse(json.has("unparsed"));
        assertFalse(json.has("sort"));
        assertEquals(7, json.path("total").asInt());
        assertEquals("high", json.path("confidence").asText());
        JsonNode untyped = new ObjectMapper().valueToTree(read("show me everything"));
        assertFalse(untyped.has("type"), untyped.toString());
        assertTrue(untyped.has("q"), "q is always there: " + untyped);
    }
}
