package com.iaf.console.bff.resourcegroups.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.iaf.console.kit.errors.Problem;

/** A create's body: the name, an optional description and the query. */
public record NewGroup(String name, String description, GroupQuery query) {

    public NewGroup {
        query = query == null ? new GroupQuery(null, null) : query;
    }

    /** The body of a create that sent none. */
    public static NewGroup empty() {
        return new NewGroup(null, null, null);
    }

    /** The body's problems, each on its JSON pointer; {@code known} are the type names of the region's catalogue. */
    public List<Problem> problems(Set<String> known) {
        List<Problem> problems = new ArrayList<>();
        ResourceGroup.checkName(name, problems);
        ResourceGroup.checkDescription(description, problems);
        query.check(known, problems);
        return problems;
    }

    /**
     * A digest of what a valid body asks for (each type once), so that a create repeated with the
     * same Idempotency-Key can tell the same request from another one.
     */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder();
        field(canonical, "n", name);
        field(canonical, "d", description == null ? "" : description);
        for (String type : query.distinct().types()) {
            field(canonical, "t", type);
        }
        for (Map.Entry<String, String> tag : query.tags().entrySet()) {
            field(canonical, "k", tag.getKey());
            field(canonical, "v", tag.getValue());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** One field, marked and length-prefixed, so no two bodies spell the same text. */
    private static void field(StringBuilder canonical, String mark, String value) {
        String text = value == null ? "" : value;
        canonical.append(mark).append(text.length()).append(':').append(text);
    }
}
