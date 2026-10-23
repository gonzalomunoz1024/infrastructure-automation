package com.iaf.console.fake;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.demo.DemoSeed;
import com.iaf.console.fake.identity.adapters.IdentityFilter;
import com.iaf.console.fake.identity.domain.DevUsers;
import com.iaf.console.fake.registry.domain.ResourceRecord;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.iaf.console.platform.client.PlatformClient;
import com.iaf.console.platform.contract.PlatformContractTest;
import com.iaf.console.wfrn.Wfrn;
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

    @Test
    void anOldWfrnAnswersWithTheCurrentOneInContentLocation() throws Exception {
        ResourceRecord vm = fake.registry().all().stream().filter(r -> "vm".equals(r.type()) && r.wfrn() != null && !fake.jobs().holds(r))
                .filter(r -> r.previousWfrns().isEmpty()).findFirst().orElseThrow();
        String from = vm.wfrn();
        Wfrn old = Wfrn.parse(from);
        String to = new Wfrn(old.partition(), old.service(), old.region(), "444444444444", old.type(), old.id()).format();
        fake.registry().rehome(vm.id(), from, to, "444444444444", DevUsers.byName("gonzalo"), r -> false);
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> byOld = http.send(byWfrn(from), HttpResponse.BodyHandlers.ofString());
        assertThat(byOld.statusCode()).isEqualTo(200);
        assertThat(byOld.headers().firstValue("Content-Location"))
                .contains("/api/v1/resources/by-wfrn?wfrn=" + URLEncoder.encode(to, StandardCharsets.UTF_8));
        assertThat(Json.read(byOld.body()).path("wfrn").asText()).isEqualTo(to);
        HttpResponse<String> byCurrent = http.send(byWfrn(to), HttpResponse.BodyHandlers.ofString());
        assertThat(byCurrent.statusCode()).isEqualTo(200);
        assertThat(byCurrent.headers().firstValue("Content-Location")).isEmpty();
    }

    private HttpRequest byWfrn(String wfrn) {
        return HttpRequest.newBuilder(URI.create(fake.baseUrl() + "/api/v1/resources/by-wfrn?wfrn=" + URLEncoder.encode(wfrn, StandardCharsets.UTF_8)))
                .header(IdentityFilter.DEV_USER, "gonzalo").GET().build();
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
