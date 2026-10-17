package com.iaf.platform.identity.adapters.outbound;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.identity.ports.outbound.UserDirectory;

/** The people in the demo. Production reads the IdP's claims instead (see JwtTokenVerifier). */
@Component
public class DemoUserDirectory implements UserDirectory {

    private static final Map<String, Identity> USERS = Map.of(
            "gonzalo", new Identity("gonzalo", "Gonzalo", "gonzalo@example.com",
                    List.of("team-compute", "team-platform", "compute-approvers", "data-approvers"), List.of("ops", "sre", "admin"), true, Identity.Kind.USER),
            "ana", new Identity("ana", "Ana", "ana@example.com", List.of("team-data", "data-approvers"), List.of("ops"), false, Identity.Kind.USER),
            "mateo", new Identity("mateo", "Mateo", "mateo@example.com", List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER),
            "junior", new Identity("junior", "Junior engineer", "junior@example.com", List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER));

    @Override
    public Optional<Identity> byId(String id) {
        return Optional.ofNullable(USERS.get(id));
    }
}
