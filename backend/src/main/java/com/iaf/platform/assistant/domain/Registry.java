package com.iaf.platform.assistant.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;

/**
 * What the demo agent knows at drafting time: the providers, the published workflows and the
 * resource types, read through the other slices' query ports. Every product name the agent
 * ever uses comes from here.
 */
public record Registry(List<ProviderDefinition> providers, List<WorkflowRecord> workflows, List<ResourceTypeDefinition> types) {

    public static final Registry EMPTY = new Registry(List.of(), List.of(), List.of());

    /** Title words too generic to identify a provider on their own. */
    private static final Set<String> GENERIC = Set.of("service", "api", "manager", "ops", "modules", "module", "tiles", "tile", "builder",
            "repository", "shared", "enterprise", "platform", "system", "server", "the", "a", "an", "and", "of");

    public Registry {
        providers = providers == null ? List.of() : List.copyOf(providers);
        workflows = workflows == null ? List.of() : List.copyOf(workflows);
        types = types == null ? List.of() : List.copyOf(types);
    }

    public Optional<ProviderDefinition> provider(String name) {
        return providers.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    /** A provider by name, title or a close spelling of either. */
    public Optional<ProviderDefinition> providerLike(String word) {
        if (word == null) {
            return Optional.empty();
        }
        return providers.stream().filter(p -> Words.same(p.name(), word) || providerWords(p).stream().anyMatch(w -> Words.same(w, word))).findFirst();
    }

    public Optional<ResourceTypeDefinition> type(String name) {
        return types.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    /** The resource type a noun names (vm, vms, database, databases). */
    public Optional<ResourceTypeDefinition> typeForNoun(String noun) {
        if (noun == null) {
            return Optional.empty();
        }
        return types.stream().filter(t -> Words.same(t.name(), noun) || Words.same(t.plural(), noun)).findFirst();
    }

    public Optional<WorkflowRecord> workflow(String name) {
        return workflows.stream().filter(w -> w.name().equals(name)).findFirst();
    }

    /** The nouns provider actions act on: {@code vm} from {@code vm.restart}. */
    public Set<String> actionNouns() {
        Set<String> out = new LinkedHashSet<>();
        for (ProviderDefinition p : providers) {
            for (Action a : p.actions()) {
                int dot = a.id().indexOf('.');
                if (dot > 0) {
                    out.add(a.id().substring(0, dot));
                }
            }
        }
        return out;
    }

    /** The words that identify a provider: its name and the specific words of its title. */
    public static List<String> providerWords(ProviderDefinition p) {
        List<String> out = new ArrayList<>();
        out.add(p.name());
        for (String part : p.name().split("-")) {
            if (!part.isEmpty() && !GENERIC.contains(part)) {
                out.add(part);
            }
        }
        for (String w : Words.tokens(p.title())) {
            if (!GENERIC.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** Every word an action can be found by: its id parts, title and description. */
    public static List<String> actionWords(Action a) {
        List<String> out = new ArrayList<>();
        for (String part : a.id().split("[.\\-_]")) {
            out.addAll(splitCamel(part));
        }
        out.addAll(Words.content(Words.tokens(a.title())));
        out.addAll(Words.content(Words.tokens(a.description())));
        return out;
    }

    /** waitForPowerState -> wait, for, power, state. */
    static List<String> splitCamel(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split("(?<=[a-z0-9])(?=[A-Z])")) {
            if (!p.isEmpty()) {
                out.add(p.toLowerCase());
            }
        }
        return out;
    }

    /** The noun an action id starts with ({@code vm} for {@code vm.restart}), or the whole id. */
    public static String nounOf(Action a) {
        int dot = a.id().indexOf('.');
        return dot > 0 ? a.id().substring(0, dot) : a.id();
    }
}
