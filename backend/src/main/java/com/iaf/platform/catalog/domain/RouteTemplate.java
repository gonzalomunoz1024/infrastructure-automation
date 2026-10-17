package com.iaf.platform.catalog.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One generated route: a method and a path template compiled into a regex. {@code {vmId}} becomes
 * a capture of one segment (no {@code /} and no {@code :}), so {@code /api/v1/vms/{vmId}} never
 * swallows {@code /api/v1/vms/vm-1:batch}; everything else, including the {@code :restart},
 * {@code :batch} and {@code :preview} suffixes, is literal.
 */
public record RouteTemplate(String method, String template, Pattern pattern, List<String> params) {

    private static final Pattern PARAM = Pattern.compile("\\{([^/{}]+)}");

    public static RouteTemplate compile(String method, String template) {
        if (template == null || !template.startsWith("/")) {
            throw new IllegalArgumentException("A route path starts with /: " + template);
        }
        List<String> params = new ArrayList<>();
        StringBuilder regex = new StringBuilder("^");
        Matcher m = PARAM.matcher(template);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(template.substring(last, m.start())));
            String name = m.group(1).trim();
            if (params.contains(name)) {
                throw new IllegalArgumentException("Path parameter {" + name + "} appears twice in " + template);
            }
            params.add(name);
            regex.append("(?<p").append(params.size() - 1).append(">[^/:]+)");
            last = m.end();
        }
        regex.append(Pattern.quote(template.substring(last))).append("/?$");
        return new RouteTemplate(method == null ? "POST" : method.toUpperCase(), template, Pattern.compile(regex.toString()), List.copyOf(params));
    }

    /** The path parameters when the method and the path match, otherwise empty. */
    public Optional<Map<String, String>> match(String method, String path) {
        if (method == null || !this.method.equalsIgnoreCase(method)) {
            return Optional.empty();
        }
        return matchPath(path);
    }

    public Optional<Map<String, String>> matchPath(String path) {
        if (path == null) {
            return Optional.empty();
        }
        Matcher m = pattern.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < params.size(); i++) {
            values.put(params.get(i), m.group("p" + i));
        }
        return Optional.of(values);
    }

    /** The same template with a literal suffix, e.g. {@code :batch}. */
    public RouteTemplate withSuffix(String method, String suffix) {
        return compile(method, template + suffix);
    }

    /** The path with {@code {x}} replaced, for links. */
    public String expand(Map<String, String> values) {
        String out = template;
        for (var e : values.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    @Override
    public String toString() {
        return method + " " + template;
    }
}
