package com.iaf.console.drift.monitoring.ports.outbound;

import com.iaf.console.drift.monitoring.domain.Alarm;
import com.iaf.console.drift.monitoring.domain.AlarmQuery;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The alarms: one per account and alarm id. Every write is conditional on the revision it read, so
 * an evaluation and a person's change never overwrite each other.
 */
public interface AlarmStorePort {

    Mono<Alarm> find(AccountScope scope, String alarmId);

    /** A page of the account's alarms, the firing ones first, then by alarm id. */
    Mono<Page<Alarm>> list(AccountScope scope, AlarmQuery query);

    /** Every alarm of every account, ordered by wfrn, so the evaluation reads each resource once. */
    @GlobalRead("the evaluation every minute works across accounts")
    Flux<Alarm> findAll();

    /**
     * Writes the alarm if the stored one is still at {@code alarm.rev()} (0: not stored yet) and
     * answers it at the next revision; otherwise fails with {@link Conflict}.
     */
    Mono<Alarm> save(Alarm alarm);

    /** Removes the alarm; false when the account has no alarm of that id. */
    Mono<Boolean> delete(AccountScope scope, String alarmId);

    /** Another writer changed the alarm since it was read, or created it first. */
    final class Conflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Conflict(String accountId, String alarmId) {
            super("the alarm " + alarmId + " of account " + accountId + " changed since it was read");
        }
    }
}
