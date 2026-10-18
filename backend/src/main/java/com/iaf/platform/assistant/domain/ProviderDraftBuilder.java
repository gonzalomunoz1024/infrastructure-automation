package com.iaf.platform.assistant.domain;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderDefinition.Alerts;
import com.iaf.platform.providers.domain.ProviderDefinition.Auth;
import com.iaf.platform.providers.domain.ProviderDefinition.Metadata;
import com.iaf.platform.providers.domain.ProviderDefinition.Slo;
import com.iaf.platform.providers.domain.ProviderDefinition.Spec;
import com.iaf.platform.providers.domain.ProviderDefinition.SpecSource;
import com.iaf.platform.providers.domain.ProviderType;
import com.iaf.platform.providers.domain.ProviderYaml;

/**
 * "Ask the assistant" in the Add provider wizard, the demo way: a {@code kind: Provider} file from
 * a one-line description. Credentials are named after the provider ({@code vault:<name>/token})
 * and listed as assumptions; nothing is invented beyond placeholders a person has to confirm.
 */
public final class ProviderDraftBuilder {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Pattern URL = Pattern.compile("https?://[^\\s,;)\"']+");
    private static final Set<String> GENERIC = Set.of("service", "api", "system", "server", "platform", "tool", "app", "application",
            "endpoint", "endpoints", "backend", "provider", "integration", "new", "our", "the", "a", "an", "this", "that", "my", "its");
    private static final Set<String> READ_VERBS = Set.of("list", "get", "read", "check", "scan", "status", "describe", "show", "find", "search", "query", "fetch", "verify");
    private static final Set<String> CREATE_VERBS = Set.of("create", "add", "start", "run", "trigger", "snapshot", "backup", "attach", "enable", "register", "open", "submit", "request", "provision", "build", "launch");
    private static final Set<String> CHANGE_VERBS = Set.of("update", "patch", "restart", "stop", "resize", "upgrade", "rotate", "sync", "restore", "deploy", "cancel", "retry", "detach", "disable", "pause", "resume", "set", "reboot", "scale", "migrate", "rename", "move");
    private static final Set<String> DELETE_VERBS = Set.of("delete", "remove", "destroy", "terminate", "purge", "drop");
    private static final Set<String> STOP_BEFORE_NAME = Set.of("at", "with", "that", "which", "to", "on", "for", "exposing", "offering", "providing", "via", "using", "from", "in");

    private ProviderDraftBuilder() {
    }

