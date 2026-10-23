package com.iaf.console.bff.drift.ports.outbound;

import java.time.Instant;

import com.iaf.console.bff.drift.domain.Alarm;
import com.iaf.console.bff.drift.domain.AlarmInput;
import com.iaf.console.bff.drift.domain.DiscoveryRun;
import com.iaf.console.bff.drift.domain.DriftCaller;
import com.iaf.console.bff.drift.domain.DriftCommands;
import com.iaf.console.bff.drift.domain.DriftQueries;
import com.iaf.console.bff.drift.domain.DriftResults;
import com.iaf.console.bff.drift.domain.Finding;
import com.iaf.console.bff.drift.domain.FindingStats;
import com.iaf.console.bff.drift.domain.FindingsSummary;
import com.iaf.console.bff.drift.domain.Recommendation;
import com.iaf.console.bff.drift.domain.RecommendationsSummary;
import com.iaf.console.bff.drift.domain.ResourceMetrics;
import com.iaf.console.bff.drift.domain.UnmanagedObject;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The drift service (contracts/openapi/console-drift-service.yaml), one method per operation the
 * console uses. Every call names the scope's account (the {@code accountId} of the reads) and,
 * where the operation takes one, its region; the caller is the person, or the console-internal
 * principal for a read no person is behind. The drift service's refusals arrive as the kit's
 * exceptions: 400 {@code InvalidInputException}, 404 {@code NotFoundException}, 409
 * {@code DriftConflictException}, 429 {@code TooManyRequestsException}, 502 {@code DriftGatewayException}.
 */
public interface DriftPort {

    // ---- findings

    Mono<Page<Finding>> listFindings(AccountScope scope, DriftCaller caller, DriftQueries.Findings query);

    Mono<FindingsSummary> getFindingsSummary(AccountScope scope, DriftCaller caller);

    /**
     * Findings opened and closed in a window, for the scope's account: the drift numbers of the
     * executive home. Null bounds leave the drift service's defaults (the last 30 days, until now).
     * Another slice may call it as the console-internal principal ({@link DriftCaller#internalPrincipal()}).
     */
    Mono<FindingStats> getFindingStats(AccountScope scope, DriftCaller caller, Instant since, Instant until);

    Mono<Finding> getFinding(AccountScope scope, DriftCaller caller, String findingId);

    Mono<DriftResults.RevertStarted> revertFinding(AccountScope scope, DriftCaller caller, String findingId, DriftCommands.Decision decision,
            DriftCommands.Meta meta);

    Mono<Finding> acceptFinding(AccountScope scope, DriftCaller caller, String findingId, DriftCommands.Decision decision, DriftCommands.Meta meta);

    Mono<Finding> ignoreFinding(AccountScope scope, DriftCaller caller, String findingId, DriftCommands.Decision decision, DriftCommands.Meta meta);

    // ---- the shadow-infrastructure finder

    Mono<DiscoveryRun> startDiscovery(AccountScope scope, DriftCaller caller, String type, DriftCommands.Meta meta);

    Mono<DiscoveryRun> getDiscovery(AccountScope scope, DriftCaller caller, String runId);

    Mono<Page<UnmanagedObject>> listUnmanaged(AccountScope scope, DriftCaller caller, DriftQueries.Unmanaged query);

    Mono<DriftResults.Imported> importUnmanaged(AccountScope scope, DriftCaller caller, DriftCommands.Import command, DriftCommands.Meta meta);

    Mono<UnmanagedObject> assignUnmanaged(AccountScope scope, DriftCaller caller, DriftCommands.Assign command, DriftCommands.Meta meta);

    // ---- monitoring

    Mono<ResourceMetrics> getResourceMetrics(AccountScope scope, DriftCaller caller, DriftQueries.Metrics query);

    Mono<Page<Alarm>> listAlarms(AccountScope scope, DriftCaller caller, DriftQueries.Alarms query);

    Mono<Alarm> getAlarm(AccountScope scope, DriftCaller caller, String alarmId);

    Mono<DriftResults.AlarmSaved> putAlarm(AccountScope scope, DriftCaller caller, String alarmId, AlarmInput input, DriftCommands.Meta meta);

    Mono<Void> deleteAlarm(AccountScope scope, DriftCaller caller, String alarmId, DriftCommands.Meta meta);

    // ---- rightsizing

    Mono<Page<Recommendation>> listRecommendations(AccountScope scope, DriftCaller caller, DriftQueries.Recommendations query);

    Mono<RecommendationsSummary> getRecommendationsSummary(AccountScope scope, DriftCaller caller);

    Mono<Recommendation> getRecommendation(AccountScope scope, DriftCaller caller, String recommendationId);

    Mono<DriftResults.ApplyStarted> applyRecommendation(AccountScope scope, DriftCaller caller, String recommendationId,
            DriftCommands.Decision decision, DriftCommands.Meta meta);

    Mono<Recommendation> dismissRecommendation(AccountScope scope, DriftCaller caller, String recommendationId, DriftCommands.Decision decision,
            DriftCommands.Meta meta);
}
