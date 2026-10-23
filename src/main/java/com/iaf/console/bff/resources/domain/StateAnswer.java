package com.iaf.console.bff.resources.domain;

/** A conditional read of one resource: the record and its ETag, or only the ETag when {@code If-None-Match} held. */
public record StateAnswer(StateResource resource, String etag, boolean notModified) {

    public static StateAnswer unchanged(String etag) {
        return new StateAnswer(null, etag, true);
    }
}
