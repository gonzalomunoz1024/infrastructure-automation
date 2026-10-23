package com.iaf.console.bff.digests.adapters.outbound;

import org.springframework.stereotype.Component;

import com.iaf.console.bff.digests.domain.Digest;
import com.iaf.console.bff.digests.domain.DigestFacts;
import com.iaf.console.bff.digests.domain.DigestTemplate;
import com.iaf.console.bff.digests.domain.DigestText;
import com.iaf.console.bff.digests.ports.outbound.DigestWriter;

import reactor.core.publisher.Mono;

/** The rules template as a writer ({@code source: rules}): what writes the digests while no assistant is wired. */
@Component
public class RulesDigestWriter implements DigestWriter {

    @Override
    public String source() {
        return Digest.RULES;
    }

    @Override
    public Mono<DigestText> write(DigestFacts facts) {
        return Mono.fromSupplier(() -> DigestTemplate.write(facts));
    }
}
