package com.iaf.platform.common.redaction;

import java.util.List;

import com.iaf.platform.common.ports.CredentialsPort;

import reactor.core.publisher.Mono;

/**
 * A {@link CredentialsPort} that tells {@link SecretValues} about every value it resolves or
 * stores, then answers exactly as the port it wraps. Put around every credentials adapter by
 * {@link CredentialsRecordingPostProcessor}, so no adapter has to know about redaction.
 */
public final class RecordingCredentials implements CredentialsPort {

    private final CredentialsPort delegate;
    private final SecretValues secrets;

    public RecordingCredentials(CredentialsPort delegate, SecretValues secrets) {
        this.delegate = delegate;
        this.secrets = secrets;
    }

    public CredentialsPort delegate() {
        return delegate;
    }

    @Override
    public Mono<String> resolve(String reference) {
        return delegate.resolve(reference).doOnNext(secrets::remember);
    }

    @Override
    public Mono<List<CredentialInfo>> list() {
        return delegate.list();
    }

    @Override
    public Mono<CredentialInfo> store(String name, String type, String value, String by) {
        secrets.remember(value);
        return delegate.store(name, type, value, by);
    }
}
