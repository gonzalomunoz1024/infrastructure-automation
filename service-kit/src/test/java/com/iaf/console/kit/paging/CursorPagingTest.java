package com.iaf.console.kit.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

class CursorPagingTest {

    @Test
    void pageSizeHasADefaultAndACap() {
        assertThat(CursorPaging.pageSize(null)).isEqualTo(CursorPaging.DEFAULT_PAGE_SIZE);
        assertThat(CursorPaging.pageSize(7)).isEqualTo(7);
        assertThat(CursorPaging.pageSize(5000)).isEqualTo(CursorPaging.MAX_PAGE_SIZE);
        assertThatThrownBy(() -> CursorPaging.pageSize(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cursorsRoundTrip() {
        Cursor numeric = CursorPaging.decode(CursorPaging.encode(1_700_000_000_000_000_000L, "job-1"));
        assertThat(numeric.sortLong()).isEqualTo(1_700_000_000_000_000_000L);
        assertThat(numeric.id()).isEqualTo("job-1");

        Cursor text = CursorPaging.decode(CursorPaging.encode("vm-0042", "r-9"));
        assertThat(text.sortText()).isEqualTo("vm-0042");
        assertThat(text.id()).isEqualTo("r-9");

        Cursor none = CursorPaging.decode(CursorPaging.encode(null, "r-1"));
        assertThat(none.sortValue()).isNull();
        assertThat(none.id()).isEqualTo("r-1");
    }

    @Test
    void cursorsAreOpaqueButUrlSafe() {
        String cursor = CursorPaging.encode(42L, "a/b+c");
        assertThat(cursor).matches("[A-Za-z0-9_-]+");
        assertThat(cursor).doesNotContain("a/b+c");
    }

    @Test
    void aCursorThatWasNotIssuedIsABadRequest() {
        assertThatThrownBy(() -> CursorPaging.decode("not a cursor at all")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CursorPaging.decode("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CursorPaging.decode(null)).isInstanceOf(IllegalArgumentException.class);
        String oneElement = Base64.getUrlEncoder().withoutPadding().encodeToString("[\"x\"]".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CursorPaging.decode(oneElement)).isInstanceOf(IllegalArgumentException.class);
        String notText = Base64.getUrlEncoder().withoutPadding().encodeToString("[1, 2]".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CursorPaging.decode(notText)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPageKeepsTheLimitAndPointsAtItsLastItem() {
        record Row(long at, String id) {
        }
        List<Row> fetched = List.of(new Row(1, "a"), new Row(2, "b"), new Row(3, "c"));
        Page<Row> page = Page.from(fetched, 2, r -> new Cursor(r.at(), r.id()));
        assertThat(page.items()).extracting(Row::id).containsExactly("a", "b");
        assertThat(page.hasNext()).isTrue();
        Cursor next = CursorPaging.decode(page.next());
        assertThat(next.sortLong()).isEqualTo(2);
        assertThat(next.id()).isEqualTo("b");

        Page<Row> last = Page.from(fetched.subList(0, 2), 2, r -> new Cursor(r.at(), r.id()));
        assertThat(last.items()).hasSize(2);
        assertThat(last.next()).isNull();
        assertThat(last.map(Row::id).items()).containsExactly("a", "b");
    }
}
