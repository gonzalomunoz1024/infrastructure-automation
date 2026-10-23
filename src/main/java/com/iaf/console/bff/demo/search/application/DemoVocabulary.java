package com.iaf.console.bff.demo.search.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.iaf.console.bff.demo.search.domain.DemoInterpreter;
import com.iaf.console.bff.demo.state.application.DemoStateStore;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.bff.types.domain.Descriptors;
import com.iaf.console.platform.model.ResourceType;

import reactor.core.publisher.Mono;

/**
 * What the demo interpreter may name: the platform's types as the console describes them (name,
 * plural and title), read from the platform once; and an account's own tag names with their
 * values and its regions, read from the demo state store on every reading.
 */
public class DemoVocabulary {

    private final PlatformClients clients;
    private final DemoStateStore store;
    private volatile List<DemoInterpreter.TypeName> types;

    public DemoVocabulary(PlatformClients clients, DemoStateStore store) {
        this.clients = clients;
        this.store = store;
    }

    public Mono<DemoInterpreter.Vocabulary> of(String accountId) {
        return types().map(known -> {
            Map<String, Set<String>> tags = new TreeMap<>();
            Set<String> regions = new TreeSet<>();
            for (StateResource r : store.snapshot(accountId, null)) {
                if (r.region() != null) {
                    regions.add(r.region());
                }
                r.tags().forEach((name, value) -> tags.computeIfAbsent(name, n -> new TreeSet<>()).add(value));
            }
            return new DemoInterpreter.Vocabulary(known, tags, regions);
        });
    }

    private Mono<List<DemoInterpreter.TypeName>> types() {
        List<DemoInterpreter.TypeName> known = types;
        if (known != null) {
            return Mono.just(known);
        }
        return clients.internal().flatMap(p -> p.listResourceTypes(null)).map(c -> c.body() == null ? List.<ResourceType>of() : c.body()).map(all -> {
            List<DemoInterpreter.TypeName> names = new ArrayList<>();
            for (ResourceType rt : all) {
                ConsoleType t = Descriptors.describe(rt, List.of());
                names.add(new DemoInterpreter.TypeName(t.type(), t.service(), t.plural(), t.title()));
            }
            List<DemoInterpreter.TypeName> read = List.copyOf(names);
            types = read;
            return read;
        });
    }
}
