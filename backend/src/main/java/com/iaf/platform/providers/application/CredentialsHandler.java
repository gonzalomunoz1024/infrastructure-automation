package com.iaf.platform.providers.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.content.ContentFiles;
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
import reactor.core.scheduler.Schedulers;

/**
 * Credentials by name, type and owner team: the value goes to the {@code CredentialsPort} and
 * never comes back. The owner is the team that may use the credential in the provider wizard and
 * the imports: one of the caller's teams (their first when the command names none; a new value
 * keeps the owner the credential already has), any team for an admin. Admins may store any
 * credential. Anyone else must belong to a team, can only change a credential their team owns,
 * and a credential that a provider or a published workflow already uses can only be changed by a
 * team that owns one of them.
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
            String wanted = command.owner() == null || command.owner().isBlank() ? null : ContentFiles.requireSlug("The owner team", command.owner().trim());
            String key = name;
            return credentials.info(key).map(Optional::of).defaultIfEmpty(Optional.empty())
                    // the checks read every provider and workflow from the stores: never on the thread the vault answered on
                    .flatMap(existing -> Mono.fromCallable(() -> {
                        requireChange(who, key, existing.orElse(null));
                        return ownerOf(who, wanted, existing.orElse(null));
                    }).subscribeOn(Schedulers.boundedElastic()))
                    .flatMap(owner -> credentials.store(key, type, command.value(), who.id(), owner))
                    // the audit listener writes to its store as the event is published
                    .publishOn(Schedulers.boundedElastic())
                    .map(info -> {
                        ObjectNode data = F.objectNode();
                        data.put("name", info.name());
                        data.put("type", info.type());
                        if (info.owner() != null) {
                            data.put("owner", info.owner());
                        }
                        data.put("by", who.id());
                        events.publish(STORED_EVENT, "credential", info.name(), who.id(), data);
                        return CommandAccepted.of(CredentialsPort.referenceOf(info.name()), "stored");
                    });
        });
    }

    /**
     * The team the stored credential belongs to: the one named (one of the caller's, or any for
     * an admin), else the owner it already has, else the caller's first team.
     */
    static String ownerOf(Identity who, String wanted, CredentialInfo existing) {
        if (wanted != null) {
            if (!who.admin() && !who.teams().contains(wanted)) {
                throw new ForbiddenException(who.name() + " is not in team " + wanted + "; a credential's owner is one of the caller's teams");
            }
            return wanted;
        }
        if (existing != null && existing.owner() != null) {
            return existing.owner();
        }
        if (!who.teams().isEmpty()) {
            return who.teams().get(0);
        }
        throw new IllegalArgumentException("owner is required: the team that may use the credential");
    }

    /** Throws unless {@code who} may set the credential {@code name} (a Vault path without the prefix), {@code existing} as the store has it now. */
    void requireChange(Identity who, String name, CredentialInfo existing) {
        if (who.admin()) {
            return;
        }
        if (who.teams().isEmpty()) {
            throw new ForbiddenException("Only members of a team can store credentials");
        }
        if (existing != null && existing.owner() != null && !who.teams().contains(existing.owner())) {
            throw new ForbiddenException("vault:" + name + " is owned by " + existing.owner() + "; only " + existing.owner() + " or an admin can change it");
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
