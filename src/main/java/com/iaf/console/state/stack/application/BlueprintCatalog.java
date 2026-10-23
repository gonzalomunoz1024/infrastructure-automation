package com.iaf.console.state.stack.application;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.state.stack.domain.Blueprint;

/** The blueprints this service pinned from the hub ({@code blueprints/*.yaml}), by name; the same for every account. */
public final class BlueprintCatalog {

    private final Map<String, Blueprint> byName;

    public BlueprintCatalog(Collection<Blueprint> blueprints) {
        Map<String, Blueprint> map = new TreeMap<>();
        for (Blueprint bp : blueprints) {
            if (map.put(bp.name(), bp) != null) {
                throw new IllegalArgumentException("two blueprints are named " + bp.name());
            }
        }
        this.byName = Map.copyOf(map);
    }

    public Collection<Blueprint> all() {
        return new TreeMap<>(byName).values();
    }

    public Optional<Blueprint> find(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }

    /** The blueprint of a name; 404 when there is none. */
    public Blueprint require(String name) {
        return find(name).orElseThrow(() -> NotFoundException.of("blueprint", name));
    }
}
