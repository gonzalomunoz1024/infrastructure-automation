package com.iaf.console.fake.identity.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Ids;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Scopes;
import com.iaf.console.fake.identity.domain.ServiceAccountRecord;
import com.iaf.console.kit.scope.AccountScope;

/** The service accounts of the fake: created with presets, rotated with a grace period, looked up by token. */
public final class ServiceAccounts {

    /** How long a rotated token keeps working. */
    public static final Duration ROTATION_GRACE = Duration.ofMinutes(10);

    private final Map<String, ServiceAccountRecord> accounts = new ConcurrentHashMap<>();
    private final FakeClock clock;
    private final Ids ids;

    public ServiceAccounts(FakeClock clock, Ids ids) {
        this.clock = clock;
        this.ids = ids;
    }

    /** The account a bearer token belongs to, empty when none accepts it. */
    public Optional<Caller> byToken(String token) {
        String hash = Json.sha256(token);
        for (ServiceAccountRecord account : accounts.values()) {
            if (account.accepts(hash, clock.now())) {
                return Optional.of(account.caller());
            }
        }
        return Optional.empty();
    }

    public List<ObjectNode> list() {
        List<ObjectNode> out = new ArrayList<>();
        accounts.values().stream().sorted(java.util.Comparator.comparing(ServiceAccountRecord::id)).forEach(a -> out.add(a.document()));
        return out;
    }

    public Optional<ServiceAccountRecord> get(String id) {
        return Optional.ofNullable(accounts.get(id));
    }

    /** Seeds an account with a known token, for demos and tests. */
    public ServiceAccountRecord seed(String id, String name, List<String> teams, List<String> roles, Set<String> scopes, String consoleAccount,
            String token) {
        ServiceAccountRecord record = new ServiceAccountRecord(id, name, roles, teams, scopes, clock.now(), consoleAccount, Json.sha256(token));
        accounts.put(id, record);
        return record;
    }

    /** Creates an account from a CreateServiceAccount body; the answer carries the token once. */
    public ObjectNode create(Caller creator, JsonNode body) {
        String name = body.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name is required");
        }
        String preset = body.path("preset").asText(null);
        String consoleAccount = body.path("consoleAccount").asText(null);
        Set<String> scopes = new LinkedHashSet<>();
        List<String> requested = texts(body.path("scopes"));
        for (String scope : requested) {
            if (!Scopes.isKnown(scope)) {
                throw ApiException.badRequest("unknown scope '" + scope + "'");
            }
        }
        if (preset != null) {
            List<String> presetScopes = Scopes.PRESETS.get(preset);
            if (presetScopes == null) {
                throw ApiException.badRequest("unknown preset '" + preset + "'");
            }
            if (!requested.isEmpty() && !presetScopes.containsAll(requested)) {
                throw ApiException.badRequest("scopes must fit in the preset '" + preset + "'");
            }
            scopes.addAll(requested.isEmpty() ? presetScopes : requested);
        } else {
            if (requested.isEmpty()) {
                throw ApiException.badRequest("scopes or a preset is required");
            }
            scopes.addAll(requested);
        }
        List<String> teams = texts(body.path("teams"));
        String note = null;
        if (consoleAccount != null) {
            if (!AccountScope.isAccountId(consoleAccount)) {
                throw ApiException.badRequest("consoleAccount must be a 12-digit account id");
            }
            if (!"console".equals(preset)) {
                throw ApiException.badRequest("consoleAccount needs the console preset");
            }
            if (teams.size() != 1) {
                throw ApiException.badRequest("a console account's teams are exactly its owning team");
            }
            for (String scope : scopes) {
                if (Scopes.FORBIDDEN_FOR_CONSOLE.contains(scope)) {
                    throw ApiException.badRequest("a console account can never hold " + scope);
                }
            }
        }
        if (teams.isEmpty()) {
            if (creator.teams().isEmpty()) {
                note = "no team: the policy refuses every job";
            } else {
                teams = creator.teams();
                note = "teams taken from the creator: " + String.join(", ", teams);
            }
        }
        String id = ids.serviceAccount();
        String token = ids.token();
        ServiceAccountRecord record = new ServiceAccountRecord(id, name, texts(body.path("roles")), teams, scopes, clock.now(), consoleAccount,
                Json.sha256(token));
        accounts.put(id, record);
        ObjectNode out = Json.object();
        out.put("id", id);
        out.put("token", token);
        var scopesNode = out.putArray("scopes");
        scopes.forEach(scopesNode::add);
        var teamsNode = out.putArray("teams");
        teams.forEach(teamsNode::add);
        if (note != null) {
            out.put("note", note);
        }
        return out;
    }

    public ObjectNode rotate(String id) {
        ServiceAccountRecord record = accounts.get(id);
        if (record == null) {
            throw ApiException.notFound("service account", id);
        }
        String token = ids.token();
        var graceUntil = clock.now().plus(ROTATION_GRACE);
        record.rotate(Json.sha256(token), graceUntil);
        ObjectNode out = Json.object();
        out.put("id", id);
        out.put("token", token);
        out.put("graceUntil", graceUntil.toString());
        return out;
    }

    public void delete(String id) {
        if (accounts.remove(id) == null) {
            throw ApiException.notFound("service account", id);
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            out.add(item.asText());
        }
        return out;
    }
}
