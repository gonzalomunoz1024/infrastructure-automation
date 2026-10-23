package com.iaf.console.platformfake.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.fake.PlatformFake;

/** The application boots the fake with the demo world and answers the console's reads. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformFakeApplicationTest {

    @LocalServerPort
    int port;

    @Autowired
    PlatformFake fake;

    private WebClient client() {
        return WebClient.builder().baseUrl("http://localhost:" + port).defaultHeader("X-User", "gonzalo").build();
    }

    @Test
    void theDemoWorldIsSeeded() {
        assertThat(fake.demo()).isNotNull();
        assertThat(fake.registry().size()).isGreaterThan(10);
        assertThat(fake.jobs().all()).isNotEmpty();
    }

    @Test
    void theReadsAnswerWithTheDevHeader() {
        for (String path : new String[] {"/api/v1/regions", "/api/v1/resource-types", "/api/v1/catalog", "/api/v1/resources", "/api/v1/jobs",
                "/api/v1/events", "/api/v1/openapi.json", "/actuator/health"}) {
            ClientResponse response = client().get().uri(path).exchangeToMono(r -> r.releaseBody().thenReturn(r)).block();
            assertThat(response).isNotNull();
            assertThat(response.statusCode()).as(path).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void theDefaultUserAnswersWithoutAHeaderAndLocalhostOriginsAreAllowed() {
        String anonymous = WebClient.create("http://localhost:" + port).get().uri("/api/v1/me").retrieve().bodyToMono(String.class).block();
        assertThat(anonymous).contains("\"id\":\"gonzalo\"");
        ClientResponse badToken = WebClient.create("http://localhost:" + port).get().uri("/api/v1/regions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer iaf_not_a_token")
                .exchangeToMono(r -> r.releaseBody().thenReturn(r)).block();
        assertThat(badToken).isNotNull();
        assertThat(badToken.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ClientResponse preflight = WebClient.create("http://localhost:" + port).options().uri("/api/v1/jobs")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-user, idempotency-key")
                .accept(MediaType.ALL)
                .exchangeToMono(r -> r.releaseBody().thenReturn(r)).block();
        assertThat(preflight).isNotNull();
        assertThat(preflight.statusCode().is2xxSuccessful()).isTrue();
        assertThat(preflight.headers().header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly("http://localhost:5173");
    }
}
