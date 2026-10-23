package com.iaf.console.bff.recent.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.recent.adapters.outbound.InMemoryFavouritesStore;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

/** Decision 32.3: favourite resources, at most 100, a put idempotent. */
class FavouritesTest {

    private static final Instant T0 = Instant.parse("2026-10-23T10:00:00Z");
    private static final String ACCOUNT = "123456789012";

    @Test
    void puttingAFavouriteAgainKeepsWhenItWasAddedAndTakesTheNewName() {
        Favourites.Put first = Favourites.put(List.of(), Favourites.of(vm(1), null, "web-1", T0), Favourites.MAX);
        Favourites.Put again = Favourites.put(first.favourites(), Favourites.of(vm(1), "vm", "web-1 (prod)", T0.plusSeconds(60)), Favourites.MAX);
        assertFalse(again.full());
        assertEquals(1, again.favourites().size());
        assertEquals("web-1 (prod)", again.kept().name());
        assertEquals(T0, again.kept().addedAt());
    }

    @Test
    void theHundredAndFirstIsRefusedButAPutOfOneThereIsNot() {
        List<Favourite> current = new ArrayList<>();
        for (int i = 0; i < Favourites.MAX; i++) {
            current = new ArrayList<>(Favourites.put(current, Favourites.of(vm(i), null, null, T0.plusSeconds(i)), Favourites.MAX).favourites());
        }
        assertEquals(100, current.size());
        Favourites.Put refused = Favourites.put(current, Favourites.of(vm(100), null, null, T0), Favourites.MAX);
        assertTrue(refused.full());
        assertNull(refused.kept());
        assertEquals(100, refused.favourites().size());
        Favourites.Put renamed = Favourites.put(current, Favourites.of(vm(7), null, "seven", T0), Favourites.MAX);
        assertFalse(renamed.full());
        assertEquals("seven", renamed.kept().name());
    }

    @Test
    void theStoreKeepsTheLimitAndListsNewestFirst() {
        InMemoryFavouritesStore store = new InMemoryFavouritesStore();
        AccountScope dana = AccountScope.of(ACCOUNT, "global", "user_dana");
        AccountScope mia = AccountScope.of(ACCOUNT, "global", "user_mia");
        for (int i = 0; i < Favourites.MAX; i++) {
            assertFalse(store.put(dana, Favourites.of(vm(i), null, null, T0.plusSeconds(i)), Favourites.MAX).block().full());
        }
        assertTrue(store.put(dana, Favourites.of(vm(100), null, null, T0), Favourites.MAX).block().full());
        assertFalse(store.put(mia, Favourites.of(vm(100), null, null, T0), Favourites.MAX).block().full(), "the limit is each person's");
        List<Favourite> listed = store.list(dana).block();
        assertEquals(vm(99).format(), listed.get(0).wfrn());
        assertTrue(store.remove(dana, vm(99).format()).block());
        assertFalse(store.remove(dana, vm(99).format()).block());
        assertFalse(store.put(dana, Favourites.of(vm(100), null, null, T0), Favourites.MAX).block().full(), "room again after a removal");
    }

    @Test
    void aFavouriteIsOfTheWfrnsTypeAndNamedByItsIdWhenTheBodyNamesNothing() {
        Favourite plain = Favourites.of(vm(3), null, null, T0);
        assertEquals("vm", plain.type());
        assertEquals("vm_3", plain.name());
        Favourite nested = Favourites.of(Wfrn.parse("wfrn:wf:storage:global:" + ACCOUNT + ":object/bucket-a/logs/2026"), "", " ", T0);
        assertEquals("2026", nested.name());
        InvalidInputException e = assertThrows(InvalidInputException.class, () -> Favourites.of(vm(3), "mongodb", "x".repeat(201), T0));
        assertEquals(List.of("mismatch", "range"), e.problems().stream().map(Problem::code).toList());
    }

    private static Wfrn vm(int i) {
        return Wfrn.parse("wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm_" + i);
    }
}
