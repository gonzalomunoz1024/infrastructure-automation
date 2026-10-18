package com.iaf.platform.identity.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** What every {@link ServiceAccountStore} does, in memory and in a database. */
abstract class ServiceAccountStoreContract {

    static final Instant NOW = Instant.parse("2026-10-17T12:00:00.123456789Z");

    /** An empty store. */
    abstract ServiceAccountStore newStore();

    /** A new store over the same storage as the last one made; null when the storage does not outlive the store. */
    abstract ServiceAccountStore reopen();

    static ServiceAccount account(String id, String token, Instant createdAt) {
        return new ServiceAccount(id, "agent " + id, List.of("ops"), List.of("team-compute", "team-data"), WorkflowYaml.sha256(token), createdAt);
    }

    @Test
    void findsAnAccountByTheHashOfItsToken() {
        ServiceAccountStore store = newStore();
        ServiceAccount agent = account("sa_1", "iaf_secret-one", NOW);
        store.save(agent);
        store.save(account("sa_2", "iaf_secret-two", NOW.plusNanos(1)));

        assertThat(store.byTokenHash(WorkflowYaml.sha256("iaf_secret-one"))).contains(agent);
        assertThat(store.byTokenHash(WorkflowYaml.sha256("iaf_unknown"))).isEmpty();
        assertThat(store.byTokenHash("iaf_secret-one")).as("the token itself finds nothing").isEmpty();
    }

    @Test
    void listsEveryAccount() {
        ServiceAccountStore store = newStore();
        assertThat(store.all()).isEmpty();
        ServiceAccount first = account("sa_1", "iaf_a", NOW);
        ServiceAccount second = account("sa_2", "iaf_b", NOW.plusSeconds(1));
        store.save(second);
        store.save(first);
        assertThat(store.all()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void savingAgainReplacesTheAccount() {
        ServiceAccountStore store = newStore();
        store.save(account("sa_1", "iaf_old", NOW));
        ServiceAccount rotated = account("sa_1", "iaf_new", NOW);
        store.save(rotated);
        assertThat(store.all()).containsExactly(rotated);
        assertThat(store.byTokenHash(WorkflowYaml.sha256("iaf_old"))).isEmpty();
        assertThat(store.byTokenHash(WorkflowYaml.sha256("iaf_new"))).contains(rotated);
    }

    @Test
    void keepsAccountsAcrossARestart() {
        ServiceAccountStore store = newStore();
        ServiceAccount agent = account("sa_1", "iaf_secret-one", NOW);
        store.save(agent);
        ServiceAccountStore after = reopen();
        assumeTrue(after != null, "this store does not outlive the process");
        assertThat(after.byTokenHash(WorkflowYaml.sha256("iaf_secret-one"))).contains(agent);
        assertThat(after.all()).containsExactly(agent);
    }
}
