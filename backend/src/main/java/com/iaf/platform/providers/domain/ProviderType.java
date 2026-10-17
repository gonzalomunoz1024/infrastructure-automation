package com.iaf.platform.providers.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** How the platform runs a provider's actions. */
public enum ProviderType {
    OPENAPI("openapi", "OpenAPI spec", "The spec URL, environments, credential, chosen operations", true),
    HTTP("http", "HTTP without a spec", "Method, path and one example response per endpoint", true),
    ANSIBLE("ansible", "Ansible repository", "Repo URL and which playbooks to expose", true),
    TERRAFORM("terraform", "Terraform module", "Module source and version", true),
    IDP("idp", "IDP tile", "Which tile (mock until the IDP tiles API is wired)", true),
    SDK("sdk", "Custom code", "A package using the Java, Go or Python SDK, run by the team's own worker", false);

    private final String key;
    private final String title;
    private final String juniorWrites;
    private final boolean noCode;

    ProviderType(String key, String title, String juniorWrites, boolean noCode) {
        this.key = key;
        this.title = title;
        this.juniorWrites = juniorWrites;
        this.noCode = noCode;
    }

    @JsonValue
    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public String juniorWrites() {
        return juniorWrites;
    }

    /** Config the platform runs itself, as opposed to a team-owned worker. */
    public boolean noCode() {
        return noCode;
    }

    public static ProviderType of(String key) {
        for (ProviderType t : values()) {
            if (t.key.equalsIgnoreCase(key)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown provider type '" + key + "'");
    }
}
