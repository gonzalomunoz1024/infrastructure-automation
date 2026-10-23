package com.iaf.console.identity.iam.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.domain.IamResources;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** What the IAM commands share: the account scope of a call, and resolving names or ids to ids. */
public final class IamSupport {

    private final String partition;

    public IamSupport(String partition) {
        this.partition = partition;
    }

    public String partition() {
        return partition;
    }

    /** The scope of a call on an account; 404 when the caller is of another account. */
    public Mono<AccountScope> scope(Caller caller, String accountId) {
        if (!caller.covers(accountId)) {
            return Mono.error(NotFoundException.of("account", accountId));
        }
        return Mono.just(caller.scope(accountId));
    }

    public String resource(String accountId, String kind, String id) {
        return IamResources.of(partition, accountId, kind, id);
    }

    public String anyResource(String accountId, String kind) {
        return IamResources.any(partition, accountId, kind);
    }

    /** Every resource of every service in the account. */
    public String everything(String accountId) {
        return IamResources.everything(partition, accountId);
    }

    /**
     * Resolves references (ids or names) to ids against the things of the account; a 400 names
     * every reference that resolves to nothing. Order and uniqueness are kept.
     */
    public static <T> List<String> resolve(List<String> references, List<T> things, Function<T, String> idOf, Function<T, String> nameOf, String field,
            String what) {
        if (references == null) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        List<Problem> problems = new ArrayList<>();
        for (int i = 0; i < references.size(); i++) {
            String ref = references.get(i);
            T found = null;
            for (T t : things) {
                if (idOf.apply(t).equals(ref) || nameOf.apply(t).equals(ref)) {
                    found = t;
                    break;
                }
            }
            if (found == null) {
                problems.add(Problem.error("unknown", field + "[" + i + "]", "'" + ref + "' is no " + what + " of this account"));
            } else {
                ids.add(idOf.apply(found));
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("the " + field + " are not valid", problems);
        }
        return List.copyOf(ids);
    }

    /** Names for ids, keeping the order; an id that resolves to nothing is kept as is. */
    public static List<String> names(List<String> ids, Map<String, String> nameById) {
        return ids.stream().map(id -> nameById.getOrDefault(id, id)).toList();
    }
}
