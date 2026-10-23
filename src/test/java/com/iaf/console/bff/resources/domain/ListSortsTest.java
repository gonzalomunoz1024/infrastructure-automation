package com.iaf.console.bff.resources.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.InvalidInputException;

class ListSortsTest {

    @Test
    void theConsolesSortsBecomeTheStateServices() {
        assertEquals("name", ListSorts.toState("name"));
        assertEquals("-name", ListSorts.toState("-name"));
        assertEquals("-createdAt", ListSorts.toState("-created"));
        assertEquals("createdAt", ListSorts.toState("created"));
        assertEquals("updatedAt", ListSorts.toState("updated"));
        assertEquals("-updatedAt", ListSorts.toState("-updatedAt"), "the state service's own names pass");
        assertNull(ListSorts.toState(null));
        assertNull(ListSorts.toState(" "));
    }

    @Test
    void anUnknownSortIsRefused() {
        assertThrows(InvalidInputException.class, () -> ListSorts.toState("sideways"));
        assertThrows(InvalidInputException.class, () -> ListSorts.toState("cpu"));
    }
}
