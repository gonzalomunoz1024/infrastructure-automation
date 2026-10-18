package com.iaf.provider.sdk;

import java.util.Locale;

/** What an action does to the world. The platform derives previews, approvals and retry safety from it. */
public enum Effect {
    READ, CREATE, UPDATE, DELETE;

    /** The spelling in the provider file: {@code read}, {@code create}, {@code update}, {@code delete}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
