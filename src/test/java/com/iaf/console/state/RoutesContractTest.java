package com.iaf.console.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/** The routes the service maps are the pinned contract's paths, method for method; {@code /internal} stays out of the contract. */
class RoutesContractTest {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch");

    @Test
    void theServiceMapsExactlyTheContractsRoutes() throws Exception {
        Set<String> mapped = new TreeSet<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.iaf.console.state")) {
            for (Method method : Class.forName(candidate.getBeanClassName()).getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                for (String path : mapping.path()) {
                    for (RequestMethod verb : mapping.method()) {
                        if (!path.startsWith("/internal/")) {
                            mapped.add(verb.name() + " " + path);
                        }
                    }
                }
            }
        }
        Set<String> documented = new TreeSet<>();
        paths().forEach((path, item) -> ((Map<?, ?>) item).keySet().stream().map(String::valueOf).filter(METHODS::contains)
                .forEach(verb -> documented.add(verb.toUpperCase() + " " + path)));
        documented.remove("GET /actuator/health");
        assertThat(mapped).containsExactlyElementsOf(documented);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths() throws IOException {
        String resource = "contracts/openapi/console-state-service.yaml";
        try (InputStream in = RoutesContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            Map<String, Object> openapi = new Yaml().load(in);
            return (Map<String, Object>) openapi.get("paths");
        }
    }
}
