package com.iaf.console.bff.digests.ports.outbound;

import com.iaf.console.bff.digests.domain.DigestFacts;
import com.iaf.console.bff.digests.domain.DigestText;

import reactor.core.publisher.Mono;

/**
 * What writes a digest's headline and section texts from its facts (decision 32.4): the copilot
 * ({@code assistant}) when it is wired, else the rules template ({@code rules}). A writer that
 * fails, answers nothing or leaves a section out is made up for by the template.
 */
public interface DigestWriter {

    /** What the digest names as its {@code source}: {@code assistant} or {@code rules}. */
    String source();

    Mono<DigestText> write(DigestFacts facts);
}
