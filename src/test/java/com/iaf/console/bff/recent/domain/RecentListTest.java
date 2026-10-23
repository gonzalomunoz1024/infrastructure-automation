package com.iaf.console.bff.recent.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/** Decision 32.3: the recent pages, newest first, each once, at most 20. */
class RecentListTest {

    private static final Instant T0 = Instant.parse("2026-10-23T10:00:00Z");
    private static final String ACCOUNT = "123456789012";

    @Test
    void aVisitMovesItsPageToTheTopWithItsNewTitle() {
        List<RecentItem> list = List.of();
        list = RecentList.visit(list, item("type", "compute/vm", "Virtual machines", 0), RecentList.MAX);
        list = RecentList.visit(list, item("resource", vm(1), "web-1", 1), RecentList.MAX);
        list = RecentList.visit(list, item("type", "data/mongodb", "MongoDB", 2), RecentList.MAX);
        list = RecentList.visit(list, item("type", "compute/vm", "VMs", 3), RecentList.MAX);
        assertEquals(List.of("compute/vm", "data/mongodb", vm(1)), list.stream().map(RecentItem::key).toList());
        assertEquals("VMs", list.get(0).title());
        assertEquals(T0.plusSeconds(3), list.get(0).visitedAt());
        list = RecentList.visit(list, item("resource", "compute/vm", "not the type", 4), RecentList.MAX);
        assertEquals(4, list.size(), "a resource and a type with one key are two pages");
    }

    @Test
    void pastTwentyTheOldestGo() {
        List<RecentItem> list = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            list = new ArrayList<>(RecentList.visit(list, item("resource", vm(i), "vm " + i, i), RecentList.MAX));
        }
        assertEquals(20, list.size());
        assertEquals(vm(24), list.get(0).key());
        assertEquals(vm(5), list.get(19).key());
        for (int i = 1; i < list.size(); i++) {
            assertTrue(list.get(i - 1).visitedAt().isAfter(list.get(i).visitedAt()), "newest first");
        }
    }

    @Test
    void aVisitIsATypeKeyOrAWfrnOfTheSessionsAccountWithATitle() {
        RecentItem ok = RecentList.visitOf("resource", vm(1), "  web-1 ", ACCOUNT, T0);
        assertEquals("web-1", ok.title());
        assertEquals(List.of("enum"), codes(() -> RecentList.visitOf("page", "x", "t", ACCOUNT, T0)));
        assertEquals(List.of("pattern"), codes(() -> RecentList.visitOf("type", "Compute/VM", "t", ACCOUNT, T0)));
        assertEquals(List.of("pattern"), codes(() -> RecentList.visitOf("resource", "vm_1", "t", ACCOUNT, T0)));
        assertEquals(List.of("account"), codes(() -> RecentList.visitOf("resource", "wfrn:wf:compute:global:210987654321:vm/vm_1", "t", ACCOUNT, T0)));
        assertEquals(List.of("required", "required"), codes(() -> RecentList.visitOf("type", null, " ", ACCOUNT, T0)));
        assertEquals(List.of("range"), codes(() -> RecentList.visitOf("type", "compute/vm", "x".repeat(201), ACCOUNT, T0)));
    }

    @Test
    void aPageIsShownOnItsTypesListInItsRegion() {
        ListCheck type = RecentList.checkOf(new RecentItem("type", "compute/vm", "VMs", T0), "eu-west");
        assertEquals("compute:list", type.action());
        assertEquals("wfrn:wf:compute:eu-west:" + ACCOUNT + ":vm/*", type.resource("wf", ACCOUNT));
        ListCheck resource = RecentList.checkOf(new RecentItem("resource", "wfrn:wf:data:us-east:" + ACCOUNT + ":mongodb/db_1", "db", T0), "eu-west");
        assertEquals("data:list", resource.action());
        assertEquals("wfrn:wf:data:us-east:" + ACCOUNT + ":mongodb/*", resource.resource("wf", ACCOUNT));
    }

    private static List<String> codes(Runnable call) {
        InvalidInputException e = assertThrows(InvalidInputException.class, call::run);
        return e.problems().stream().map(Problem::code).toList();
    }

    private static String vm(int i) {
        return "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm_" + i;
    }

    private static RecentItem item(String kind, String key, String title, int second) {
        return new RecentItem(kind, key, title, T0.plusSeconds(second));
    }
}
