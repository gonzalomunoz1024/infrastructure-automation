package com.iaf.platform.workflows.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** The building blocks a workflow is made of. {@code key} is how the YAML spells it. */
public enum StepType {
    PROVIDER("provider", "call", "Providers"),
    HTTP("http", "call", "Code"),
    ANSIBLE("ansible", "call", "Ansible"),
    TERRAFORM("terraform", "call", "Terraform"),
    IDP("idp", "call", "IDP tiles"),
    ACTIVITY("activity", "call", "Code"),
    RUN("run", "run", "Flow"),
    APPROVAL("approval", "approval", "Human"),
    SWITCH("switch", "switch", "Flow"),
    FORK("fork", "fork", "Flow"),
    FOR("for", "for", "Flow"),
    TRY("try", "try", "Flow"),
    WAIT("wait", "wait", "Flow"),
    SET("set", "set", "Code"),
    RAISE("raise", "raise", "Flow"),
    USE("use", "use", "Resources"),
    REGISTER("register", "register", "Resources"),
    NOTIFY("notify", "notify", "Human"),
    SCRIPT("script", "script", "Code");

    private final String key;
    private final String yamlKey;
    private final String category;

    StepType(String key, String yamlKey, String category) {
        this.key = key;
        this.yamlKey = yamlKey;
        this.category = category;
    }

    @JsonValue
    public String key() {
        return key;
    }

    /** The YAML key: {@code call} for every integration step, otherwise the type itself. */
    public String yamlKey() {
        return yamlKey;
    }

    public String category() {
        return category;
    }

    public boolean isCall() {
        return "call".equals(yamlKey);
    }

    /** Steps that touch the world (become activities in Temporal). */
    public boolean isActivity() {
        return isCall() || this == NOTIFY || this == SCRIPT || this == REGISTER || this == USE;
    }

    /** Container steps whose children are other steps. */
    public boolean isContainer() {
        return this == FORK || this == FOR || this == TRY;
    }

    public static StepType of(String key) {
        for (StepType t : values()) {
            if (t.key.equals(key)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown step type '" + key + "'");
    }
}
