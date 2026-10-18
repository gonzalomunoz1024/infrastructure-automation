package com.iaf.platform.config;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * What an instance does, from {@code platform.roles}: {@link #API} serves the REST API, the
 * WebSocket streams, MCP and the generated routes; {@link #WORKER} runs the interpreter's Temporal
 * worker. An instance has both by default. Splitting them needs the {@code temporal} profile (the
 * in-memory engine runs a job in the process that started it) and a shared store for the job
 * projection, since the worker that runs a job's steps is the one that records them.
 */
public enum Role {
    API, WORKER;

    public static final String PROPERTY = "platform.roles";

    /** The roles the environment gives this instance, as a list or comma-separated; both when unset. */
    public static Set<Role> of(Environment env) {
        Set<Role> roles = Binder.get(env).bind(PROPERTY, Bindable.setOf(Role.class)).orElseGet(() -> EnumSet.allOf(Role.class));
        return roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
    }
}
