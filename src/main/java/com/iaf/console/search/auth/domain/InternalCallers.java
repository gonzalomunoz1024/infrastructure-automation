package com.iaf.console.search.auth.domain;

import java.util.Collection;
import java.util.Set;

/**
 * The console's own services that may call this one as themselves, with a token that carries
 * {@code svc} and no {@code acct} (hub decision 35): {@code services} for everything, the BFF
 * acting on its own; {@code counting} for {@code /v1/counts} across accounts only, the services
 * that count an organization's resources. Any other service token, and a token with neither
 * {@code acct} nor {@code svc}, is refused.
 */
public record InternalCallers(Set<String> services, Set<String> counting) {

    public static final InternalCallers DEFAULT = new InternalCallers(Set.of("console-bff"),
            Set.of("console-drift-service", "console-cost-service"));

    public InternalCallers {
        services = services == null ? Set.of() : Set.copyOf(services);
        counting = counting == null ? Set.of() : Set.copyOf(counting);
    }

    public static InternalCallers of(Collection<String> services, Collection<String> counting) {
        return new InternalCallers(services == null ? null : Set.copyOf(services), counting == null ? null : Set.copyOf(counting));
    }

    /**
     * What a caller may do: a person's ({@code account} present) only in their account; a listed
     * service as it is listed; anyone else nothing, {@code null}.
     */
    public ConsolePrincipal.Access access(String account, String service) {
        if (account != null && !account.isBlank()) {
            return ConsolePrincipal.Access.ACCOUNT;
        }
        if (service == null || service.isBlank()) {
            return null;
        }
        if (services.contains(service)) {
            return ConsolePrincipal.Access.SERVICE;
        }
        return counting.contains(service) ? ConsolePrincipal.Access.COUNTS : null;
    }
}
