package com.iaf.console.bff.types.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.ConsoleBlock;
import com.iaf.console.platform.model.ResourceType;

/**
 * A platform resource type and its catalog entries as one {@link ConsoleType}, with the defaults
 * of P-06 where the type's console block says nothing: the title from the plural, a generic icon,
 * the list columns from the schema's {@code x-console.column} hints, the usual tabs, and the
 * lifecycle classes by state name. The verbs come from the catalog entries whose noun is the type.
 */
public final class Descriptors {

    public static final String DEFAULT_ICON = "box";
    public static final List<String> DEFAULT_TABS = List.of("configuration", "drift", "activity", "relationships", "tags", "json");
    public static final List<String> BUILT_IN_COLUMNS = List.of("name", "status", "wfrn", "owner", "created", "updated");
    private static final List<String> METHODS = List.of("POST", "PATCH", "DELETE");

    private static final Map<String, String> LIFECYCLE_BY_NAME = Map.ofEntries(
            Map.entry("requested", "pending"), Map.entry("pending", "pending"), Map.entry("building", "pending"), Map.entry("creating", "pending"),
            Map.entry("provisioning", "pending"), Map.entry("queued", "pending"),
            Map.entry("ready", "active"), Map.entry("active", "active"), Map.entry("running", "active"), Map.entry("available", "active"),
            Map.entry("deprecated", "active"), Map.entry("stopped", "active"), Map.entry("attached", "active"), Map.entry("detached", "active"),
            Map.entry("updating", "updating"), Map.entry("resizing", "updating"), Map.entry("modifying", "updating"), Map.entry("upgrading", "updating"),
            Map.entry("deleting", "deleting"), Map.entry("retiring", "deleting"), Map.entry("terminating", "deleting"),
            Map.entry("deleted", "deleted"), Map.entry("retired", "deleted"), Map.entry("terminated", "deleted"), Map.entry("gone", "deleted"),
            Map.entry("failed", "failed"), Map.entry("error", "failed"), Map.entry("broken", "failed"));

    private Descriptors() {
    }

    public static ConsoleType describe(ResourceType type, List<CatalogEntry> catalog) {
        Objects.requireNonNull(type, "type");
        String service = serviceOf(type);
        String plural = type.plural() == null || type.plural().isBlank() ? type.name() + "s" : type.plural();
        ConsoleBlock console = type.console();
        String title = console != null && console.title() != null && !console.title().isBlank() ? console.title() : capitalise(plural);
        String icon = console != null && console.icon() != null && !console.icon().isBlank() ? console.icon() : DEFAULT_ICON;
        ConsoleType.ListSpec list = listSpec(type, console);
        ConsoleType.DetailSpec detail = new ConsoleType.DetailSpec(
                console != null && console.detail() != null && console.detail().tabs() != null && !console.detail().tabs().isEmpty()
                        ? List.copyOf(console.detail().tabs()) : DEFAULT_TABS);
        Map<String, String> lifecycle = lifecycle(type, console);
        ConsoleType.Observe observe = type.observe() == null ? new ConsoleType.Observe(false, null, false, false)
                : new ConsoleType.Observe(true, type.observe().interval(), type.observe().metrics() != null, type.observe().discover() != null);
        List<ConsoleAction> actions = new ArrayList<>();
        for (CatalogEntry entry : catalog == null ? List.<CatalogEntry>of() : catalog) {
            if (entry.noun() != null && entry.noun().equals(type.name()) && entry.verb() != null) {
                actions.add(action(entry));
            }
        }
        actions.sort((a, b) -> rank(a.verb()) != rank(b.verb()) ? Integer.compare(rank(a.verb()), rank(b.verb())) : a.verb().compareTo(b.verb()));
        String etag = etag(type, actions);
        return new ConsoleType(service + "/" + type.name(), type.name(), service, plural, title, icon, type.description(), type.schema(), type.states(),
                list, detail, lifecycle, observe, actions, etag);
    }

    /** The console service of a type: {@code metadata.service}, else the owner team without its {@code team-} prefix. */
    public static String serviceOf(ResourceType type) {
        if (type.service() != null && !type.service().isBlank()) {
            return type.service();
        }
        String owner = type.owner() == null ? "platform" : type.owner();
        return owner.startsWith("team-") ? owner.substring(5) : owner;
    }

