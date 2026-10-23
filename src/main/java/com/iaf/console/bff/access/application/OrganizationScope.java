package com.iaf.console.bff.access.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Session;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The organization views (Cost Explorer's, the chargeback's, the scorecards' heat map): the
 * person's accounts on which the policies allow an action, each decided in its own account as that
 * account's member, at most {@code max} allowed in the order of the memberships; the others,
 * refused or past the cap, are skipped and named.
 */
public final class OrganizationScope {

    private static final int CONCURRENCY = 16;

    private OrganizationScope() {
    }

    /** The accounts of an organization read: the allowed ones and the others, with the first refusal to explain an empty view. */
    public record Split(List<String> allowed, List<String> skipped, Decision firstRefusal) {

        public Split {
            allowed = List.copyOf(allowed);
            skipped = List.copyOf(skipped);
        }
    }

    /** Decides {@code action} on {@code resourceOf.apply(accountId)} in each of the session's accounts. */
    public static Mono<Split> decide(Access access, Session session, String action, UnaryOperator<String> resourceOf, int max) {
        List<AccountMembership> memberships = session.accounts();
        return Flux.fromIterable(memberships).flatMapSequential(m -> {
            String region = m.defaultRegion() == null || m.defaultRegion().isBlank() ? session.region() : m.defaultRegion();
            Session there = session.switchedTo(m.accountId(), region);
            return access.decide(there, List.of(new AccessCheck(action, resourceOf.apply(m.accountId())))).map(d -> Map.entry(m.accountId(), d.get(0)));
        }, CONCURRENCY).collectList().map(decided -> {
            List<String> allowed = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            Decision refusal = null;
            for (Map.Entry<String, Decision> e : decided) {
                if (e.getValue().allowed() && allowed.size() < max) {
                    allowed.add(e.getKey());
                } else {
                    skipped.add(e.getKey());
                    if (refusal == null && !e.getValue().allowed()) {
                        refusal = e.getValue();
                    }
                }
            }
            return new Split(allowed, skipped, refusal);
        });
    }
}
