package com.iaf.console.bff;

import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.client.ResourceFilter;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.Resource;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

/**
 * One demo application for every endpoint test: the demo profile on a fixed port, the embedded
 * platform-fake on a free port, the demo identity provider as the OIDC issuer. Spring caches the
 * context across the test classes that extend this.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "console.platform.fake.port=0",
        "console.platform.fake.step-delay=20ms",
        "console.session.secure=false",
        "console.live.heartbeat=2s",
        "console.insights.cache-ttl=0s",
        "console.digests.scheduled=false",
        "logging.level.console.access=WARN"})
@ActiveProfiles("demo")
public abstract class DemoApp {

    /** The demo's port: {@code CONSOLE_BFF_TEST_PORT} when set, so two builds on one machine keep apart, else 18181. */
    public static final int PORT = testPort();
    public static final String BASE = "http://localhost:" + PORT;
    public static final String COMPUTE = "123456789012";
    public static final String DATA = "210987654321";

    protected static final Console console = new Console(BASE);

    /** The client the service-token tests sign their own assertions as, and its key, made for this run. */
    public static final String TEST_CLIENT = "console-test-client";
    public static final ECKey TEST_CLIENT_KEY = ecKey("test-client-1");
    /** The pretend cluster whose service-account tokens prove the search indexer, and its key. */
    public static final String CLUSTER_ISSUER = "https://kubernetes.test";
    public static final RSAKey CLUSTER_KEY = rsaKey("cluster-1");

    @DynamicPropertySource
    static void demoPort(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("console.oidc.issuer", () -> BASE + "/demo-idp");
        registry.add("console.oidc.redirect-uri", () -> BASE + "/auth/callback");
    }

    @DynamicPropertySource
    static void serviceTokenClients(DynamicPropertyRegistry registry) {
        // dotted map keys: a bracketed one would not merge with application.yaml's entry of the same client
        registry.add("console.trust.clients." + TEST_CLIENT + ".audiences[0]", () -> "console-state-service");
        registry.add("console.trust.clients." + TEST_CLIENT + ".audiences[1]", () -> "console-identity-service");
        registry.add("console.trust.clients." + TEST_CLIENT + ".jwks", () -> TEST_CLIENT_KEY.toPublicJWK().toJSONString());
        registry.add("console.trust.clients.console-search-indexer.service-account", () -> "console:console-search-indexer");
        registry.add("console.trust.kubernetes.issuer", () -> CLUSTER_ISSUER);
        registry.add("console.trust.kubernetes.jwks", () -> new JWKSet(CLUSTER_KEY.toPublicJWK()).toString());
    }

    private static int testPort() {
        String port = System.getenv("CONSOLE_BFF_TEST_PORT");
        return port == null || port.isBlank() ? 18181 : Integer.parseInt(port.trim());
    }

    static ECKey ecKey(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static RSAKey rsaKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Starts a {@code vm-restart} for the compute account as gonzalo, straight at the platform; the job id. */
    protected static String startVmRestart(PlatformClients clients, Trust trust) {
        PlatformPort platform = clients.forAccount(AccountScope.of(COMPUTE, "global", "user_gonzalo")).block();
        Resource vm = platform.listResources(new ResourceFilter("vm", null, null, null, null, null, 50, null)).block().items().stream()
                .filter(r -> r.status() == null || !(r.status().equalsIgnoreCase("retired") || r.status().equalsIgnoreCase("deleted")))
                .findFirst().orElseThrow();
        CatalogEntry restart = platform.listCatalog("vm").block().stream().filter(e -> "vm-restart".equals(e.workflow())).findFirst().orElseThrow();
        String assertion = trust.assertionSigner().sign(
                new OnBehalfOfAssertions.User("user_gonzalo", "Gonzalo Munoz", "gonzalo@example.com", List.of("owners")), COMPUTE, "global");
        return platform.start(restart, Map.of("vmId", vm.id()), Map.of("reason", "restart from the console tests", "environment", "dev"),
                RequestOptions.console(assertion, "req_test_" + System.nanoTime(), null)).block().body().jobId();
    }
}
