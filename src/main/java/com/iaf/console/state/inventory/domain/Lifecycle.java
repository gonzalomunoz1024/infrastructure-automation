package com.iaf.console.state.inventory.domain;

import java.util.Locale;
import java.util.Map;

/**
 * The platform's resource statuses as lifecycle classes (P-06): a type's {@code console.lifecycle}
 * map decides, and a status it does not name falls back to the conventional names: {@code retired}
 * and {@code deleted} are gone, {@code building}, {@code creating}, {@code provisioning} and
 * {@code pending} are on their way, {@code failed} and {@code error} failed, everything else is active.
 */
public final class Lifecycle {

    private Lifecycle() {
    }

    public static ResourceState classOf(Map<String, String> lifecycle, String platformStatus) {
        if (platformStatus == null) {
            return ResourceState.ACTIVE;
        }
        String mapped = lifecycle == null ? null : lifecycle.get(platformStatus);
        if (mapped != null) {
            ResourceState state = ResourceState.of(mapped);
            if (state != null) {
                return state;
            }
        }
        return switch (platformStatus.toLowerCase(Locale.ROOT)) {
            case "retired", "deleted", "destroyed", "terminated" -> ResourceState.DELETED;
            case "building", "creating", "provisioning", "pending", "requested" -> ResourceState.PENDING;
            case "failed", "error", "broken" -> ResourceState.FAILED;
            default -> ResourceState.ACTIVE;
        };
    }
}
