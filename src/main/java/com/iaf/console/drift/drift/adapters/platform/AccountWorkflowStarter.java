package com.iaf.console.drift.drift.adapters.platform;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.drift.drift.ports.outbound.PlatformCredentialsPort;
import com.iaf.console.drift.drift.ports.outbound.WorkflowStarterPort;
import com.iaf.console.platform.client.Accepted;
import com.iaf.console.platform.client.PlatformClient;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.JobAccepted;

import reactor.core.publisher.Mono;

/**
 * Starts a workflow as the account: the console-internal principal this service reads with may
 * not start jobs (preset {@code console-internal}, P-03), so a revert runs through the account's
 * own platform service account, whose token the credentials port answers. One client per token,
 * kept while the token stays the same.
 */
public final class AccountWorkflowStarter implements WorkflowStarterPort {

    private record Client(String token, PlatformPort client) {
    }

    private final PlatformCredentialsPort credentials;
    private final String baseUrl;
    private final Duration timeout;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    public AccountWorkflowStarter(PlatformCredentialsPort credentials, String baseUrl, Duration timeout) {
        this.credentials = credentials;
        this.baseUrl = baseUrl;
        this.timeout = timeout;
    }

    @Override
    public Mono<JobAccepted> start(String accountId, CatalogEntry entry, Map<String, String> pathParams, Map<String, Object> input, RequestOptions options) {
        return credentials.tokenFor(accountId)
                .switchIfEmpty(Mono.error(() -> new NoCredential(accountId)))
                .map(token -> clientFor(accountId, token))
                .flatMap(client -> client.start(entry, pathParams, input, options))
                .map(Accepted::body);
    }

    private PlatformPort clientFor(String accountId, String token) {
        Client current = clients.get(accountId);
        if (current != null && current.token().equals(token)) {
            return current.client();
        }
        PlatformPort client = PlatformClient.builder().baseUrl(baseUrl).bearerToken(token).responseTimeout(timeout)
                .userAgent("console-drift-service/account-" + accountId).build();
        clients.put(accountId, new Client(token, client));
        return client;
    }
}
