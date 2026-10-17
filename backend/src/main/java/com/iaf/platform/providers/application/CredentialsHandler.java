package com.iaf.platform.providers.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.domain.AccessRules;
import com.iaf.platform.providers.ports.inbound.CredentialsCommands;
import com.iaf.platform.providers.ports.inbound.CredentialsQueries;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Credentials by name and type: the value goes to the {@code CredentialsPort} and never comes back.
 * Admins may store any credential. Anyone else must belong to a team, and a credential that a
 * provider or a published workflow already uses can only be changed by a team that owns one of them.
 */
@Primary
@Service
@RequiredArgsConstructor
public class CredentialsHandler implements CredentialsCommands, CredentialsQueries {

    public static final String STORED_EVENT = "credential.stored";
    public static final Set<String> TYPES = Set.of("token", "password", "ssh-key", "api-key", "basic", "file");

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_./-]*$");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final CredentialsPort credentials;
    private final EventBus events;
    private final ProviderStorePort providers;
    private final WorkflowStorePort workflows;

    @Override
    public Mono<CommandAccepted> storeCredential(StoreCredential command) {
        return CurrentIdentity.current().flatMap(who -> {
            if (command == null || command.name() == null || command.name().isBlank()) {
                throw new IllegalArgumentException("name is required: the Vault path, like vsphere/token");
            }
            String name = command.name().trim();
            if (name.startsWith("vault:")) {
                name = name.substring("vault:".length());
            }
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("name is a path of letters, digits, dots, dashes and slashes, like vsphere/token");
            }
            String type = command.type() == null || command.type().isBlank() ? "token" : command.type().trim().toLowerCase();
            if (!TYPES.contains(type)) {
                throw new IllegalArgumentException("type is one of " + TYPES.stream().sorted().toList());
            }
            if (command.value() == null || command.value().isBlank()) {
                throw new IllegalArgumentException("value is required");
            }
            requireChange(who, name);
            return credentials.store(name, type, command.value(), who.id()).map(info -> {
                ObjectNode data = F.objectNode();
                data.put("name", info.name());
                data.put("type", info.type());
                data.put("by", who.id());
                events.publish(STORED_EVENT, "credential", info.name(), who.id(), data);
                return CommandAccepted.of(CredentialsPort.referenceOf(info.name()), "stored");
            });
        });
    }

    /** Throws unless {@code who} may set the credential {@code name} (a Vault path without the prefix). */
    void requireChange(Identity who, String name) {
        if (who.admin()) {
            return;
        }
        if (who.teams().isEmpty()) {
            throw new ForbiddenException("Only members of a team can store credentials");
        }
        Pattern uses = Pattern.compile(Pattern.quote("vault:" + name) + "(?![A-Za-z0-9_./-])");
        List<String> owners = new ArrayList<>();
        Set<String> usedBy = new LinkedHashSet<>();
        for (StoredProvider p : providers.all()) {
            if (p.yaml() != null && uses.matcher(p.yaml()).find()) {
                owners.add(p.definition().owner());
                usedBy.add("provider " + p.definition().name());
            }
        }
        for (WorkflowRecord w : workflows.all()) {
            if (w.status() == WorkflowStatus.PUBLISHED && w.yaml() != null && uses.matcher(w.yaml()).find()) {
                owners.add(w.definition().metadata() == null ? null : w.definition().metadata().owner());
                usedBy.add("workflow " + w.name());
            }
        }
        if (!owners.isEmpty() && owners.stream().noneMatch(team -> AccessRules.canEdit(who, team))) {
            throw new ForbiddenException("vault:" + name + " is used by " + String.join(", ", usedBy)
                    + "; only " + String.join(" or ", new TreeSet<>(owners)) + " or an admin can change it");
        }
    }

    @Override
    public Mono<List<CredentialInfo>> credentials() {
        return credentials.list();
    }
}
