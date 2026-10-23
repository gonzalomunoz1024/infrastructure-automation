package com.iaf.console.drift.auth.adapters.roles;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.iaf.console.drift.auth.domain.ConsolePrincipal;
import com.iaf.console.drift.auth.ports.outbound.AuthorizationPort;

import reactor.core.publisher.Mono;

/**
 * The managed roles of the token's {@code roles} claim, where no identity service decides (the
 * demos): account-admin does everything, operator everything but deletes, viewer and auditor only
 * read. A token without roles may do nothing; groups never decide here.
 */
public final class ManagedRoleAuthorization implements AuthorizationPort {

    private static final Set<String> READS = Set.of("list", "get", "read", "describe", "search", "preview", "observe");
    private static final Set<String> DESTRUCTIVE = Set.of("delete", "destroy", "purge", "terminate");

    @Override
    public Mono<Boolean> allowed(ConsolePrincipal person, String action, String resource) {
        int colon = action.lastIndexOf(':');
        String verb = (colon < 0 ? action : action.substring(colon + 1)).toLowerCase(Locale.ROOT);
        return Mono.just(anyAllows(person.roles(), verb));
    }

    static boolean anyAllows(List<String> roles, String verb) {
        for (String role : roles) {
            switch (role.toLowerCase(Locale.ROOT)) {
                case ConsolePrincipal.ACCOUNT_ADMIN:
                    return true;
                case "operator":
                    if (!DESTRUCTIVE.contains(verb)) {
                        return true;
                    }
                    break;
                case "viewer", "auditor":
                    if (READS.contains(verb)) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }
}