    public static ProviderDraft build(String description, Registry registry, String owner) {
        String text = description == null ? "" : description.trim();
        List<String> tokens = Words.tokens(text);
        List<String> assumptions = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        Registry reg = registry == null ? Registry.EMPTY : registry;

        ProviderType type = typeOf(tokens);
        String url = firstUrl(text);
        List<String> nameWords = nameWords(tokens, text);
        String name = nameWords.isEmpty() ? null : Ids.slug(String.join(" ", nameWords));
        if (name == null || name.isBlank()) {
            name = url != null ? hostWord(url) : "new-provider";
            if ("new-provider".equals(name)) {
                questions.add("What is the system called? The name becomes the provider id and the credential path.");
            } else {
                assumptions.add("The provider is named after the host of " + url + ".");
            }
        }
        String title = nameWords.isEmpty() ? Words.capitalize(name.replace('-', ' ')) : titleOf(nameWords, text);
        if (reg.provider(name).isPresent()) {
            questions.add("A provider named '" + name + "' already exists; should this draft replace it as a new version or take another name?");
        }

        Map<String, String> endpoints = endpoints(url, name, assumptions);
        Auth auth = authOf(tokens, name, assumptions, questions);
        List<Action> actions = actions(tokens, type, name, assumptions);
        if (actions.isEmpty()) {
            actions.add(action("status.get", "Get status", "get", "status", Effect.READ, type, name));
            questions.add("Which operations should the provider offer? Name them as verb + noun, like 'list jobs' or 'create job'.");
        }
        String teamShort = owner == null || owner.isBlank() ? "platform" : owner.replaceFirst("^team-", "");
        SpecSource spec = type == ProviderType.OPENAPI
                ? new SpecSource(url == null ? "https://" + name + ".example.com/openapi.json" : (url.endsWith(".json") || url.endsWith(".yaml") ? url : url.replaceAll("/+$", "") + "/openapi.json"), null, null)
                : null;
        if (spec != null) {
            assumptions.add("The OpenAPI spec is expected at " + spec.url() + "; the import pins its sha256 at publish.");
        }
        JsonNode source = type == ProviderType.ANSIBLE ? ansibleSource(url, name) : null;
        JsonNode module = type == ProviderType.TERRAFORM ? terraformModule(url, name) : null;
        JsonNode tile = type == ProviderType.IDP ? F.objectNode().put("id", actions.get(0).id()) : null;
        if (type == ProviderType.ANSIBLE) {
            assumptions.add("Playbooks are expected in a Git repository pinned to a tag and a commit; fill source.git and source.commit.");
        }
        if (type == ProviderType.TERRAFORM) {
            assumptions.add("The module source and version are placeholders to pin before publishing.");
        }
        if (type == ProviderType.IDP) {
            assumptions.add("IDP tiles are mocked until the IDP tiles API is wired.");
        }
        Spec s = new Spec(type, spec, type == ProviderType.ANSIBLE || type == ProviderType.TERRAFORM || type == ProviderType.IDP ? Map.of() : endpoints,
                auth, actions, Map.of(), List.of(), new Slo(99.0, 1000), new Alerts("#" + teamShort + "-alerts"), source, module, tile, null,
                type == ProviderType.ANSIBLE ? "ee-platform:2.17" : null);
        ProviderDefinition def = new ProviderDefinition("iaf.platform/v1", new Metadata(name, title, owner == null || owner.isBlank() ? "shared" : owner, "0.1.0",
                text.isBlank() ? null : Words.clip(text, 200)), s);
        assumptions.add("SLO defaults to 99.0% success and p95 1000 ms; alerts go to #" + teamShort + "-alerts.");
        assumptions.add("Reads are marked retry safe; creates and changes retry unsafe; deletes require an approval.");

        String summary = "Provider " + title + " (" + type.key() + ") with " + actions.size() + (actions.size() == 1 ? " action: " : " actions: ")
                + String.join(", ", actions.stream().map(Action::id).toList()) + "."
                + (url != null ? " Endpoints for dev, test and prod are derived from " + url + "." : " Endpoints are placeholders to fill in.")
                + " The credential is " + auth.credential() + ", which must exist in the registry.";
        return new ProviderDraft(ProviderYaml.toYaml(def), Words.clip(summary, 600), List.copyOf(assumptions), List.copyOf(questions));
    }

    // ---------------------------------------------------------------- pieces

    static ProviderType typeOf(List<String> tokens) {
        if (Words.containsAny(tokens, "openapi", "swagger", "spec", "specification")) {
            return ProviderType.OPENAPI;
        }
        if (Words.containsAny(tokens, "ansible", "playbook", "playbooks")) {
            return ProviderType.ANSIBLE;
        }
        if (Words.containsAny(tokens, "terraform", "module", "modules", "tofu")) {
            return ProviderType.TERRAFORM;
        }
        if (Words.containsAny(tokens, "tile", "tiles", "idp", "portal")) {
            return ProviderType.IDP;
        }
        return ProviderType.HTTP;
    }

    static String firstUrl(String text) {
        Matcher m = URL.matcher(text);
        return m.find() ? m.group().replaceAll("[.,;]+$", "") : null;
    }

