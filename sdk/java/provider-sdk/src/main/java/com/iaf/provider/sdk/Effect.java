package com.iaf.provider.sdk;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What an action does to the world. The platform derives previews, approvals and retry safety from it. */
public enum Effect {
    READ, CREATE, CHANGE, DELETE;

    /**
     * The old name of {@link #CHANGE}: it writes {@code change}. It goes away in the next version.
     *
     * @deprecated use {@link #CHANGE}
     */
    @Deprecated
    public static final Effect UPDATE = CHANGE;

    /** The spelling in the provider file: {@code read}, {@code create}, {@code change}, {@code delete}. */
    @JsonValue
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The effect a provider file spells. {@code update}, the spelling of earlier versions of the
     * SDKs, still reads as {@link #CHANGE} in this version.
     */
    @JsonCreator
    public static Effect of(String key) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (k.equals("update")) {
            return CHANGE;
        }
        for (Effect e : values()) {
            if (e.key().equals(k)) {
                return e;
            }
        }
        throw new IllegalArgumentException("An effect is read, create, change or delete; was " + key);
    }
}
