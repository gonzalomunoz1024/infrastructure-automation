package com.iaf.console.drift.monitoring.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.console.drift.monitoring.domain.Alarm;
import com.iaf.console.drift.monitoring.domain.AlarmQuery;
import com.iaf.console.drift.monitoring.domain.AlarmState;
import com.iaf.console.drift.monitoring.domain.Comparison;
import com.iaf.console.drift.monitoring.domain.MetricPoint;
import com.iaf.console.drift.monitoring.ports.outbound.AlarmStorePort;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

/**
 * What every alarm store does, the in-memory one and MongoDB alike: an alarm id is unique within
 * its account, every write is conditional on the revision it read, a page lists the firing alarms
 * first, and the evaluation reads every alarm a resource at a time.
 */
public abstract class AlarmStoreContract {

    protected static final String ACCOUNT = "123456789012";
    protected static final String OTHER_ACCOUNT = "210987654321";
    protected static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "dana");
    protected static final AccountScope OTHER = AccountScope.of(OTHER_ACCOUNT, "global", "ana");
    protected static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

    private AlarmStorePort store;

    /** An empty store. */
    protected abstract AlarmStorePort fresh();

    @BeforeEach
    void empty() {
        store = fresh();
    }

    protected static String wfrn(String accountId, String id) {
        return "wfrn:wf:compute:global:" + accountId + ":vm/" + id;
    }

    protected static Alarm alarm(String accountId, String alarmId, String resource) {
        return Alarm.created(alarmId, accountId, "vm", wfrn(accountId, resource), "an alarm", "cpuPercent", Comparison.ABOVE, 50, 5, "dana", T0);
    }

    /** The alarm evaluated into a state: one point of the given value. */
    protected static Alarm in(Alarm alarm, double value) {
        return alarm.evaluated(List.of(new MetricPoint(T0.plusSeconds(60), value)), T0.plusSeconds(60));
    }

    @Test
    void anAlarmIsWrittenAtTheRevisionItWasRead() {
        Alarm created = store.save(alarm(ACCOUNT, "cpu", "vm-a")).block();
        assertThat(created.rev()).isEqualTo(1);
        Alarm read = store.find(SCOPE, "cpu").block();
        assertThat(read).isEqualTo(created);
        assertThat(read.comparison()).isEqualTo(Comparison.ABOVE);
        assertThat(read.state()).isEqualTo(AlarmState.INSUFFICIENT);

        Alarm evaluated = store.save(in(read, 70)).block();
        assertThat(evaluated.rev()).isEqualTo(2);
        assertThat(evaluated.state()).isEqualTo(AlarmState.ALARM);
        assertThat(evaluated.lastValue()).isEqualTo(70.0);
        // a writer that read revision 1 lost the race
        assertThatThrownBy(() -> store.save(read.changed("vm", read.wfrn(), null, "memoryPercent", Comparison.BELOW, 5, 10, T0)).block())
                .isInstanceOf(AlarmStorePort.Conflict.class);
        // and a second create of the same id is a conflict too
        assertThatThrownBy(() -> store.save(alarm(ACCOUNT, "cpu", "vm-b")).block()).isInstanceOf(AlarmStorePort.Conflict.class);
        assertThat(store.find(SCOPE, "cpu").block()).isEqualTo(evaluated);
    }

    @Test
    void anAlarmIdIsTheAccountsOwn() {
        store.save(alarm(ACCOUNT, "cpu", "vm-a")).block();
        store.save(alarm(OTHER_ACCOUNT, "cpu", "vm-z")).block();
        assertThat(store.find(SCOPE, "cpu").block().wfrn()).isEqualTo(wfrn(ACCOUNT, "vm-a"));
        assertThat(store.find(OTHER, "cpu").block().wfrn()).isEqualTo(wfrn(OTHER_ACCOUNT, "vm-z"));
        assertThat(store.delete(OTHER, "cpu").block()).isTrue();
        assertThat(store.delete(OTHER, "cpu").block()).isFalse();
        assertThat(store.find(OTHER, "cpu").block()).isNull();
        assertThat(store.find(SCOPE, "cpu").block()).isNotNull();
    }

    @Test
    void aPageListsTheFiringAlarmsFirstThenByIdAndPagesOn() {
        store.save(in(store.save(alarm(ACCOUNT, "a-ok", "vm-a")).block(), 10)).block();
        store.save(in(store.save(alarm(ACCOUNT, "b-firing", "vm-a")).block(), 90)).block();
        store.save(alarm(ACCOUNT, "c-new", "vm-b")).block();
        store.save(in(store.save(alarm(ACCOUNT, "d-firing", "vm-b")).block(), 95)).block();
        store.save(alarm(OTHER_ACCOUNT, "e-elsewhere", "vm-z")).block();

        assertThat(ids(store.list(SCOPE, new AlarmQuery(null, null, 50, null)).block())).containsExactly("b-firing", "d-firing", "c-new", "a-ok");
        assertThat(ids(store.list(SCOPE, new AlarmQuery(null, AlarmState.ALARM, 50, null)).block())).containsExactly("b-firing", "d-firing");
        assertThat(ids(store.list(SCOPE, new AlarmQuery(wfrn(ACCOUNT, "vm-b"), null, 50, null)).block())).containsExactly("d-firing", "c-new");

        Page<Alarm> first = store.list(SCOPE, new AlarmQuery(null, null, 3, null)).block();
        assertThat(ids(first)).containsExactly("b-firing", "d-firing", "c-new");
        assertThat(first.hasNext()).isTrue();
        // the cursor goes out to the caller and comes back
        Page<Alarm> second = store.list(SCOPE, new AlarmQuery(null, null, 3, CursorPaging.decode(first.next()))).block();
        assertThat(ids(second)).containsExactly("a-ok");
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    void everyAlarmComesAResourceAtATime() {
        store.save(alarm(ACCOUNT, "x", "vm-b")).block();
        store.save(alarm(ACCOUNT, "y", "vm-a")).block();
        store.save(alarm(OTHER_ACCOUNT, "z", "vm-c")).block();
        store.save(alarm(ACCOUNT, "w", "vm-b")).block();
        List<String> wfrns = store.findAll().map(Alarm::wfrn).collectList().block();
        assertThat(wfrns).hasSize(4).isSorted();
        assertThat(store.findAll().map(Alarm::alarmId).collectList().block()).containsExactly("y", "w", "x", "z");
    }

    private static List<String> ids(Page<Alarm> page) {
        return page.items().stream().map(Alarm::alarmId).toList();
    }
}