    /** "the backup service at https://..." -> [backup, service]; "called Foo" -> [foo]. */
    static List<String> nameWords(List<String> tokens, String text) {
        Matcher called = Pattern.compile("(?i)\\b(?:called|named)\\s+\"?([A-Za-z][A-Za-z0-9 _-]{0,40}?)\"?(?=\\s*(?:[,.;(]|\\bat\\b|\\bwith\\b|\\bthat\\b|$))").matcher(text);
        if (called.find()) {
            return Words.tokens(called.group(1));
        }
        List<String> out = new ArrayList<>();
        for (String t : tokens) {
            if (t.startsWith("http")) {
                break;
            }
            if (STOP_BEFORE_NAME.contains(t) && !out.isEmpty()) {
                break;
            }
            if (Words.STOP.contains(t) || GENERIC.contains(t) || t.matches("^\\d.*") || isTypeWord(t)) {
                if (!out.isEmpty() && STOP_BEFORE_NAME.contains(t)) {
                    break;
                }
                continue;
            }
            if (isActionVerb(t)) {
                break;
            }
            out.add(t);
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    private static boolean isTypeWord(String t) {
        return Set.of("openapi", "swagger", "spec", "specification", "ansible", "playbook", "playbooks", "terraform", "module", "modules",
                "tile", "tiles", "idp", "portal", "http", "rest", "json").contains(t);
    }

    private static boolean isActionVerb(String t) {
        return READ_VERBS.contains(t) || CREATE_VERBS.contains(t) || CHANGE_VERBS.contains(t) || DELETE_VERBS.contains(t);
    }

    /** The title keeps the generic word the description used ("Backup service"). */
    static String titleOf(List<String> nameWords, String text) {
        List<String> tokens = Words.tokens(text);
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (String t : tokens) {
            if (!started && !Words.same(t, nameWords.get(0))) {
                continue;
            }
            started = true;
            if (t.startsWith("http") || STOP_BEFORE_NAME.contains(t) || isActionVerb(t) || isTypeWord(t)) {
                break;
            }
            sb.append(sb.isEmpty() ? Words.capitalize(t) : " " + t);
            if (sb.length() > 40) {
                break;
            }
        }
        return sb.isEmpty() ? Words.capitalize(String.join(" ", nameWords)) : sb.toString();
    }

    static String hostWord(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return "new-provider";
            }
            String first = host.split("\\.")[0].replaceAll("-(dev|test|prod|staging)$", "");
            return Ids.slug(first.isBlank() ? "new-provider" : first);
        } catch (IllegalArgumentException e) {
            return "new-provider";
        }
    }

