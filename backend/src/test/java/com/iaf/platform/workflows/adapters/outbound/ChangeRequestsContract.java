package com.iaf.platform.workflows.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

/** What every {@link ChangeRequestPort} does, in memory and in a database. */
abstract class ChangeRequestsContract {

    static final Instant T0 = Instant.parse("2026-10-18T09:15:30.123456789Z");

    ChangeRequestPort store;

    abstract ChangeRequestPort newStore();

    /** A new store over the same storage, as after a restart; null when the storage does not outlive the store. */
    abstract ChangeRequestPort reopen();

    @BeforeEach
    void setUp() {
        store = newStore();
    }

    static ChangeRequest cr(String id, Instant createdAt) {
        return new ChangeRequest(id, ChangeRequest.Kind.WORKFLOW, "vm-restart", "1.3.0", "change/vm-restart-1.3.0", "Restart: wait for tools",
                "ana", ChangeRequest.Status.OPEN,
                List.of(new Check("schema", "Schema", "passed", null), new Check("replay", "Replay", "failed", "step 3 differs")),
                "canary", "kind: Workflow\n", "+ wait: 30s\n", createdAt, null, null, "https://git.example.com/pr/" + id);
    }

    @Test
    void readsAChangeRequestBackExactly() {
        ChangeRequest saved = cr("cr_1", T0);
        store.save(saved);
        assertThat(store.get("cr_1")).contains(saved);
        assertThat(store.get("cr_9")).isEmpty();
        assertThat(store.get(null)).isEmpty();
    }

    @Test
    void listsNewestFirstWithUndatedOnesLast() {
        store.save(cr("cr_old", T0.minusSeconds(3600)));
        store.save(cr("cr_undated", null));
        store.save(cr("cr_new", T0));
        store.save(cr("cr_close", T0.minusNanos(1)));
        assertThat(store.all()).extracting(ChangeRequest::id).containsExactly("cr_new", "cr_close", "cr_old", "cr_undated");
    }

    @Test
    void saveReplacesTheChangeRequestWithTheSameId() {
        store.save(cr("cr_1", T0));
        ChangeRequest merged = cr("cr_1", T0).withStatus(ChangeRequest.Status.MERGED, T0.plusSeconds(90), "gonzalo");
        store.save(merged);
        assertThat(store.all()).containsExactly(merged);
        assertThat(store.get("cr_1")).map(ChangeRequest::mergedBy).contains("gonzalo");
    }

    @Test
    void keepsWhatItWroteAcrossARestart() {
        ChangeRequest saved = cr("cr_1", T0);
        store.save(saved);
        ChangeRequestPort after = reopen();
        assumeTrue(after != null, "this store does not outlive the process");
        assertThat(after.all()).containsExactly(saved);
    }
}
