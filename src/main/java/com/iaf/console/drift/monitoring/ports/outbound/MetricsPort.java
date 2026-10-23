package com.iaf.console.drift.monitoring.ports.outbound;

import java.util.List;

import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.monitoring.domain.MetricsTarget;
import com.iaf.console.drift.monitoring.domain.MetricsWindow;
import com.iaf.console.drift.monitoring.domain.ResourceMetrics;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.TooManyRequestsException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * Where a resource's metric series come from: the platform's metrics read ({@code observe.metrics},
 * P-09) with the account's own credential and under the observe budget the scans share, or the
 * demo's synthetic series. A type without a metrics read is {@link NotSupported} (404
 * {@code metrics.notSupported}), a spent budget {@link BudgetSpent} (429 with Retry-After), and a
 * platform that cannot answer {@link Unavailable} (502).
 */
public interface MetricsPort {

    /** Whether resources of the type have series to read at all. */
    boolean supports(TypeInfo type);

    /** The series names the type's read answers, such as {@code cpuPercent}; empty when they are only known after a read. */
    List<String> seriesOf(TypeInfo type);

    /** The resource's series over the window, read for the scope's account. */
    Mono<ResourceMetrics> getMetrics(AccountScope scope, MetricsTarget target, TypeInfo type, MetricsWindow window);

    /** The type has no metrics read, or the platform has none for this resource: a 404 {@code metrics.notSupported}. */
    final class NotSupported extends NotFoundException {
        private static final long serialVersionUID = 1L;

        public NotSupported(String why) {
            super("metrics.notSupported: " + why);
        }
    }

    /** The provider's observe budget is used up for now: a 429 whose Retry-After says when to ask again. */
    final class BudgetSpent extends TooManyRequestsException {
        private static final long serialVersionUID = 1L;

        public BudgetSpent(String provider, String region, int retryAfterSeconds) {
            super("metrics.budgetSpent: the observe budget of " + provider + " in " + region + " is used up for now", retryAfterSeconds);
        }
    }

    /** The platform failed to answer, or no credential reads for the account: a 502. */
    final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
