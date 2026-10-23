package com.iaf.console.bff.resources.domain;

import java.util.Locale;
import java.util.Map;

import com.iaf.console.platform.model.Resource;

/** The console's lifecycle state for a registry record's status, and a reference to the record. */
public final class PlatformStates {

    private static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("building", StateResource.PENDING), Map.entry("pending", StateResource.PENDING), Map.entry("creating", StateResource.PENDING),
            Map.entry("ready", StateResource.ACTIVE), Map.entry("active", StateResource.ACTIVE), Map.entry("running", StateResource.ACTIVE),
            Map.entry("deprecated", StateResource.ACTIVE), Map.entry("updating", StateResource.UPDATING),
            Map.entry("deleting", StateResource.DELETING), Map.entry("retired", StateResource.DELETED),
            Map.entry("deleted", StateResource.DELETED), Map.entry("failed", StateResource.FAILED));

    private PlatformStates() {
    }

    /** The console state of a platform status; {@code null} for one the console does not know. */
    public static String of(String status) {
        return status == null ? null : STATES.get(status.toLowerCase(Locale.ROOT));
    }

    /** Whether a registry record still exists: anything but retired or deleted. */
    public static boolean live(Resource resource) {
        return !StateResource.DELETED.equals(of(resource.status()));
    }

    public static ResourceRef ref(Resource resource) {
        return new ResourceRef(resource.wfrn(), resource.type(), resource.name(), of(resource.status()), resource.id());
    }
}
