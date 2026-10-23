package com.iaf.console.identity.authz.domain;

import java.util.Map;
import java.util.TreeMap;

/** Every account's authorization data at one revision: what the bundle carries and the in-process evaluator reads. */
public record Compiled(Map<String, CompiledAccount> accounts, long revision) {

    public Compiled {
        accounts = Map.copyOf(new TreeMap<>(accounts == null ? Map.of() : accounts));
    }

    public static Compiled empty() {
        return new Compiled(Map.of(), 0);
    }
}
