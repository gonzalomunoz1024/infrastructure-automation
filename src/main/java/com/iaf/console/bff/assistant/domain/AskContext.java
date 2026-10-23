package com.iaf.console.bff.assistant.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Where the person is when they ask, so "these" and "this one" mean something: the console route,
 * the service and type of the page, the resource open in it and the list's filter. {@code explain}
 * needs {@code wfrn}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AskContext(String path, String service, String type, String wfrn, SearchFilter filter) {

    public static final int MAX_PATH = 2048;
    private static final Pattern NAME = Pattern.compile("^[a-z0-9-]{1,64}$");

    /**
     * The context as the copilot takes it: a console route (under {@code /console/}, without
     * spaces or control characters), a service and a type that look like names, and the filter
     * reduced to what a list takes. What does not fit is left out rather than refused, as it only
     * helps; the wfrn is checked by the caller, which answers 404 for one of another account.
     */
    public AskContext sanitized() {
        String keptPath = ConsolePaths.isConsolePath(path) && path.length() <= MAX_PATH ? path : null;
        String keptService = service != null && NAME.matcher(service).matches() ? service : null;
        String keptType = type != null && NAME.matcher(type).matches() ? type : null;
        String keptWfrn = wfrn == null || wfrn.isBlank() ? null : wfrn.strip();
        List<String> ignored = new ArrayList<>();
        SearchFilter keptFilter = filter == null ? null : filter.reduced(ignored);
        return new AskContext(keptPath, keptService, keptType, keptWfrn, keptFilter);
    }
}
