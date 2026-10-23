package com.iaf.console.bff;

import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.client.ResourceFilter;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.Resource;

/**
 * One demo application for every endpoint test: the demo profile on a fixed port, the embedded
 * platform-fake on a free port, the demo identity provider as the OIDC issuer. Spring caches the
 * context across the test classes that extend this.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=" + DemoApp.PORT,
        "console.oidc.issuer=" + DemoApp.BASE + "/demo-idp",
        "console.oidc.redirect-uri=" + DemoApp.BASE + "/auth/callback",
        "console.platform.fake.port=0",
        "console.platform.fake.step-delay=20ms",
        "console.session.secure=false",
        "console.live.heartbeat=2s",
        "logging.level.console.access=WARN"})
@ActiveProfiles("demo")
public abstract class DemoApp {

    public static final int PORT = 18181;
    public static final String BASE = "http://localhost:18181";
    public static final String COMPUTE = "123456789012";
    public static final String DATA = "210987654321";

    protected static final Console console = new Console(BASE);

    /** Starts a {@code vm-restart} for the compute account as gonzalo, straight at the platform; the job id. */
    protected static String startVmRestart(PlatformClients clients, Trust trust) {
        PlatformPort platform = clients.forAccount(AccountScope.of(COMPUTE, "global", "user_gonzalo")).block();
        Resource vm = platform.listResources(new ResourceFilter("vm", null, null, null, null, null, 5, null)).block().items().get(0);
        CatalogEntry restart = platform.listCatalog("vm").block().stream().filter(e -> "vm-restart".equals(e.workflow())).findFirst().orElseThrow();
        String assertion = trust.assertionSigner().sign(
                new OnBehalfOfAssertions.User("user_gonzalo", "Gonzalo Munoz", "gonzalo@example.com", List.of("account-admins")), COMPUTE, "global");
        return platform.start(restart, Map.of("vmId", vm.id()), Map.of("reason", "restart from the console tests", "environment", "dev"),
                RequestOptions.console(assertion, "req_test_" + System.nanoTime(), null)).block().body().jobId();
    }
}
