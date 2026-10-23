package com.iaf.console.state.inventory.domain;

import java.util.Objects;

import com.iaf.console.wfrn.MintedIds;
import com.iaf.console.wfrn.Ulid;
import com.iaf.console.wfrn.Wfrn;

/** Who a new resource is: its wfrn and the parts the wfrn is made of. */
public record ResourceIdentity(String wfrn, String accountId, String region, String type, String service) {

    public ResourceIdentity {
        Objects.requireNonNull(wfrn, "wfrn");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(service, "service");
    }

    /** Mints a name: {@code wfrn:<partition>:<service>:<region>:<account>:<type>/<type>-<ulid>}. */
    public static ResourceIdentity mint(String partition, String service, String region, String accountId, String type) {
        String id = MintedIds.mint(type, Ulid.next());
        Wfrn name = new Wfrn(partition, service, region, accountId, type, id);
        return new ResourceIdentity(name.format(), accountId, region, type, service);
    }

    /** The identity a given name spells; its account, region and type come from the name itself. */
    public static ResourceIdentity of(String wfrn) {
        Wfrn name = Wfrn.parse(wfrn);
        if (name.account().isEmpty() || name.region().isEmpty()) {
            throw new IllegalArgumentException("a console resource's wfrn names its account and region, '" + wfrn + "' does not");
        }
        return new ResourceIdentity(name.format(), name.account(), name.region(), name.type(), name.service());
    }
}