    static ConsoleType.ListSpec listSpec(ResourceType type, ConsoleBlock console) {
        if (console != null && console.list() != null && console.list().columns() != null && !console.list().columns().isEmpty()) {
            String sort = console.list().defaultSort() == null || console.list().defaultSort().isBlank() ? "-created" : console.list().defaultSort();
            return new ConsoleType.ListSpec(List.copyOf(console.list().columns()), sort);
        }
        List<String> columns = new ArrayList<>(List.of("name", "status"));
        JsonNode properties = type.schema() == null ? null : type.schema().path("properties");
        if (properties != null && properties.isObject()) {
            for (Map.Entry<String, JsonNode> field : properties.properties()) {
                if (field.getValue().path("x-console").path("column").asBoolean(false) && !columns.contains(field.getKey())) {
                    columns.add(field.getKey());
                }
            }
        }
        columns.add("created");
        return new ConsoleType.ListSpec(columns, "-created");
    }

    static Map<String, String> lifecycle(ResourceType type, ConsoleBlock console) {
        Map<String, String> lifecycle = new LinkedHashMap<>();
        for (String state : type.states() == null ? List.<String>of() : type.states()) {
            String byName = LIFECYCLE_BY_NAME.get(state.toLowerCase(Locale.ROOT));
            if (byName != null) {
                lifecycle.put(state, byName);
            }
        }
        if (console != null && console.lifecycle() != null) {
            lifecycle.putAll(console.lifecycle());
        }
        return lifecycle;
    }

    static ConsoleAction action(CatalogEntry entry) {
        String verb = entry.verb();
        String method = entry.method() != null && METHODS.contains(entry.method().toUpperCase(Locale.ROOT)) ? entry.method().toUpperCase(Locale.ROOT)
                : "update".equals(verb) ? "PATCH" : "delete".equals(verb) ? "DELETE" : "POST";
        JsonNode input = entry.input();
        if (input != null && input.isObject() && entry.subjectInput() != null && !entry.subjectInput().isBlank()) {
            ObjectNode copy = input.deepCopy();
            JsonNode properties = copy.get("properties");
            if (properties instanceof ObjectNode props) {
                props.remove(entry.subjectInput());
            }
            JsonNode required = copy.get("required");
            if (required != null && required.isArray()) {
                com.fasterxml.jackson.databind.node.ArrayNode kept = copy.putArray("required");
                for (JsonNode r : required) {
                    if (!entry.subjectInput().equals(r.asText())) {
                        kept.add(r);
                    }
                }
            }
            input = copy;
        }
        ConsoleAction.Deprecation deprecated = entry.deprecated() == null ? null
                : new ConsoleAction.Deprecation(entry.deprecated().since(), entry.deprecated().sunset(), entry.deprecated().message());
        boolean bulk = Boolean.TRUE.equals(entry.bulk()) || entry.batch() != null || (entry.batchPath() != null && !entry.batchPath().isBlank());
        return new ConsoleAction(verb, entry.title() == null ? capitalise(verb) : entry.title(), entry.description(), method, entry.isDestructive(),
                entry.needsApproval(), bulk, entry.hasPreview(), entry.effect() == null ? "unknown" : entry.effect(), entry.subjectInput(),
                entry.mutableFields(), input, entry.workflow(), entry.version(), deprecated, null, null);
    }

    private static int rank(String verb) {
        return switch (verb) {
            case "create" -> 0;
            case "update" -> 1;
            case "delete" -> 9;
            default -> 5;
        };
    }

    static String capitalise(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String spaced = s.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** A hash of what the platform said: the type's own hash or version and the catalog entries' workflow versions. */
    static String etag(ResourceType type, List<ConsoleAction> actions) {
        StringBuilder sb = new StringBuilder();
        sb.append(type.name()).append('|').append(type.hash() == null ? String.valueOf(type.version()) : type.hash());
        for (ConsoleAction action : actions) {
            sb.append('|').append(action.workflow()).append('@').append(action.version());
        }
        return sha256(sb.toString());
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
