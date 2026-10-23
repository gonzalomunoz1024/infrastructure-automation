package com.iaf.console.identity.iam.application;

import java.util.List;

import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.domain.Guardrail;
import com.iaf.console.identity.iam.ports.outbound.GuardrailStore;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** The reads on guardrails: all of them for the internal principal, an account's own for its people who may list its IAM. */
public final class GuardrailQueries {

    private static final String KIND = "guardrail";

    private final GuardrailStore guardrails;
    private final AccessGuard guard;
    private final IamSupport support;

    public GuardrailQueries(GuardrailStore guardrails, AccessGuard guard, IamSupport support) {
        this.guardrails = guardrails;
        this.guard = guard;
        this.support = support;
    }

    public Mono<Page<Guardrail>> list(Caller caller, int limit, String after) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException("no such operation"));
        }
        String from = after == null ? null : CursorPaging.decode(after).id();
        return guardrails.findAll()
                .filter(g -> from == null || g.guardrailId().compareTo(from) > 0)
                .take(limit + 1L)
                .collectList()
                .map(found -> Page.from(found, limit, g -> new Cursor(g.guardrailId(), g.guardrailId())));
    }

    public Mono<Guardrail> get(Caller caller, String guardrailId) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException("no such operation"));
        }
        return guardrails.findById(guardrailId).switchIfEmpty(Mono.error(NotFoundException.of(KIND, guardrailId)));
    }

    /** The guardrails that apply to the account, in id order. */
    public Mono<List<Guardrail>> forAccount(Caller caller, String accountId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:list", support.anyResource(accountId, KIND)))
                .then(guardrails.findAll().filter(g -> g.appliesTo(accountId)).collectList());
    }
}
