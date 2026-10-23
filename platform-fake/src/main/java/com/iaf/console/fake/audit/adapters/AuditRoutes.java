package com.iaf.console.fake.audit.adapters;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.audit.application.Audit;
import com.iaf.console.fake.audit.domain.EvidenceChain;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.Http;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.identity.application.Access;
import com.iaf.console.fake.identity.application.Callers;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Scopes;

import reactor.core.publisher.Mono;

/** The audit routes (P-12): rows, one record, its verification, its export and the evidence pack. */
public final class AuditRoutes {

    private final Audit audit;

    public AuditRoutes(Audit audit) {
        this.audit = audit;
    }

    public RouterFunction<ServerResponse> routes() {
        return route(GET("/api/v1/audit"), this::list)
                .andRoute(POST("/api/v1/audit:export-pack"), this::pack)
                .andRoute(GET("/api/v1/audit/{jobId:[^:]+}"), this::get)
                .andRoute(GET("/api/v1/audit/{jobId}:verify"), this::verify)
                .andRoute(GET("/api/v1/audit/{jobId}/export"), this::export);
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.JOBS_READ);
        ObjectNode query = Json.object();
        for (String name : new String[] {"workflow", "from", "to", "requester", "account", "wfrn"}) {
            Http.query(request, name).ifPresent(value -> query.put(name, value));
        }
        return Http.ok(audit.rows(Audit.Filter.of(query), caller));
    }

    private EvidenceChain chain(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.JOBS_READ);
        String jobId = request.pathVariable("jobId");
        return audit.visible(jobId, caller).orElseThrow(() -> ApiException.notFound("evidence record", jobId));
    }

    private Mono<ServerResponse> get(ServerRequest request) {
        return Http.ok(chain(request).document());
    }

    private Mono<ServerResponse> verify(ServerRequest request) {
        return Http.ok(chain(request).verify());
    }

    private Mono<ServerResponse> export(ServerRequest request) {
        EvidenceChain chain = chain(request);
        String format = Http.query(request, "format").orElse("json");
        if (!format.equals("json") && !format.equals("pdf")) {
            throw ApiException.badRequest("format must be json or pdf");
        }
        byte[] file = audit.export(chain, format);
        return ServerResponse.ok()
                .contentType(format.equals("pdf") ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + chain.jobId() + "." + format + "\"")
                .bodyValue(file);
    }

    private Mono<ServerResponse> pack(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireAdmin(caller);
        return request.bodyToMono(String.class).defaultIfEmpty("").flatMap(text -> {
            ObjectNode body = text.isBlank() ? Json.object() : (ObjectNode) Json.read(text);
            Audit.Filter filter = Audit.Filter.of(body.path("filter").isObject() ? body.path("filter") : body);
            byte[] zip = audit.pack(filter, caller);
            return ServerResponse.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(zip.length)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"evidence-pack.zip\"")
                    .bodyValue(zip);
        });
    }
}
