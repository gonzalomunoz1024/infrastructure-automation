package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Every operation of the pinned contract is served, except the ones named here as later work
 * packages: the resource pages go through the state service (WP 3.2, 3.3) and search through the
 * indexer (WP 4.1).
 */
class RoutesMatchContractTest extends DemoApp {

    private static final Set<String> LATER = Set.of(
            "/console/v1/r/{type}", "/console/v1/r/{type}:preview", "/console/v1/r/{type}:batch", "/console/v1/r/{type}/{wfrn}",
            "/console/v1/r/{type}/{wfrn}:{verb}", "/console/v1/r/{type}/{wfrn}:{verb}:preview", "/console/v1/r/{type}/{wfrn}/activity",
            "/console/v1/r/{type}/{wfrn}/drift", "/console/v1/r/{type}/{wfrn}/lineage", "/console/v1/r/{type}/{wfrn}/tags",
            "/console/v1/search", "/console/v1/suggest");

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyContractOperationHasAHandler() throws IOException {
        JsonNode paths;
        try (InputStream in = getClass().getResourceAsStream("/contracts/console-bff.yaml")) {
            paths = new ObjectMapper(new YAMLFactory()).readTree(in).path("paths");
        }
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            if (LATER.contains(path.getKey()) || path.getKey().startsWith("/actuator")) {
                continue;
            }
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (!METHODS.contains(op.getKey())) {
                    continue;
                }
                checked++;
                if (!served(path.getKey(), op.getKey())) {
                    missing.add(op.getKey().toUpperCase() + " " + path.getKey());
                }
            }
        }
        assertTrue(checked >= 15, "the contract has operations: " + checked);
        assertTrue(missing.isEmpty(), "not served: " + missing);
        assertTrue(console.get("/actuator/health", null).status() == 200);
    }

    private boolean served(String contractPath, String method) {
        String sample = contractPath.replaceAll("\\{[^}]+}", "x1");
        PathContainer path = PathContainer.parsePath(sample);
        RequestMethod wanted = RequestMethod.valueOf(method.toUpperCase());
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            boolean pathMatches = info.getPatternsCondition().getPatterns().stream().anyMatch(p -> p.matches(path));
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (pathMatches && (methods.isEmpty() || methods.contains(wanted))) {
                return true;
            }
        }
        return false;
    }
}
