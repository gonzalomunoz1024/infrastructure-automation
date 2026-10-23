package com.iaf.console.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.identity.accesskeys.adapters.mongo.MongoAccessKeyStore;
import com.iaf.console.identity.accesskeys.domain.AccessKey;
import com.iaf.console.identity.accesskeys.domain.AccessKeyStatus;
import com.iaf.console.identity.accessrequests.adapters.mongo.MongoAccessRequestStore;
import com.iaf.console.identity.accessrequests.domain.AccessRequest;
import com.iaf.console.identity.accounts.adapters.mongo.MongoAccountStore;
import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.domain.AccountStatus;
import com.iaf.console.identity.authz.adapters.mongo.MongoAuthzVersions;
import com.iaf.console.identity.common.idempotency.IdempotencyPort;
import com.iaf.console.identity.common.idempotency.IdempotencyPort.Outcome;
import com.iaf.console.identity.common.idempotency.MongoIdempotency;
import com.iaf.console.identity.iam.adapters.mongo.MongoGroupStore;
import com.iaf.console.identity.iam.adapters.mongo.MongoGuardrailStore;
import com.iaf.console.identity.iam.adapters.mongo.MongoPolicyStore;
import com.iaf.console.identity.iam.adapters.mongo.MongoRoleStore;
import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.identity.iam.domain.Guardrail;
import com.iaf.console.identity.iam.domain.Policy;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.domain.Statement;
import com.iaf.console.identity.principals.adapters.mongo.MongoMembershipStore;
import com.iaf.console.identity.principals.adapters.mongo.MongoUserStore;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.domain.User;
import com.iaf.console.identity.principals.domain.UserStatus;
import com.iaf.console.identity.quotas.adapters.mongo.MongoQuotaStore;
import com.iaf.console.identity.quotas.domain.QuotaOverride;
import com.iaf.console.identity.quotas.domain.QuotaRequest;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;

import reactor.core.publisher.Mono;

