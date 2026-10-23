package com.iaf.console.identity.accessreviews.adapters.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.accessreviews.application.AccessReviewCommands;
import com.iaf.console.identity.accessreviews.application.AccessReviewQueries;
import com.iaf.console.identity.accessreviews.application.DecideAccessReviewItem;
import com.iaf.console.identity.accessreviews.application.StartAccessReview;
import com.iaf.console.identity.accessreviews.domain.AccessReview;
import com.iaf.console.identity.accessreviews.domain.AccessReviewItem;
import com.iaf.console.identity.common.Paging;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** {@code /v1/accounts/{accountId}/access-reviews}: campaigns over who holds which group, kept or revoked by the account's admins. */
@RestController
public class AccessReviewsController {

    private final AccessReviewCommands commands;
    private final AccessReviewQueries queries;

    public AccessReviewsController(AccessReviewCommands commands, AccessReviewQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/v1/accounts/{accountId}/access-reviews")
    public Mono<Page<AccessReview>> list(Caller caller, @PathVariable String accountId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        return queries.list(caller, accountId, Paging.limit(limit), after);
    }

    /** 201 with the campaign; 409 naming the open one when the account has one. */
    @PostMapping("/v1/accounts/{accountId}/access-reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AccessReview> start(Caller caller, @PathVariable String accountId, @RequestBody(required = false) StartAccessReview body) {
        return commands.start(caller, accountId, body);
    }

    @GetMapping("/v1/accounts/{accountId}/access-reviews/{reviewId}")
    public Mono<AccessReview> get(Caller caller, @PathVariable String accountId, @PathVariable String reviewId) {
        return queries.get(caller, accountId, reviewId);
    }

    @GetMapping("/v1/accounts/{accountId}/access-reviews/{reviewId}/items")
    public Mono<Page<AccessReviewItem>> items(Caller caller, @PathVariable String accountId, @PathVariable String reviewId,
            @RequestParam(required = false) String decision, @RequestParam(required = false) Integer limit, @RequestParam(required = false) String after) {
        return queries.items(caller, accountId, reviewId, decision, Paging.limit(limit), after);
    }

    @PostMapping("/v1/accounts/{accountId}/access-reviews/{reviewId}/items/{itemId}:decide")
    public Mono<AccessReviewItem> decide(Caller caller, @PathVariable String accountId, @PathVariable String reviewId, @PathVariable String itemId,
            @RequestBody DecideAccessReviewItem body) {
        return commands.decide(caller, accountId, reviewId, itemId, body);
    }

    @PostMapping("/v1/accounts/{accountId}/access-reviews/{reviewId}:cancel")
    public Mono<AccessReview> cancel(Caller caller, @PathVariable String accountId, @PathVariable String reviewId) {
        return commands.cancel(caller, accountId, reviewId);
    }
}
