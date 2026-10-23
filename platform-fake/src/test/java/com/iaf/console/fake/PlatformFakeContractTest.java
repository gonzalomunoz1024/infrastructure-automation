package com.iaf.console.fake;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.demo.DemoSeed;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.iaf.console.platform.client.PlatformClient;
import com.iaf.console.platform.contract.PlatformContractTest;
import com.nimbusds.jose.jwk.ECKey;

/** The client's contract test against the fake, started in this process on a free port with a key generated for the run. */
class PlatformFakeContractTest extends PlatformContractTest {

    private PlatformFake fake;

    @Override
    protected Platform connect() {
        fake = PlatformFake.builder().stepDelay(Duration.ofMillis(20)).generateDemoKey(false).build();
        ECKey key = OnBehalfOfAssertions.generateKey("contract-test");
        fake.verifier().trust(key.toPublicJWK());
        fake.start(0);
        DemoSeed.Result demo = fake.demo();
        String base = fake.baseUrl();
        PlatformClient admin = PlatformClient.builder().baseUrl(base).devUser("gonzalo").build();
        PlatformClient console = PlatformClient.builder().baseUrl(base).bearerToken(demo.compute().token()).build();
        PlatformClient other = PlatformClient.builder().baseUrl(base).bearerToken(demo.data().token()).build();
        PlatformClient internal = PlatformClient.builder().baseUrl(base).bearerToken(demo.internalToken()).build();
        OnBehalfOfAssertions.Signer signer = new OnBehalfOfAssertions.Signer(key, fake.verifier().issuer(), fake.verifier().audience());
        OnBehalfOfAssertions.User dana = new OnBehalfOfAssertions.User("dana", "Dana Reyes", "dana@example.com", List.of("team-compute", "developer"));
        return new Platform(base, admin, console, DemoSeed.ACCOUNT_COMPUTE, other, DemoSeed.ACCOUNT_DATA, internal, signer, dana,
                Duration.ofSeconds(30));
    }

    @Override
    protected void disconnect(Platform connected) {
        fake.close();
    }

    @Override
    protected boolean canPlanFailures() {
        return true;
    }

    @Override
    protected void planFailure(String provider, String action, String providerId, String errorClass, boolean retryable) {
        ObjectNode body = Json.object().put("provider", provider).put("action", action);
        if (providerId != null) {
            body.putObject("match").put("id", providerId);
        }
        body.putObject("fail").put("class", errorClass).put("message", "planned by the contract test").put("retryable", retryable).put("times", 1);
        fake.world().mutate(body);
    }
}