/** The MongoDB adapters against a real database (CI's mongo:7), each in a collection of its own database. */
@EnabledIf("com.iaf.console.identity.MongoSupport#available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoStoresTest {

    static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    static final String ACCOUNT = "123456789012";
    static final String OTHER = "210987654321";
    static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "usr_test");
    static final AccountScope OTHER_SCOPE = AccountScope.of(OTHER, "global", "usr_test");

    MongoClient client;
    String database;
    Mongo mongo;

    @BeforeAll
    void connect() {
        client = MongoClients.create(MongoSupport.uri());
        database = MongoSupport.freshDatabase("console_identity_stores");
        mongo = new Mongo(client.getDatabase(database));
    }

    @AfterAll
    void drop() {
        Mono.from(client.getDatabase(database).drop()).block();
        client.close();
    }

    @Test
    void accounts() {
        MongoAccountStore store = new MongoAccountStore(mongo);
        store.ensureIndexes().block();
        Account a = Account.create(ACCOUNT, "Compute", "team-compute", "global", "sa-1", NOW);
        Account b = Account.create(OTHER, "Data", "team-data", "global", "sa-2", NOW);
        assertThat(store.insert(a).block()).isEqualTo(a);
        assertThat(store.insert(b).block()).isEqualTo(b);
        assertThatThrownBy(() -> store.insert(Account.create("300000000000", "Compute", "t", "global", "sa-3", NOW)).block()).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> store.insert(Account.create(ACCOUNT, "Again", "t", "global", "sa-3", NOW)).block()).isInstanceOf(ConflictException.class);

        assertThat(store.find(SCOPE).block()).isEqualTo(a);
        assertThat(store.findById(OTHER).block()).isEqualTo(b);
        assertThat(store.findById("999999999999").block()).isNull();
        assertThat(store.existsByName("Compute").block()).isTrue();
        assertThat(store.existsByName("Nope").block()).isFalse();

        Account renamed = a.changed("Compute 2", AccountStatus.suspended, "global", NOW.plusSeconds(1));
        assertThat(store.update(renamed, 1).block()).isEqualTo(renamed);
        assertThat(store.update(renamed.changed("Stale", AccountStatus.active, "global", NOW), 1).block()).as("a stale version updates nothing").isNull();
        assertThat(store.findById(ACCOUNT).block().version()).isEqualTo(2);
        assertThatThrownBy(() -> store.update(store.findById(ACCOUNT).block().changed("Data", AccountStatus.active, "global", NOW), 2).block())
                .isInstanceOf(ConflictException.class);

        Page<Account> first = store.list(null, 1, null).block();
        assertThat(first.items()).containsExactly(renamed);
        assertThat(first.hasNext()).isTrue();
        Page<Account> second = store.list(null, 1, first.next()).block();
        assertThat(second.items()).containsExactly(b);
        assertThat(second.hasNext()).isFalse();
        assertThat(store.list(AccountStatus.suspended, 10, null).block().items()).containsExactly(renamed);
        assertThat(store.listByIds(List.of(OTHER, ACCOUNT, "999999999999")).block()).containsExactlyInAnyOrder(renamed, b);
        assertThat(store.findAll().collectList().block()).containsExactlyInAnyOrder(renamed, b);
    }

    @Test
    void usersAndMemberships() {
        MongoUserStore users = new MongoUserStore(mongo);
        MongoMembershipStore memberships = new MongoMembershipStore(mongo);
        users.ensureIndexes().block();
        memberships.ensureIndexes().block();
        User dana = User.create("usr_dana", "dana@example.com", "Dana", null, null, NOW);
        User mia = User.create("usr_mia", "mia@example.com", "Mia", "https://idp", "mia", NOW);
        users.insert(dana).block();
        users.insert(mia).block();
        assertThatThrownBy(() -> users.insert(User.create("usr_dup", "dana@example.com", "Dup", null, null, NOW)).block()).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> users.insert(User.create("usr_dup", "dup@example.com", "Dup", "https://idp", "mia", NOW)).block())
                .isInstanceOf(ConflictException.class);
        assertThat(users.findById("usr_dana").block()).isEqualTo(dana);
        assertThat(users.findByEmail("DANA@example.com").block()).as("emails are case-insensitive").isEqualTo(dana);
        assertThat(users.findByIssuerAndSubject("https://idp", "mia").block()).isEqualTo(mia);
        assertThat(users.findByIssuerAndSubject("https://idp", "dana").block()).isNull();
        User linked = dana.signedIn("https://idp", "dana", "Dana R", NOW.plusSeconds(5));
        assertThat(users.save(linked).block()).isEqualTo(linked);
        assertThat(users.findByIssuerAndSubject("https://idp", "dana").block()).isEqualTo(linked);
        assertThat(users.findByIds(List.of("usr_mia", "usr_dana", "usr_nobody")).collectList().block()).containsExactlyInAnyOrder(linked, mia);
        assertThat(users.findAll().collectList().block()).hasSize(2);

        Membership m1 = Membership.create(ACCOUNT, linked, List.of("grp_owners"), NOW);
        Membership m2 = Membership.create(ACCOUNT, mia, List.of("grp_viewers", "grp_sre"), NOW);
        Membership m3 = Membership.create(OTHER, linked, List.of("grp_members"), NOW);
        memberships.insert(m1).block();
        memberships.insert(m2).block();
        memberships.insert(m3).block();
        assertThatThrownBy(() -> memberships.insert(m1).block()).isInstanceOf(ConflictException.class);
        assertThat(memberships.find(SCOPE, "usr_dana").block()).isEqualTo(m1);
        assertThat(memberships.find(OTHER_SCOPE, "usr_mia").block()).isNull();
        assertThat(memberships.findAll(SCOPE).collectList().block()).containsExactlyInAnyOrder(m1, m2);
        assertThat(memberships.findByGroup(SCOPE, "grp_sre").collectList().block()).containsExactly(m2);
        assertThat(memberships.countByGroup(SCOPE, "grp_viewers").block()).isEqualTo(1);
        assertThat(memberships.countByGroup(SCOPE, "grp_nobody").block()).isZero();
        assertThat(memberships.findByUser("usr_dana").collectList().block()).containsExactlyInAnyOrder(m1, m3);
        assertThat(memberships.findEverything().collectList().block()).hasSize(3);
        assertThat(memberships.list(SCOPE, "mi", 10, null).block().items()).containsExactly(m2);
        assertThat(memberships.list(SCOPE, "dana R", 10, null).block().items()).containsExactly(m1);
        Page<Membership> page = memberships.list(SCOPE, null, 1, null).block();
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasNext()).isTrue();
        assertThat(memberships.list(SCOPE, null, 1, page.next()).block().hasNext()).isFalse();

        Membership changed = m2.changed("Mia C", UserStatus.suspended, List.of("grp_viewers"), NOW.plusSeconds(1));
        assertThat(memberships.update(changed, 1).block()).isEqualTo(changed);
        assertThat(memberships.update(changed, 1).block()).isNull();
        memberships.updateUserFields("usr_dana", "dana.r@example.com", "Dana Reyes").block();
        assertThat(memberships.find(SCOPE, "usr_dana").block().email()).isEqualTo("dana.r@example.com");
        assertThat(memberships.find(OTHER_SCOPE, "usr_dana").block().name()).isEqualTo("Dana Reyes");
        assertThat(memberships.delete(SCOPE, "usr_mia").block()).isTrue();
        assertThat(memberships.delete(SCOPE, "usr_mia").block()).isFalse();
        assertThat(memberships.findAll(SCOPE).collectList().block()).hasSize(1);
    }

    @Test
    void groupsRolesAndPolicies() {
        MongoGroupStore groups = new MongoGroupStore(mongo);
        MongoRoleStore roles = new MongoRoleStore(mongo);
        MongoPolicyStore policies = new MongoPolicyStore(mongo);
        groups.ensureIndexes().block();
        roles.ensureIndexes().block();
        policies.ensureIndexes().block();

        Policy p = Policy.create("pol_1", ACCOUNT, "no-deletes", "No deletes", List.of(Statement.deny("s0", List.of("*:delete"), List.of("wfrn:wf:*"))), false, NOW);
        policies.insert(p).block();
        assertThatThrownBy(() -> policies.insert(Policy.create("pol_2", ACCOUNT, "no-deletes", null, List.of(), false, NOW)).block())
                .isInstanceOf(ConflictException.class);
        assertThat(policies.insert(Policy.create("pol_3", OTHER, "no-deletes", null, List.of(), false, NOW)).block()).as("names are unique per account").isNotNull();
        assertThat(policies.find(SCOPE, "pol_1").block()).isEqualTo(p);
        assertThat(policies.find(OTHER_SCOPE, "pol_1").block()).isNull();
        assertThat(policies.findByName(SCOPE, "no-deletes").block()).isEqualTo(p);
        assertThat(policies.findByName(SCOPE, "nope").block()).isNull();
        Policy p2 = new Policy("pol_1", ACCOUNT, "no-vm-deletes", "Renamed", p.statements(), false, 2, NOW, NOW.plusSeconds(1));
        assertThat(policies.update(p2, 1).block()).isEqualTo(p2);
        assertThat(policies.update(p2, 1).block()).isNull();
        assertThat(policies.findAll(SCOPE).collectList().block()).containsExactly(p2);
        assertThat(policies.findEverything().collectList().block()).hasSize(2);

        Role r = Role.create("rol_1", ACCOUNT, "careful", "Careful", List.of("pol_1"), false, NOW);
        roles.insert(r).block();
        roles.insert(Role.create("rol_2", ACCOUNT, "another", null, List.of(), false, NOW)).block();
        assertThatThrownBy(() -> roles.insert(Role.create("rol_3", ACCOUNT, "careful", null, List.of(), false, NOW)).block()).isInstanceOf(ConflictException.class);
        Page<Role> first = roles.list(SCOPE, 1, null).block();
        assertThat(first.items()).hasSize(1);
        assertThat(first.hasNext()).isTrue();
        Page<Role> second = roles.list(SCOPE, 1, first.next()).block();
        assertThat(second.items()).hasSize(1);
        assertThat(second.hasNext()).isFalse();
        assertThat(first.items().get(0)).isNotEqualTo(second.items().get(0));
        assertThat(roles.findByName(SCOPE, "careful").block()).isEqualTo(r);
        assertThat(roles.delete(SCOPE, "rol_2").block()).isTrue();
        assertThat(roles.delete(SCOPE, "rol_2").block()).isFalse();
        assertThat(roles.findAll(SCOPE).collectList().block()).containsExactly(r);

        Role bounded = Role.create("rol_4", ACCOUNT, "bounded", null, List.of("pol_1"), false, NOW).bounded("pol_1");
        roles.insert(bounded).block();
        assertThat(roles.findByName(SCOPE, "bounded").block()).as("the boundary is kept").isEqualTo(bounded);
        assertThat(roles.delete(SCOPE, "rol_4").block()).isTrue();

        Group g = Group.create("grp_1", ACCOUNT, "sre", "SRE", List.of("rol_1"), List.of("corp-sre"), false, NOW);
        groups.insert(g).block();
        groups.insert(Group.create("grp_2", OTHER, "sre", null, List.of(), List.of("corp-sre", "corp-data"), false, NOW)).block();
        assertThatThrownBy(() -> groups.insert(Group.create("grp_3", ACCOUNT, "sre", null, List.of(), List.of(), false, NOW)).block())
                .isInstanceOf(ConflictException.class);
        assertThat(groups.findByIdpGroups(List.of("corp-sre")).collectList().block()).hasSize(2);
        assertThat(groups.findByIdpGroups(List.of("corp-data", "nope")).collectList().block()).hasSize(1);
        assertThat(groups.findByIdpGroups(List.of("nope")).collectList().block()).isEmpty();
        Group g2 = g.changed("sre", "Site reliability", List.of(), List.of(), NOW.plusSeconds(1));
        assertThat(groups.update(g2, 1).block()).isEqualTo(g2);
        assertThat(groups.findByIdpGroups(List.of("corp-sre")).collectList().block()).hasSize(1);
        assertThat(groups.find(SCOPE, "grp_1").block()).isEqualTo(g2);
        assertThat(groups.list(SCOPE, 10, null).block().items()).containsExactly(g2);
        assertThat(groups.delete(SCOPE, "grp_1").block()).isTrue();
        assertThat(groups.findAll(SCOPE).collectList().block()).isEmpty();
        assertThat(groups.findEverything().collectList().block()).hasSize(1);
    }

    @Test
    void guardrails() {
        MongoGuardrailStore store = new MongoGuardrailStore(mongo);
        store.ensureIndexes().block();
        Guardrail g = Guardrail.create("gr_2", "no-prod-deletes", "Production is deleted by change request only", List.of(new Statement("prod-deletes",
                Statement.Effect.deny, List.of("*:delete"), null, List.of("wfrn:wf:*"), Map.of("tag.env", "prod", "region", List.of("global")))), List.of(ACCOUNT),
                NOW);
        store.insert(g).block();
        store.insert(Guardrail.create("gr_1", "global-only", null, List.of(Statement.allow("global", List.of("*:*"), List.of("wfrn:wf:*:global:*"))),
                List.of(Guardrail.EVERY_ACCOUNT), NOW)).block();
        assertThatThrownBy(() -> store.insert(Guardrail.create("gr_3", "no-prod-deletes", null, List.of(), List.of(OTHER), NOW)).block())
                .as("names are unique in the organization").isInstanceOf(ConflictException.class);
        assertThat(store.findById("gr_2").block()).as("conditions with dotted keys come back as written").isEqualTo(g);
        assertThat(store.findAll().map(Guardrail::guardrailId).collectList().block()).as("in id order").containsExactly("gr_1", "gr_2");
        Guardrail moved = g.changed(null, null, null, List.of(OTHER), NOW.plusSeconds(1));
        assertThat(store.update(moved, 1).block()).isEqualTo(moved);
        assertThat(store.update(moved, 1).block()).as("a stale version").isNull();
        assertThatThrownBy(() -> store.update(moved.changed("global-only", null, null, null, NOW.plusSeconds(2)), 2).block()).isInstanceOf(ConflictException.class);
        assertThat(store.findById("gr_2").block().appliesTo(OTHER)).isTrue();
        assertThat(store.delete("gr_2").block()).isTrue();
        assertThat(store.delete("gr_2").block()).isFalse();
        assertThat(store.findById("gr_2").block()).isNull();
        assertThat(store.findAll().collectList().block()).hasSize(1);
    }

    @Test
    void accessKeys() {
        MongoAccessKeyStore store = new MongoAccessKeyStore(mongo);
        store.ensureIndexes().block();
        AccessKey k1 = AccessKey.create("ck_1", ACCOUNT, "usr_dana", "laptop", "s".repeat(43), null, NOW);
        AccessKey k2 = AccessKey.create("ck_2", ACCOUNT, "usr_dana", "ci", "t".repeat(43), NOW.plus(Duration.ofDays(30)), NOW);
        AccessKey k3 = AccessKey.create("ck_3", ACCOUNT, "usr_mia", null, "u".repeat(43), null, NOW);
        store.insert(k1).block();
        store.insert(k2).block();
        store.insert(k3).block();
        assertThatThrownBy(() -> store.insert(k1).block()).isInstanceOf(ConflictException.class);
        assertThat(store.find(SCOPE, "ck_1").block()).isEqualTo(k1);
        assertThat(store.find(OTHER_SCOPE, "ck_1").block()).isNull();
        assertThat(store.findByKeyId("ck_3").block()).isEqualTo(k3);
        assertThat(store.findByKeyId("ck_nope").block()).isNull();
        assertThat(store.findByUser(SCOPE, "usr_dana").collectList().block()).containsExactlyInAnyOrder(k1, k2);
        assertThat(store.findAll(SCOPE).collectList().block()).hasSize(3);
        Page<AccessKey> page = store.list(SCOPE, "usr_dana", 1, null).block();
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasNext()).isTrue();
        assertThat(store.list(SCOPE, "usr_dana", 1, page.next()).block().hasNext()).isFalse();
        assertThat(store.list(SCOPE, null, 10, null).block().items()).hasSize(3);
        AccessKey revoked = k1.revoked(NOW.plusSeconds(1));
        assertThat(store.update(revoked, 1).block()).isEqualTo(revoked);
        assertThat(store.update(revoked, 1).block()).isNull();
        assertThat(store.find(SCOPE, "ck_1").block().status(NOW.plusSeconds(2))).isEqualTo(AccessKeyStatus.revoked);
        AccessKey rotated = k2.rotated("v".repeat(43), NOW.plusSeconds(1));
        assertThat(store.update(rotated, 1).block()).isEqualTo(rotated);
        assertThat(store.find(SCOPE, "ck_2").block().accepts("t".repeat(43), NOW.plusSeconds(2))).isTrue();
    }

    @Test
    void quotas() {
        MongoQuotaStore store = new MongoQuotaStore(mongo);
        store.ensureIndexes().block();
        QuotaOverride o = new QuotaOverride(ACCOUNT, "jobs-per-minute", 120, 240, 1, NOW);
        assertThat(store.upsert(o).block()).isEqualTo(o);
        QuotaOverride again = o.changed(200, null, NOW.plusSeconds(1));
        assertThat(store.upsert(again).block()).isEqualTo(again);
        store.upsert(new QuotaOverride(ACCOUNT, "access-keys", 10, null, 1, NOW)).block();
        store.upsert(new QuotaOverride(OTHER, "access-keys", 5, null, 1, NOW)).block();
        assertThat(store.findOverride(SCOPE, "jobs-per-minute").block()).isEqualTo(again);
        assertThat(store.findOverride(SCOPE, "nope").block()).isNull();
        assertThat(store.findOverrides(SCOPE).map(QuotaOverride::key).collectList().block()).containsExactly("access-keys", "jobs-per-minute");

        QuotaRequest r = QuotaRequest.open("qreq_1", ACCOUNT, "resources.vm", 100, 200, "more test machines", "usr_mia", NOW);
        assertThat(store.insertRequest(r).block()).isEqualTo(r);
        assertThatThrownBy(() -> store.insertRequest(r).block()).isInstanceOf(ConflictException.class);
        assertThat(store.findRequest(SCOPE, "qreq_1").block()).isEqualTo(r);
        assertThat(store.findRequest(OTHER_SCOPE, "qreq_1").block()).isNull();
        assertThat(store.findOpenRequest(SCOPE, "resources.vm").block()).isEqualTo(r);
        assertThat(store.findOpenRequest(SCOPE, "resources.db").block()).isNull();
        assertThat(store.listRequests(SCOPE, QuotaRequest.Status.open, 10, null).block().items()).containsExactly(r);
        QuotaRequest approved = r.approved("svc:console-bff", 150, "150 for now", NOW.plusSeconds(1));
        assertThat(store.updateRequest(approved, 1).block()).isEqualTo(approved);
        assertThat(store.updateRequest(approved, 1).block()).isNull();
        assertThat(store.findOpenRequest(SCOPE, "resources.vm").block()).isNull();
        assertThat(store.listRequests(SCOPE, QuotaRequest.Status.approved, 10, null).block().items()).containsExactly(approved);
        assertThat(store.listRequests(SCOPE, null, 10, null).block().items()).containsExactly(approved);
        assertThat(store.findRequest(SCOPE, "qreq_1").block().grantedLimit()).isEqualTo(150);
        assertThat(store.findRequest(SCOPE, "qreq_1").block().version()).isEqualTo(2);
        assertThat(store.nextChangeVersion(SCOPE, "resources.vm").block()).isEqualTo(1);
        assertThat(store.nextChangeVersion(SCOPE, "resources.vm").block()).isEqualTo(2);
        assertThat(store.nextChangeVersion(SCOPE, "resources.db").block()).as("one counter per key").isEqualTo(1);
        assertThat(store.nextChangeVersion(OTHER_SCOPE, "resources.vm").block()).as("and per account").isEqualTo(1);

        assertThat(store.lastRuleApproval(SCOPE, "resources.vm").block()).as("a person's approval starts no cooldown").isNull();
        QuotaRequest early = QuotaRequest.open("qreq_2", ACCOUNT, "resources.vm", 150, 300, "more test machines", "usr_mia", NOW);
        store.insertRequest(early).block();
        store.updateRequest(early.approved(QuotaRequest.RULE + "resources-double", 300, null, NOW.plusSeconds(10)), 1).block();
        QuotaRequest late = QuotaRequest.open("qreq_3", ACCOUNT, "resources.vm", 300, 600, "more test machines", "usr_mia", NOW);
        store.insertRequest(late.routedTo("platform-team")).block();
        assertThat(store.findRequest(SCOPE, "qreq_3").block().routedTo()).isEqualTo("platform-team");
        store.updateRequest(late.routedTo("platform-team").approved(QuotaRequest.RULE + "resources-double", 600, null, NOW.plusSeconds(20)), 1).block();
        assertThat(store.lastRuleApproval(SCOPE, "resources.vm").block()).isEqualTo(NOW.plusSeconds(20));
        assertThat(store.lastRuleApproval(SCOPE, "resources.db").block()).isNull();
        assertThat(store.lastRuleApproval(OTHER_SCOPE, "resources.vm").block()).isNull();
    }

    @Test
    void accessRequests() {
        MongoAccessRequestStore store = new MongoAccessRequestStore(mongo);
        store.ensureIndexes().block();
        AccessRequest.Requester mia = new AccessRequest.Requester("usr_mia", "mia@example.com", "Mia");
        AccessRequest r1 = AccessRequest.open("areq_1", ACCOUNT, mia, "compute:create", "wfrn:wf:compute:global:" + ACCOUNT + ":vm/*", "operator", "I deploy",
                List.of("usr_owner"), NOW);
        AccessRequest r2 = AccessRequest.open("areq_2", ACCOUNT, new AccessRequest.Requester("usr_dana", "dana@example.com", "Dana"), "iam:create", null,
                "account-admin", "I manage users", List.of("usr_owner"), NOW.plusSeconds(1));
        store.insert(r1).block();
        store.insert(r2).block();
        assertThatThrownBy(() -> store.insert(r1).block()).isInstanceOf(ConflictException.class);
        assertThat(store.find(SCOPE, "areq_1").block()).isEqualTo(r1);
        assertThat(store.find(OTHER_SCOPE, "areq_1").block()).isNull();
        assertThat(store.findPending(SCOPE, "usr_mia", "operator").block()).isEqualTo(r1);
        assertThat(store.findPending(SCOPE, "usr_mia", "auditor").block()).isNull();
        assertThat(store.list(SCOPE, "usr_mia", null, 10, null).block().items()).containsExactly(r1);
        assertThat(store.list(SCOPE, null, AccessRequest.Status.pending, 10, null).block().items()).containsExactlyInAnyOrder(r1, r2);
        assertThat(store.findPendingBefore(NOW.plusSeconds(2)).collectList().block()).containsExactlyInAnyOrder(r1, r2);
        assertThat(store.findPendingBefore(NOW.plusSeconds(1)).collectList().block()).containsExactly(r1);
        assertThat(store.findPendingBefore(NOW).collectList().block()).isEmpty();
        AccessRequest approved = r1.decided(AccessRequest.Status.approved, "usr_owner", "welcome", NOW.plusSeconds(2));
        assertThat(store.update(approved, 1).block()).isEqualTo(approved);
        assertThat(store.update(approved, 1).block()).isNull();
        assertThat(store.findPending(SCOPE, "usr_mia", "operator").block()).isNull();
        assertThat(store.findPendingBefore(NOW.plusSeconds(10)).collectList().block()).containsExactly(r2);
        AccessRequest expired = r2.expired();
        assertThat(store.update(expired, 1).block()).isEqualTo(expired);
        assertThat(store.findPendingBefore(NOW.plusSeconds(10)).collectList().block()).isEmpty();
        assertThat(store.list(SCOPE, null, AccessRequest.Status.expired, 10, null).block().items()).containsExactly(expired);
        assertThat(store.list(SCOPE, null, AccessRequest.Status.approved, 10, null).block().items()).containsExactly(approved);
        Page<AccessRequest> page = store.list(SCOPE, null, null, 1, null).block();
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasNext()).isTrue();
        assertThat(store.list(SCOPE, null, null, 1, page.next()).block().hasNext()).isFalse();
    }

    @Test
    void authzVersions() {
        MongoAuthzVersions versions = new MongoAuthzVersions(mongo);
        versions.ensureIndexes().block();
        assertThat(versions.getRevision().block()).isZero();
        assertThat(versions.getVersion(ACCOUNT).block()).isZero();
        assertThat(versions.bump(ACCOUNT).block()).isEqualTo(1);
        assertThat(versions.bump(ACCOUNT).block()).isEqualTo(2);
        assertThat(versions.bump(OTHER).block()).isEqualTo(1);
        assertThat(versions.getVersion(ACCOUNT).block()).isEqualTo(2);
        assertThat(versions.getVersion(OTHER).block()).isEqualTo(1);
        assertThat(versions.getRevision().block()).isEqualTo(3);
    }

    @Test
    void idempotency() {
        MongoIdempotency store = new MongoIdempotency(mongo);
        store.ensureIndexes().block();
        String scope = "usr_dana|POST|/v1/accounts/" + ACCOUNT + "/users|k-1";
        IdempotencyPort.Reservation first = store.reserve(scope, "hash-1", NOW, Duration.ofHours(24)).block();
        assertThat(first.outcome()).isEqualTo(Outcome.RESERVED);
        assertThat(store.reserve(scope, "hash-1", NOW, Duration.ofHours(24)).block().outcome()).isEqualTo(Outcome.IN_FLIGHT);
        assertThat(store.reserve(scope, "hash-2", NOW, Duration.ofHours(24)).block().outcome()).isEqualTo(Outcome.MISMATCH);
        store.complete(scope, 201, "application/json", "{\"userId\":\"usr_1\"}".getBytes(StandardCharsets.UTF_8)).block();
        IdempotencyPort.Reservation replay = store.reserve(scope, "hash-1", NOW, Duration.ofHours(24)).block();
        assertThat(replay.outcome()).isEqualTo(Outcome.REPLAY);
        assertThat(replay.record().status()).isEqualTo(201);
        assertThat(replay.record().contentType()).isEqualTo("application/json");
        assertThat(new String(replay.record().body(), StandardCharsets.UTF_8)).contains("usr_1");
        assertThat(store.reserve(scope, "hash-2", NOW, Duration.ofHours(24)).block().outcome()).isEqualTo(Outcome.MISMATCH);

        String released = scope + "-2";
        assertThat(store.reserve(released, "hash", NOW, Duration.ofHours(24)).block().outcome()).isEqualTo(Outcome.RESERVED);
        store.release(released).block();
        assertThat(store.reserve(released, "hash", NOW, Duration.ofHours(24)).block().outcome()).isEqualTo(Outcome.RESERVED);
    }
}
