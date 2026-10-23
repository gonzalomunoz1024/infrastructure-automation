package com.iaf.console.search.index.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One suggestion of the contract: a resource, a type, a tag or a wfrn to put in the query or the path. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Suggestion(Kind kind, String value, String label, String type, String wfrn) {

    public enum Kind {
        RESOURCE("resource"), TYPE("type"), TAG("tag"), WFRN("wfrn");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        @com.fasterxml.jackson.annotation.JsonValue
        public String wire() {
            return wire;
        }
    }

    public static Suggestion resource(IndexedResource r) {
        return new Suggestion(Kind.RESOURCE, r.name() != null ? r.name() : r.id(), r.name() != null ? r.name() + " (" + r.type() + ")" : r.id(), r.type(),
                r.wfrn());
    }

    public static Suggestion wfrn(IndexedResource r) {
        return new Suggestion(Kind.WFRN, r.wfrn(), r.id() + (r.name() != null ? " · " + r.name() : ""), r.type(), r.wfrn());
    }

    public static Suggestion type(String type, long count) {
        return new Suggestion(Kind.TYPE, "type:" + type, type + " (" + count + ")", type, null);
    }

    public static Suggestion tag(String flat, long count) {
        return new Suggestion(Kind.TAG, "tag:" + flat, flat + " (" + count + ")", null, null);
    }
}
