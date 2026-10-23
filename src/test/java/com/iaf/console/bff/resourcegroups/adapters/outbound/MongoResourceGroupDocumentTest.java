package com.iaf.console.bff.resourcegroups.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import com.iaf.console.bff.resourcegroups.domain.CreateKey;
import com.iaf.console.bff.resourcegroups.domain.GroupQuery;
import com.iaf.console.bff.resourcegroups.domain.NewGroup;
import com.iaf.console.bff.resourcegroups.domain.ResourceGroup;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.scope.AccountScope;

/** A group as the Mongo store writes and reads it, without a database: what the queries read beside the record, and the record back whole. */
class MongoResourceGroupDocumentTest {

    private static final String COMPUTE = "123456789012";
    private static final Instant AT = Instant.parse("2026-10-23T12:00:00.123456789Z");

    private final Mongo mongo = new Mongo(null);
    private final MongoResourceGroupStore store = new MongoResourceGroupStore(mongo);
    private final AccountScope scope = AccountScope.of(COMPUTE, "global", "user_gonzalo");
    private final ResourceGroup group = ResourceGroup.of("wf", COMPUTE, "global", "user_gonzalo",
            new NewGroup("web-tier", "the web VMs", new GroupQuery(List.of("vm", "volume"), Map.of("owner", "team-compute", "env", "dev"))), AT);

    @Test
    void aGroupIsStoredWithWhatItsQueriesReadAndReadBackWhole() {
        CreateKey key = new CreateKey("k-1", "user_gonzalo", "f00d", AT);
        Document stored = store.document(scope, group, key);
        assertEquals(group.groupId(), stored.get("_id"));
        assertEquals(COMPUTE, stored.get("accountId"));
        assertEquals("global", stored.get("region"));
        assertEquals("web-tier", stored.get("name"));
        assertEquals(1L, stored.get("version"));
        assertEquals("k-1", stored.get(MongoResourceGroupStore.CREATE_KEY, Document.class).get("key"));
        assertEquals("user_gonzalo", stored.get(MongoResourceGroupStore.CREATE_KEY, Document.class).get("principal"));
        assertEquals(group, store.read(stored));
        assertEquals(key, mongo.as(stored.get(MongoResourceGroupStore.CREATE_KEY, Document.class), CreateKey.class));
    }

    @Test
    void aGroupWithoutAKeyStoresNoneAndAnotherAccountsGroupIsRefused() {
        assertFalse(store.document(scope, group, null).containsKey(MongoResourceGroupStore.CREATE_KEY));
        assertThrows(IllegalStateException.class, () -> store.document(AccountScope.of("210987654321", "global", "user_gonzalo"), group, null));
        assertThrows(IllegalStateException.class, () -> store.document(AccountScope.of(COMPUTE, "eu-west", "user_gonzalo"), group, null));
    }
}
