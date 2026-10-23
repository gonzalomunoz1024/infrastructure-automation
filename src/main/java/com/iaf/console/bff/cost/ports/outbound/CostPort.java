package com.iaf.console.bff.cost.ports.outbound;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.iaf.console.bff.common.relay.RelayCall;
import com.iaf.console.bff.common.relay.Relayed;
import com.iaf.console.bff.cost.domain.BudgetCheck;
import com.iaf.console.bff.cost.domain.BudgetRule;
import com.iaf.console.bff.cost.domain.Estimate;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The cost service (console-cost-service.yaml): a person's own reads and budget changes go as the
 * person in their account, which the service holds to that account (another account is its 404);
 * the organization view, the budget check and the estimate go with the BFF's service token.
 */
public interface CostPort {

    /** A call as the person, in the scope's account. */
    Mono<Relayed> callAsPerson(AccountScope scope, StateCaller caller, RelayCall call);

    /** A call with the BFF's service token, naming its accounts itself ({@code accountId}, repeated). */
    Mono<Relayed> callAsService(RelayCall call);

    /** {@code POST /v1/budgets:check} with the service token, answered within {@code within}: the rules, deny or warn. */
    Mono<List<BudgetRule>> checkBudgets(BudgetCheck check, Duration within);

    /** {@code POST /v1/cost:estimate} with the service token, answered within {@code within}. */
    Mono<Estimate> estimate(String type, String region, Map<String, Object> attributes, int count, Duration within);
}
