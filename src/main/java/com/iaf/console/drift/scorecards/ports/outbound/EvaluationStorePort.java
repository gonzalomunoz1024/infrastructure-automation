package com.iaf.console.drift.scorecards.ports.outbound;

import com.iaf.console.drift.scorecards.domain.ResourceEvaluation;
import com.iaf.console.drift.scorecards.domain.RuleKey;
import com.iaf.console.drift.scorecards.domain.Views;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** One evaluation per resource and rule, kept as one record per resource, with the failing ones findable per rule. */
public interface EvaluationStorePort {

    Mono<ResourceEvaluation> find(AccountScope scope, String wfrn);

    @GlobalRead("the evaluator scores a resource by its name, which carries the account")
    Mono<ResourceEvaluation> findByWfrn(String wfrn);

    /**
     * Writes the evaluation if the stored one is at {@code evaluation.version() - 1} (none stored
     * for version 1); answers false when another writer got there first.
     */
    Mono<Boolean> save(ResourceEvaluation evaluation);

    /** Removes a resource's evaluation and answers what was stored, if anything. */
    Mono<ResourceEvaluation> delete(String wfrn);

    /** The account's resources that fail a rule, longest failing first (since, then wfrn). */
    Mono<Page<Views.Evaluation>> listFailing(AccountScope scope, RuleKey rule, String type, int limit, Cursor after);

    /** Every evaluation of the account, for the daily recount. */
    Flux<ResourceEvaluation> listAll(AccountScope scope);

    @GlobalRead("the daily recount walks every account that has an evaluation")
    Flux<String> listAccounts();
}