    /** dev, test and prod endpoints from one URL: the host gets a -dev / -test suffix. */
    static Map<String, String> endpoints(String url, String name, List<String> assumptions) {
        Map<String, String> out = new LinkedHashMap<>();
        if (url == null) {
            out.put("dev", "https://" + name + "-dev.example.com");
            out.put("test", "https://" + name + "-test.example.com");
            out.put("prod", "https://" + name + ".example.com");
            assumptions.add("No URL was given, so the endpoints are placeholders.");
            return out;
        }
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? name + ".example.com" : u.getHost();
            String rest = (u.getPath() == null ? "" : u.getPath()) + (u.getQuery() == null ? "" : "?" + u.getQuery());
            if (rest.endsWith("/")) {
                rest = rest.substring(0, rest.length() - 1);
            }
            String scheme = u.getScheme() == null ? "https" : u.getScheme();
            String port = u.getPort() > 0 ? ":" + u.getPort() : "";
            String base = host.replaceAll("-(dev|test|prod|staging)(?=\\.)", "");
            int dot = base.indexOf('.');
            String dev = dot > 0 ? base.substring(0, dot) + "-dev" + base.substring(dot) : base + "-dev";
            String test = dot > 0 ? base.substring(0, dot) + "-test" + base.substring(dot) : base + "-test";
            out.put("dev", scheme + "://" + dev + port + rest);
            out.put("test", scheme + "://" + test + port + rest);
            out.put("prod", scheme + "://" + base + port + rest);
            assumptions.add("The dev and test endpoints add -dev and -test to the host of " + url + "; change them if the environments live elsewhere.");
        } catch (IllegalArgumentException e) {
            out.put("prod", url);
            assumptions.add("Only the production endpoint is known.");
        }
        return out;
    }

    static Auth authOf(List<String> tokens, String name, List<String> assumptions, List<String> questions) {
        Auth auth;
        if (Words.containsAny(tokens, "basic") || (Words.containsAny(tokens, "username", "password"))) {
            auth = new Auth("basic", "vault:" + name + "/basic", null);
        } else if (Words.containsAny(tokens, "apikey", "api-key", "x-api-key") || (Words.contains(tokens, "api") && Words.contains(tokens, "key"))) {
            auth = new Auth("header", "vault:" + name + "/api-key", "X-Api-Key");
        } else if (Words.containsAny(tokens, "anonymous", "unauthenticated")) {
            auth = new Auth("none", null, null);
        } else {
            auth = new Auth("bearer", "vault:" + name + "/token", null);
        }
        if (auth.credential() != null) {
            assumptions.add("Signs in with a " + auth.type() + " credential named " + auth.credential() + "; it must exist in the registry (names only, never values).");
            questions.add("Which registry credential holds the " + ("basic".equals(auth.type()) ? "username and password" : "token") + "? The draft uses " + auth.credential() + ".");
        }
        return auth;
    }

    /** "with list jobs and create job" -> jobs.list (read), job.create (create). */
    static List<Action> actions(List<String> tokens, ProviderType type, String name, List<String> assumptions) {
        List<Action> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean hasUrl = tokens.stream().anyMatch(t -> t.startsWith("http"));
        Set<String> nameParts = name == null ? Set.of() : Set.of(name.split("-"));
        boolean afterUrl = false;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.startsWith("http")) {
                afterUrl = true;
                continue;
            }
            if (!isActionVerb(t)) {
                continue;
            }
            // the system's own name ("the backup service") is not an action, nor is a verb before its URL
            if ((!afterUrl && (i == 0 || hasUrl)) || nameParts.contains(t)) {
                continue;
            }
            int j = i + 1;
            while (j < tokens.size() && (Words.STOP.contains(tokens.get(j)) || tokens.get(j).matches("^(a|an|the|all|its|existing)$"))) {
                j++;
            }
            if (j >= tokens.size() || isActionVerb(tokens.get(j)) || tokens.get(j).startsWith("http")) {
                continue;
            }
            String nounWord = singular(tokens.get(j));
            Effect effect = READ_VERBS.contains(t) ? Effect.READ : CREATE_VERBS.contains(t) ? Effect.CREATE : DELETE_VERBS.contains(t) ? Effect.DELETE : Effect.CHANGE;
            String id = nounWord + "." + t;
            if (seen.add(id)) {
                out.add(action(id, Words.capitalize(t) + " " + tokens.get(j), t, nounWord, effect, type, name));
            }
        }
        if (!out.isEmpty()) {
            assumptions.add("Actions come from the verbs in the description: " + String.join(", ", out.stream().map(Action::id).toList()) + ".");
        }
        return out;
    }

    static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 4) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && word.length() > 2) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    static Action action(String id, String title, String verb, String nounWord, Effect effect, ProviderType type, String provider) {
        String plural = nounWord.endsWith("s") ? nounWord : nounWord + "s";
        boolean byId = effect != Effect.READ || "get".equals(verb) || "describe".equals(verb) || "show".equals(verb);
        boolean list = effect == Effect.READ && !byId;
        String operation = null;
        String method = null;
        String path = null;
        String playbook = null;
        switch (type) {
            case OPENAPI -> operation = Words.camel(verb + " " + (list ? plural : nounWord));
            case ANSIBLE -> playbook = nounWord + "/" + verb + ".yml";
            case HTTP -> {
                method = switch (effect) {
                    case READ -> "GET";
                    case CREATE -> "POST";
                    case DELETE -> "DELETE";
                    default -> "POST";
                };
                path = list ? "/" + plural : effect == Effect.CREATE ? "/" + plural : "/" + plural + "/{id}" + (effect == Effect.CHANGE ? ":" + verb : "");
            }
            default -> {
            }
        }
        ObjectNode input = F.objectNode();
        input.put("type", "object");
        ObjectNode props = input.putObject("properties");
        if (effect == Effect.CREATE) {
            input.putArray("required").add("name");
            props.putObject("name").put("type", "string");
        } else if (byId) {
            input.putArray("required").add("id");
            props.putObject("id").put("type", "string");
        }
        ObjectNode example = F.objectNode();
        if (list) {
            example.putArray("items");
            example.put("total", 0);
        } else {
            example.put("id", nounWord + "-1");
            example.put("state", effect == Effect.DELETE ? "deleted" : "ok");
        }
        ObjectNode vars = type == ProviderType.ANSIBLE || type == ProviderType.TERRAFORM ? input : null;
        String tfId = type == ProviderType.TERRAFORM ? (effect == Effect.DELETE ? "destroy" : effect == Effect.READ ? "plan" : "apply") : id;
        return new Action(tfId, title, operation, method, path, effect, effect == Effect.READ, effect == Effect.DELETE, null, null,
                vars == null ? input : null, null, null, type == ProviderType.HTTP ? example : null, playbook, vars, null);
    }

    static JsonNode ansibleSource(String url, String name) {
        ObjectNode source = F.objectNode();
        ObjectNode git = source.putObject("git");
        git.put("repo", url == null ? "github.com/example/" + name + "-playbooks" : url.replaceFirst("^https?://", ""));
        git.put("ref", "main");
        source.put("commit", "0000000000000000000000000000000000000000");
        return source;
    }

    static JsonNode terraformModule(String url, String name) {
        ObjectNode module = F.objectNode();
        module.put("source", url == null ? "git::https://github.com/example/tf-modules//" + name : "git::" + url);
        module.put("version", "0.1.0");
        return module;
    }
}
