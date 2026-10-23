package com.iaf.console.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Every operation of the pinned contract has a handler, and every handler beyond it is one
 * {@code contracts/additions.md} names. The actuator serves its own paths.
 */
@SpringBootTest(classes = SearchIndexerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "console.search.engine=memory", "console.ingest.enabled=false", "console.auth.dev-headers=true", "console.search.budget.per-second=0.001",
        "console.search.budget.burst=2" })
class RoutesMatchContractTest {

    /** The handlers the pinned contract does not have yet, each described in contracts/additions.md; none since hub d9d1604. */
    private static final Set<String> ADDITIONS = Set.of();

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyContractOperationHasAHandlerAndNothingElseIsServed() throws IOException {
        JsonNode paths;
        try (InputStream in = getClass().getResourceAsStream("/contracts/openapi/console-search-indexer.yaml")) {
            paths = new ObjectMapper(new YAMLFactory()).readTree(in).path("paths");
        }
        List<String> missing = new ArrayList<>();
        List<String> contract = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            if (path.getKey().startsWith("/actuator")) {
                continue;
            }
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (!METHODS.contains(op.getKey())) {
                    continue;
                }
                String operation = op.getKey().toUpperCase() + " " + path.getKey();
                contract.add(operation);
                if (!served(path.getKey(), op.getKey())) {
                    missing.add(operation);
                }
            }
        }
        assertThat(contract).hasSizeGreaterThanOrEqualTo(5);
        assertThat(missing).as("contract operations without a handler").isEmpty();

        List<String> extra = new ArrayList<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            for (var pattern : info.getPatternsCondition().getPatterns()) {
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    String operation = method.name() + " " + pattern.getPatternString();
                    if (!contract.contains(operation) && !ADDITIONS.contains(operation)) {
                        extra.add(operation);
                    }
                }
            }
        }
        assertThat(extra).as("handlers neither in the contract nor in contracts/additions.md").isEmpty();
    }

    private boolean served(String contractPath, String method) {
        PathContainer path = PathContainer.parsePath(contractPath.replaceAll("\\{[^}]+}", "x1"));
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
