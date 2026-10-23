package com.iaf.console.state.stack.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.stack.domain.Blueprint;
import com.iaf.console.state.stack.domain.BundledBlueprints;
import com.iaf.console.state.stack.domain.ParameterCheck;
import com.iaf.console.state.stack.domain.Stack;
import com.iaf.console.state.stack.domain.StackDecider;
import com.iaf.console.state.stack.domain.StackEvent;
import com.iaf.console.state.stack.domain.StackQuery;
import com.iaf.console.state.stack.domain.StackState;
import com.iaf.console.state.stack.ports.outbound.StackEventStorePort;
import com.iaf.console.state.stack.ports.outbound.StackStorePort;

/**
 * What every stack store answers alike: a stack read back as it was written, one live stack per name
 * in an account (a deleted one frees it), a write over the version read only, the account's stacks
 * newest first by page, the ones still moving for the sweeper, and the event log written once per
 * version. The in-memory stores run it everywhere, MongoDB's where a database is at hand (CI).
 */
public abstract class StackStoreContract {

    private static final String ACCOUNT = "500000000001";
    private static final String REGION = "global";
    private static final Instant T0 = Instant.parse("2026-10-23T10:00:00Z");
    private static final AccountScope SCOPE = AccountScope.of(ACCOUNT, REGION, "dana");
    private static final Blueprint SANDBOX = BundledBlueprints.bundled("dev-sandbox");

    protected abstract StackStorePort stacks();

    protected abstract StackEventStorePort events();

    private static StackDecider.Decision launched(String account, String name, Instant at) {
        String wfrn = ResourceIdentity.mint("wf", "blueprints", REGION, account, "stack").wfrn();
        return StackDecider.launch(SANDBOX, wfrn, account, REGION, name, ParameterCheck.check(SANDBOX, Json.object().put("image", "img-1")),
                Map.of("team", "payments"), Map.of("vm", "compute", "volume", "storage"), "dana", "req_1", at);
    }

    @Test
    public void aStackIsReadBackAsItWasWrittenAndOnlyInItsAccount() {
        Stack s = launched(ACCOUNT, "round-trip", T0).stack();
        stacks().insert(s).block();
        assertThat(stacks().find(SCOPE, s.wfrn()).block()).isEqualTo(s);
        assertThat(stacks().findByWfrn(s.wfrn()).block()).isEqualTo(s);
        assertThat(stacks().find(AccountScope.of("500000000002", REGION, "dana"), s.wfrn()).block()).isNull();
        assertThat(s.step("vm").orElseThrow().ready().input().path("image").asText()).isEqualTo("img-1");
        assertThat(s.step("vm").orElseThrow().template()).as("the step keeps the input it was launched with").isNotNull();
    }

    @Test
    public void aNameIsTakenOnceAmongAnAccountsLiveStacks() {
        Stack first = launched(ACCOUNT, "taken", T0).stack();
        stacks().insert(first).block();
        assertThatThrownBy(() -> stacks().insert(launched(ACCOUNT, "taken", T0).stack()).block()).isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("stack.nameTaken");
        stacks().insert(launched("500000000003", "taken", T0).stack()).block();

        Stack deleted = withState(first, StackState.DELETED, first.version() + 1);
        stacks().update(deleted, first.version()).block();
        Stack again = launched(ACCOUNT, "taken", T0.plusSeconds(60)).stack();
        assertThat(stacks().insert(again).block()).as("a deleted stack frees its name").isEqualTo(again);
    }

    @Test
    public void aWriteGoesOverTheVersionReadOnly() {
        Stack s = launched(ACCOUNT, "versioned", T0).stack();
        stacks().insert(s).block();
        Stack next = withState(s, StackState.ACTIVE, s.version() + 1);
        assertThat(stacks().update(next, s.version()).block()).isEqualTo(next);
        assertThatThrownBy(() -> stacks().update(withState(s, StackState.FAILED, s.version() + 1), s.version()).block())
                .isInstanceOf(ConflictException.class).hasMessageContaining("version.conflict");
        assertThat(stacks().findByWfrn(s.wfrn()).block().state()).isEqualTo(StackState.ACTIVE);
    }

    @Test
    public void anAccountsStacksComeNewestFirstByPageAndTheMovingOnesToTheSweeper() {
        String account = "500000000004";
        AccountScope scope = AccountScope.of(account, REGION, "dana");
        Stack a = launched(account, "alpha", T0).stack();
        Stack b = launched(account, "beta", T0.plusSeconds(1)).stack();
        Stack c = launched(account, "alpine", T0.plusSeconds(2)).stack();
        List.of(a, b, c).forEach(s -> stacks().insert(s).block());
        stacks().update(withState(b, StackState.ACTIVE, b.version() + 1), b.version()).block();

        Page<Stack> first = stacks().list(scope, new StackQuery(null, null, null, 2, null)).block();
        assertThat(first.items()).extracting(Stack::name).containsExactly("alpine", "beta");
        Page<Stack> second = stacks().list(scope, new StackQuery(null, null, null, 2, com.iaf.console.kit.paging.CursorPaging.decode(first.next())))
                .block();
        assertThat(second.items()).extracting(Stack::name).containsExactly("alpha");
        assertThat(second.next()).isNull();
        assertThat(stacks().list(scope, new StackQuery(StackState.ACTIVE, null, null, 10, null)).block().items()).extracting(Stack::name)
                .containsExactly("beta");
        assertThat(stacks().list(scope, new StackQuery(null, "dev-sandbox", "alp", 10, null)).block().items()).extracting(Stack::name)
                .containsExactly("alpine", "alpha");
        assertThat(stacks().list(scope, new StackQuery(null, "web-app", null, 10, null)).block().items()).isEmpty();

        Set<String> moving = Set.copyOf(stacks().listInFlight(1000).map(Stack::wfrn).collectList().block());
        assertThat(moving).contains(a.wfrn(), c.wfrn()).doesNotContain(b.wfrn());
    }

    @Test
    public void theEventLogKeepsEachVersionOnce() {
        StackDecider.Decision d = launched(ACCOUNT, "logged", T0);
        Stack s = d.stack();
        events().append(d.events()).block();
        events().append(d.events()).block();
        List<StackEvent> log = events().list(SCOPE, s.wfrn(), 0, 10).collectList().block();
        assertThat(log).hasSize(d.events().size());
        assertThat(log.get(0)).isEqualTo(d.events().get(0));
        assertThat(events().list(SCOPE, s.wfrn(), log.get(log.size() - 1).version(), 10).collectList().block()).isEmpty();
        assertThat(events().list(AccountScope.of("500000000009", REGION, "dana"), s.wfrn(), 0, 10).collectList().block()).isEmpty();
    }

    private static Stack withState(Stack s, StackState state, long version) {
        Instant at = s.createdAt().plus(Duration.ofMinutes(version));
        return new Stack(s.wfrn(), s.accountId(), s.region(), s.name(), s.blueprint(), s.blueprintVersion(), state, s.operation(), version,
                s.parameters(), s.tags(), s.launchedBy(), s.teardownReason(), s.teardownBy(), s.steps(), s.outputs(), s.outputTemplates(), s.failure(),
                s.createdAt(), at, state == StackState.DELETED ? at : null, state == StackState.DELETED ? at.plus(Duration.ofDays(90)) : null);
    }
}
