package com.iaf.platform.providers.ports.inbound;

import java.util.List;

import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;

import reactor.core.publisher.Mono;

/** The credentials the platform knows, names and types only. */
public interface CredentialsQueries {

    Mono<List<CredentialInfo>> credentials();
}
