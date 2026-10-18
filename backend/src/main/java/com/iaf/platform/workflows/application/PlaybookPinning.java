package com.iaf.platform.workflows.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.GitRemotePort;
import com.iaf.platform.common.ports.GitRemotePort.Resolved;
import com.iaf.platform.identity.application.CredentialAccess;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.workflows.domain.PlaybookPins;
import com.iaf.platform.workflows.domain.PlaybookPins.Outcome;
import com.iaf.platform.workflows.domain.PlaybookPins.Pin;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Resolves the git playbooks of a version that name a ref but no commit (one ls-remote per
 * repository, ref and credential) and writes the commits into a copy of the definition. A ref
 * that cannot be resolved is a failure of the playbooks check, with the reason. A pin's
 * {@code vault:} credential goes to the remote only when the publishing identity (the current
 * one) may use it, judged by {@link CredentialAccess}: the publish is refused with 403 otherwise.
 */
@Component
@RequiredArgsConstructor
public class PlaybookPinning {

    /** The definition to publish (with the commits written) and what was pinned or not. */
    public record Pinned(WorkflowDefinition definition, Outcome outcome) {
    }

    private record Result(Pin pin, String commit, String failure) {
    }

    private final GitRemotePort git;
    private final CredentialsPort credentials;

    public Mono<Pinned> pin(WorkflowDefinition def) {
        List<Pin> needed = PlaybookPins.needed(def);
        if (needed.isEmpty()) {
            return Mono.just(new Pinned(def, Outcome.NONE));
        }
        String[] refs = needed.stream().map(Pin::credential).filter(c -> c != null).distinct().toArray(String[]::new);
        Map<String, Mono<String>> asked = new HashMap<>();
        return CurrentIdentity.current()
                .flatMap(who -> CredentialAccess.require(credentials, who, refs))
                .thenMany(Flux.fromIterable(needed))
                .concatMap(p -> asked.computeIfAbsent(p.repo() + "\n" + p.ref() + "\n" + p.credential(),
                                k -> Mono.defer(() -> git.resolve(p.repo(), p.ref(), p.credential())).map(Resolved::commit).cache())
                        .map(commit -> new Result(p, commit, null))
                        .onErrorResume(e -> Mono.just(new Result(p, null, e.getMessage() == null ? e.toString() : e.getMessage()))))
                .collectList()
                .map(results -> {
                    Map<String, String> pinned = new LinkedHashMap<>();
                    List<String> failures = new ArrayList<>();
                    for (Result r : results) {
                        if (r.commit() != null) {
                            pinned.put(r.pin().step(), r.commit());
                        } else {
                            failures.add("step " + r.pin().step() + ": " + r.pin().ref() + " of " + r.pin().repo() + " did not resolve (" + r.failure() + ")");
                        }
                    }
                    return new Pinned(PlaybookPins.apply(def, pinned), new Outcome(pinned, failures));
                });
    }
}
