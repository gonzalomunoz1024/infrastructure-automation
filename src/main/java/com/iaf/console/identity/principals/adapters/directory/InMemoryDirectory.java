package com.iaf.console.identity.principals.adapters.directory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.identity.principals.domain.DirectoryGroup;
import com.iaf.console.identity.principals.ports.outbound.DirectoryPort;

import reactor.core.publisher.Mono;

/** The directory of every profile but {@code scim}: groups set in process, by the demo seed or a test. */
public final class InMemoryDirectory implements DirectoryPort {

    private final Map<String, DirectoryGroup> groups = new ConcurrentHashMap<>();

    @Override
    public Mono<DirectoryGroup> findGroup(String name) {
        return Mono.justOrEmpty(groups.get(name));
    }

    /** Makes the group of this name hold exactly these members (an empty list keeps an empty group). */
    public void put(String name, List<DirectoryGroup.Member> members) {
        groups.put(name, new DirectoryGroup(name, members));
    }

    /** Takes the group out of the directory, so it no longer exists. */
    public void remove(String name) {
        groups.remove(name);
    }
}
