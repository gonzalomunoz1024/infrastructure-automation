package com.iaf.console.bff.digests.domain;

import java.util.Map;

/** What a writer makes of a digest's facts: the headline and each section's text, by section kind. */
public record DigestText(String headline, Map<String, String> texts) {

    public DigestText {
        texts = texts == null ? Map.of() : Map.copyOf(texts);
    }
}
