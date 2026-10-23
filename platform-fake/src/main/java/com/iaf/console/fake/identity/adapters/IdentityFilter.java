package com.iaf.console.fake.identity.adapters;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.identity.application.Callers;
import com.iaf.console.fake.identity.application.OnBehalfOfVerifier;
import com.iaf.console.fake.identity.application.ServiceAccounts;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Delegation;
import com.iaf.console.fake.identity.domain.DevUsers;
import com.iaf.console.fake.identity.domain.RequestContext;
import com.iaf.console.fake.identity.domain.Scopes;
import com.iaf.console.wfrn.Wfrn;
import com.iaf.console.wfrn.WfrnException;

import reactor.core.publisher.Mono;

/**
 * Resolves the caller of every request: a bearer token names a service account, the dev header
 * X-User a person (the default user when neither is sent), and X-On-Behalf-Of a delegation under
 * P-02's rules. The P-04 context headers are read here and refused without a delegation.
 */
public final class IdentityFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    public static final String ON_BEHALF_OF = "X-On-Behalf-Of";
    public static final String RESOURCE_WFRN = "X-Resource-Wfrn";
    public static final String CONSOLE_REQUEST_ID = "X-Console-Request-Id";
    public static final String JOB_LABEL_PREFIX = "X-Job-Label-";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String TRACEPARENT = "traceparent";
    public static final String DEV_USER = "X-User";

    private final ServiceAccounts serviceAccounts;
    private final OnBehalfOfVerifier verifier;
    private final String defaultUser;

    public IdentityFilter(ServiceAccounts serviceAccounts, OnBehalfOfVerifier verifier, String defaultUser) {
        this.serviceAccounts = serviceAccounts;
        this.verifier = verifier;
        this.defaultUser = defaultUser;
    }

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        return Mono.fromCallable(() -> {
            resolve(request);
            return request;
        }).flatMap(next::handle);
    }

    /** Resolves the caller and the context and attaches them to the request. */
    public void resolve(ServerRequest request) {
        HttpHeaders headers = request.headers().asHttpHeaders();
        Caller caller = authenticate(headers);
        String assertion = headers.getFirst(ON_BEHALF_OF);
        if (assertion != null && !assertion.isBlank()) {
            if (!caller.isService() || !caller.hasScope(Scopes.CONSOLE_ACT) || caller.consoleAccount() == null) {
                throw ApiException.badRequest("on-behalf-of not allowed for this caller");
            }
            Delegation delegation = verifier.verify(assertion.trim());
            if (!caller.consoleAccount().equals(delegation.accountId())) {
                throw ApiException.badRequest("on-behalf-of not allowed for this caller");
            }
            caller = caller.withDelegation(delegation);
        }
        RequestContext context = context(headers, assertion);
        if (context.hasContext() && caller.delegation() == null) {
            throw ApiException.badRequest("context headers need a delegation");
        }
        if (context.wfrn() != null) {
            Wfrn wfrn;
            try {
                wfrn = Wfrn.parse(context.wfrn());
            } catch (WfrnException e) {
                throw ApiException.badRequest("wfrn.invalid: " + e.getMessage());
            }
            Delegation delegation = caller.delegation();
            if (!wfrn.account().equals(delegation.accountId()) || (delegation.region() != null && !wfrn.region().equals(delegation.region()))) {
                throw ApiException.badRequest("wfrn.contextMismatch");
            }
        }
        Callers.attach(request, caller, context);
    }

    private Caller authenticate(HttpHeaders headers) {
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            String[] parts = authorization.trim().split("\\s+", 2);
            if (parts.length != 2 || !parts[0].equalsIgnoreCase("Bearer")) {
                throw ApiException.unauthorized("unsupported authorization scheme");
            }
            return serviceAccounts.byToken(parts[1].trim()).orElseThrow(() -> ApiException.unauthorized("unknown token"));
        }
        String user = headers.getFirst(DEV_USER);
        if (user == null || user.isBlank()) {
            if (defaultUser == null || defaultUser.isBlank()) {
                throw ApiException.unauthorized("authentication required");
            }
            user = defaultUser;
        }
        return DevUsers.byName(user.trim());
    }

    private static RequestContext context(HttpHeaders headers, String assertion) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<String, java.util.List<String>> header : headers.entrySet()) {
            String name = header.getKey();
            if (name.regionMatches(true, 0, JOB_LABEL_PREFIX, 0, JOB_LABEL_PREFIX.length()) && !header.getValue().isEmpty()) {
                String label = name.substring(JOB_LABEL_PREFIX.length()).toLowerCase(Locale.ROOT);
                if (!label.matches("[a-z0-9-]{1,32}")) {
                    throw ApiException.badRequest("label name '" + label + "' must match [a-z0-9-]{1,32}");
                }
                String value = header.getValue().get(0);
                if (value.length() > 128) {
                    throw ApiException.badRequest("label '" + label + "' is longer than 128 characters");
                }
                labels.put(label, value);
            }
        }
        if (labels.size() > 16) {
            throw ApiException.badRequest("at most 16 labels");
        }
        String idempotencyKey = blankToNull(headers.getFirst(IDEMPOTENCY_KEY));
        if (idempotencyKey != null && idempotencyKey.length() > 255) {
            throw ApiException.badRequest("Idempotency-Key is longer than 255 characters");
        }
        return new RequestContext(blankToNull(headers.getFirst(RESOURCE_WFRN)), blankToNull(headers.getFirst(CONSOLE_REQUEST_ID)), labels,
                blankToNull(headers.getFirst(TRACEPARENT)), idempotencyKey, blankToNull(assertion));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
