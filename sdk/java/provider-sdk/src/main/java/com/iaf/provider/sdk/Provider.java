package com.iaf.provider.sdk;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

/**
 * A custom-code provider: who owns it, the worker that serves it and the actions it offers. It is
 * both the code (each action's handler) and the source of the provider file the platform reads
 * ({@link #toYaml()}), so the two cannot drift.
 *
 * <pre>
 * Provider cmdb = Provider.named("cmdb")
 *         .title("Configuration items")
 *         .owner("team-platform")
 *         .version("1.0.0")
 *         .heartbeatTimeout(Duration.ofSeconds(30))
 *         .error("not_found", false, "No configuration item has that id")
 *         .action(Action.of("item.get", Lookup.class, Item.class, store::get).title("Get an item").effect(Effect.READ))
 *         .build();
 * </pre>
 */
public final class Provider {

    public static final String API_VERSION = "iaf.platform/v1";
    public static final String KIND = "Provider";
    public static final String TYPE = "sdk";

    static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]*$");
    static final Pattern ACTION_ID = Pattern.compile("^[a-z][A-Za-z0-9]*\\.[a-z][A-Za-z0-9]*$");
    static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$");
    static final Pattern TASK_QUEUE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$");
    static final Pattern ERROR_CLASS = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]*$");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .build());

    private final String name;
    private final String title;
    private final String owner;
    private final String version;
    private final String description;
    private final String taskQueue;
    private final Duration heartbeatTimeout;
    private final String alertsChannel;
    private final Map<String, Action<?, ?>> actions;
    private final List<ErrorClass> errors;

    private Provider(Builder b, Map<String, Action<?, ?>> actions) {
        this.name = b.name;
        this.title = b.title == null || b.title.isBlank() ? b.name : b.title;
        this.owner = b.owner;
        this.version = b.version;
        this.description = b.description;
        this.taskQueue = b.taskQueue == null || b.taskQueue.isBlank() ? "provider-" + b.name : b.taskQueue;
        this.heartbeatTimeout = b.heartbeatTimeout;
        this.alertsChannel = b.alertsChannel;
        this.actions = actions;
        this.errors = List.copyOf(b.errors);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public String title() {
        return title;
    }

    public String owner() {
        return owner;
    }

    public String version() {
        return version;
    }

    public String description() {
        return description;
    }

    /** The task queue the worker listens on and the platform schedules the actions on. */
    public String taskQueue() {
        return taskQueue;
    }

    /** How long a call may go without a heartbeat before the platform counts the worker lost; empty = no limit. */
    public Optional<Duration> heartbeatTimeout() {
        return Optional.ofNullable(heartbeatTimeout);
    }

    public List<Action<?, ?>> actions() {
        return List.copyOf(actions.values());
    }

    public Optional<Action<?, ?>> action(String id) {
        return Optional.ofNullable(id == null ? null : actions.get(id));
    }

    public List<ErrorClass> errors() {
        return errors;
    }

    /** The provider file as a JSON tree: kind Provider, type sdk, the worker and every action with its schemas. */
    public ObjectNode toDocument() {
        ObjectNode root = F.objectNode();
        root.put("apiVersion", API_VERSION);
        root.put("kind", KIND);
        ObjectNode md = root.putObject("metadata");
        md.put("name", name);
        md.put("title", title);
        md.put("owner", owner);
        md.put("version", version);
        put(md, "description", description);
        ObjectNode spec = root.putObject("spec");
        spec.put("type", TYPE);
        ObjectNode worker = spec.putObject("worker");
        worker.put("taskQueue", taskQueue);
        if (heartbeatTimeout != null) {
            worker.put("heartbeatTimeout", format(heartbeatTimeout));
        }
        ArrayNode list = spec.putArray("actions");
        for (Action<?, ?> a : actions.values()) {
            ObjectNode o = list.addObject();
            o.put("id", a.id());
            o.put("title", a.title());
            o.put("effect", a.effect().key());
            put(o, "plan", a.plan());
            put(o, "description", a.description());
            o.set("input", a.inputSchema());
            o.set("output", a.outputSchema());
            if (a.sample() != null) {
                o.set("sample", a.sample());
            }
        }
        if (!errors.isEmpty()) {
            ArrayNode errs = spec.putArray("errors");
            for (ErrorClass e : errors) {
                ObjectNode o = errs.addObject();
                o.put("class", e.name());
                o.put("retry", e.retryable());
                put(o, "description", e.description());
            }
        }
        if (alertsChannel != null && !alertsChannel.isBlank()) {
            spec.putObject("alerts").put("channel", alertsChannel);
        }
        return root;
    }

    /** The provider file the platform registers: {@code providers/<owner>/<name>.yaml}. */
    public String toYaml() {
        try {
            return YAML.writeValueAsString(toDocument());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A duration as provider files spell it: 30s, 2m, 1h, 500ms. */
    static String format(Duration d) {
        long ms = d.toMillis();
        if (ms % 3_600_000L == 0) {
            return ms / 3_600_000L + "h";
        }
        if (ms % 60_000L == 0) {
            return ms / 60_000L + "m";
        }
        if (ms % 1000L == 0) {
            return ms / 1000L + "s";
        }
        return ms + "ms";
    }

    private static void put(ObjectNode o, String key, String value) {
        if (value != null && !value.isBlank()) {
            o.put(key, value);
        }
    }

    public static final class Builder {
        private final String name;
        private String title;
        private String owner;
        private String version;
        private String description;
        private String taskQueue;
        private Duration heartbeatTimeout;
        private String alertsChannel;
        private final List<Object> actions = new ArrayList<>();
        private final List<ErrorClass> errors = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /** The team that owns the provider: it may edit and publish it. */
        public Builder owner(String owner) {
            this.owner = owner;
            return this;
        }

        /** A semantic version, like 1.0.0; publishing opens a change request for it. */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** The task queue to listen on; default {@code provider-<name>}. */
        public Builder taskQueue(String taskQueue) {
            this.taskQueue = taskQueue;
            return this;
        }

        /** How long a call may go without a heartbeat before the platform fails it as worker-lost. */
        public Builder heartbeatTimeout(Duration timeout) {
            this.heartbeatTimeout = timeout;
            return this;
        }

        /** The channel health alerts about this provider go to, like {@code #platform-alerts}. */
        public Builder alerts(String channel) {
            this.alertsChannel = channel;
            return this;
        }

        /** An error class the actions fail with. */
        public Builder error(String errorClass, boolean retryable, String description) {
            errors.add(new ErrorClass(errorClass, retryable, description));
            return this;
        }

        public Builder action(Action<?, ?> action) {
            actions.add(action);
            return this;
        }

        public Builder action(Action.Builder<?, ?> action) {
            actions.add(action);
            return this;
        }

        /** The provider, after checking it the way the platform's provider checks would; an IllegalArgumentException lists every problem. */
        public Provider build() {
            List<String> problems = new ArrayList<>();
            if (name == null || !NAME.matcher(name).matches()) {
                problems.add("The name is lowercase letters, digits and dashes, like ip-pool; was " + name);
            }
            if (owner == null || !NAME.matcher(owner).matches()) {
                problems.add("owner is the team that owns the provider, like team-platform; was " + owner);
            }
            if (version == null || !SEMVER.matcher(version).matches()) {
                problems.add("version is semantic, like 1.0.0; was " + version);
            }
            if (taskQueue != null && !TASK_QUEUE.matcher(taskQueue).matches()) {
                problems.add("The task queue is letters, digits, dots, dashes and underscores; was " + taskQueue);
            }
            if (heartbeatTimeout != null && (heartbeatTimeout.isNegative() || heartbeatTimeout.toMillis() < 1000)) {
                problems.add("heartbeatTimeout is at least one second; was " + heartbeatTimeout);
            }
            Map<String, Action<?, ?>> built = new LinkedHashMap<>();
            for (Object o : actions) {
                Action<?, ?> a;
                try {
                    a = o instanceof Action.Builder<?, ?> b ? b.build() : (Action<?, ?>) o;
                } catch (RuntimeException e) {
                    problems.add(e.getMessage());
                    continue;
                }
                if (a.id() == null || !ACTION_ID.matcher(a.id()).matches()) {
                    problems.add("Action ids read as <noun>.<verb>, like address.reserve; was " + a.id());
                }
                if (a.effect() == null) {
                    problems.add("Action " + a.id() + " says what it does: effect read, create, change or delete");
                }
                for (String which : List.of("input", "output")) {
                    ObjectNode schema = which.equals("input") ? a.inputSchema() : a.outputSchema();
                    if (schema.has("type") && !"object".equals(schema.path("type").asText())) {
                        problems.add("The " + which + " of " + a.id() + " is a JSON object; use a record");
                    }
                }
                if (built.putIfAbsent(a.id(), a) != null) {
                    problems.add("Action id " + a.id() + " is used twice");
                }
            }
            if (built.isEmpty()) {
                problems.add("A provider offers at least one action");
            }
            for (Action<?, ?> a : built.values()) {
                if (a.plan() != null && (a.plan().equals(a.id()) || !built.containsKey(a.plan()))) {
                    problems.add("The plan of " + a.id() + " names another action of the provider; was " + a.plan());
                }
            }
            Set<String> classes = new HashSet<>();
            for (ErrorClass e : errors) {
                if (e.name() == null || !ERROR_CLASS.matcher(e.name()).matches()) {
                    problems.add("An error class is a word like pool_exhausted; was " + e.name());
                } else if (!classes.add(e.name())) {
                    problems.add("Error class " + e.name() + " is declared twice");
                }
            }
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException("Provider " + name + " is not valid:\n  - " + String.join("\n  - ", problems));
            }
            return new Provider(this, built);
        }
    }
}
