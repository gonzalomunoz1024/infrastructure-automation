package com.iaf.console.bff.common.errors;

import java.util.Map;

/**
 * A 409 the console names with a code of its own, so the web app can tell it apart without
 * reading the sentence: {@code { error: <code>, message, problems: [{ code, message }], detail }}.
 */
public class CodedConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Map<String, Object> detail;

    public CodedConflictException(String code, String message, Map<String, Object> detail) {
        super(message);
        this.code = code;
        this.detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> detail() {
        return detail;
    }
}
