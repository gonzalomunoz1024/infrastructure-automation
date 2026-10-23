package com.iaf.console.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Every operation of the pinned contract is served, and the operations served beyond it are
 * exactly the ones the README lists.
 */
class ContractCoverageTest extends WithService {

    private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete");

    /** Beyond the contract: access requests and the quota request reads and denials, as the README lists them. */
    static final Set<String> EXTRAS = new TreeSet<>(List.of(
            "DELETE /v1/accounts/{accountId}/quota-requests/{requestId}",
            "GET /v1/accounts/{accountId}/access-requests",
            "GET /v1/accounts/{accountId}/access-requests/{requestId}",
            "GET /v1/accounts/{accountId}/quota-requests/{requestId}",
            "POST /v1/accounts/{accountId}/access-requests",
            "POST /v1/accounts/{accountId}/access-requests/{requestId}:decide"));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyContractOperationIsServedAndTheExtrasAreListed() throws IOException {
        Set<String> wanted = new TreeSet<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("contracts/console-identity-service.yaml")) {
            JsonNode contract = new ObjectMapper(new YAMLFactory()).readTree(in);
            Iterator<Map.Entry<String, JsonNode>> paths = contract.get("paths").properties().iterator();
            while (paths.hasNext()) {
                Map.Entry<String, JsonNode> path = paths.next();
                for (String method : METHODS) {
                    if (path.getValue().has(method)) {
                        wanted.add(method.toUpperCase(java.util.Locale.ROOT) + " " + path.getKey());
                    }
                }
            }
        }
        assertThat(wanted).hasSizeGreaterThan(30);

        Set<String> served = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (PathPattern pattern : info.getPatternsCondition().getPatterns()) {
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    served.add(method.name() + " " + pattern.getPatternString());
                }
            }
        }
        web.get().uri("/actuator/health").exchange().expectStatus().isOk();
        served.add("GET /actuator/health");

        Set<String> missing = new TreeSet<>(wanted);
        missing.removeAll(served);
        assertThat(missing).as("contract operations nothing serves").isEmpty();
        Set<String> extras = new TreeSet<>(served);
        extras.removeAll(wanted);
        assertThat(extras).as("operations beyond the contract; list a new one in the README and here").isEqualTo(EXTRAS);
    }
}
