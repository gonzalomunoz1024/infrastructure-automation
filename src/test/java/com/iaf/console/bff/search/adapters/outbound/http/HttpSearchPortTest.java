package com.iaf.console.bff.search.adapters.outbound.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.search.application.SearchProperties;
import com.iaf.console.bff.search.domain.CountsQuery;
import com.iaf.console.bff.search.domain.IndexStatus;
import com.iaf.console.bff.search.domain.SearchPage;
import com.iaf.console.bff.search.domain.SearchQuery;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.application.TrustProperties;
import com.iaf.console.bff.trust.ports.outbound.SigningKeyPort;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;

/** The indexer's requests as the adapter writes them, against a recording exchange instead of a server. */
class HttpSearchPortTest {

    private static final String COMPUTE = "123456789012";
    private static final String DATA = "210987654321";
    private static final AccountScope GONZALO = AccountScope.of(COMPUTE, "global", "user_gonzalo");
    private static final StateCaller AS_GONZALO = new StateCaller("user_gonzalo", List.of("owners"), List.of("account-admin"));

    private final List<ClientRequest> requests = new ArrayList<>();
    private HttpStatus status = HttpStatus.OK;
    private String answer = "{\"total\":3,\"byState\":{\"active\":3},\"drift\":0,\"cappedAt\":null,\"groups\":[]}";

    private final HttpSearchPort port = new HttpSearchPort(WebClient.builder().exchangeFunction(request -> {
        requests.add(request);
        return Mono.just(ClientResponse.create(status).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(answer).build());
    }), () -> "http://indexer.test", trust(), new SearchProperties("http://indexer.test", "console-search-indexer", Duration.ofSeconds(5)),
            new ObjectMapper().findAndRegisterModules());

    @Test
    void countsAcrossAccountsAreAskedAsTheConsoleInternalPrincipal() throws Exception {
        JsonNode counts = port.countAcross(GONZALO, List.of(COMPUTE, DATA),
                new CountsQuery("created:<2026-09-23T00:00Z name&more", "vm", null, "deleted", Map.of("env", "prod & dev"), true, "tag:env")).block();
        assertEquals(3, counts.path("total").asInt(), "the indexer's answer as it is");

        ClientRequest request = requests.get(0);
        URI url = request.url();
        assertEquals("/v1/counts", url.getPath());
        Map<String, List<String>> params = UriComponentsBuilder.fromUri(url).build(true).getQueryParams();
        assertEquals(List.of(COMPUTE, DATA), params.get("accountId"), "every account named: " + url);
        assertEquals("created:<2026-09-23T00:00Z name&more", decoded(params.get("q").get(0)), "the query encoded strictly: " + url);
        assertEquals("prod & dev", decoded(params.get("tag.env").get(0)));
        assertEquals("tag:env", decoded(params.get("groupBy").get(0)));
        assertEquals(List.of("vm"), params.get("type"));
        assertEquals(List.of("deleted"), params.get("state"));
        assertEquals(List.of("true"), params.get("drift"));
        assertNull(params.get("region"), "every region when none is named");

        JWTClaimsSet claims = SignedJWT.parse(request.headers().getFirst(HttpHeaders.AUTHORIZATION).substring("Bearer ".length())).getJWTClaimsSet();
        assertNull(claims.getClaim("acct"), "the console-internal principal's token names no account");
        assertEquals("console-bff", claims.getSubject());
        assertTrue(claims.getAudience().contains("console-search-indexer"), claims.getAudience().toString());
    }

    @Test
    void aCountsCallNamesOneToTwoHundredAccountsAndTheIndexersRefusalPassesOn() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            many.add(String.format("%012d", i));
        }
        CountsQuery all = new CountsQuery(null, null, null, null, Map.of(), null, null);
        assertThrows(IllegalArgumentException.class, () -> port.countAcross(GONZALO, many, all).block());
        assertThrows(IllegalArgumentException.class, () -> port.countAcross(GONZALO, List.of(), all).block());
        assertTrue(requests.isEmpty());

        status = HttpStatus.BAD_REQUEST;
        answer = "{\"error\":\"the query cannot be read\",\"problems\":[{\"code\":\"query.created\",\"field\":\"/q\",\"message\":\"a time alone needs a comparison\"}]}";
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> port.countAcross(GONZALO, List.of(COMPUTE), all).block());
        assertEquals("query.created", refused.problems().get(0).code());
    }

    @Test
    void theIndexStatusIsAskedAsThePersonInTheirAccount() throws Exception {
        answer = "{\"documents\":12,\"lagSeconds\":2.5,\"lastEventAt\":\"2026-10-23T11:59:00Z\",\"rebuilding\":false,\"sources\":[]}";
        IndexStatus read = port.getIndexStatus(GONZALO, AS_GONZALO).block();
        assertEquals(Instant.parse("2026-10-23T11:59:00Z"), read.lastEventAt());
        assertEquals(12, read.documents());
        ClientRequest request = requests.get(0);
        assertEquals("/v1/index/status", request.url().getPath());
        assertNull(request.url().getQuery(), "the person's token names the account: " + request.url());
        JWTClaimsSet claims = SignedJWT.parse(request.headers().getFirst(HttpHeaders.AUTHORIZATION).substring("Bearer ".length())).getJWTClaimsSet();
        assertEquals(COMPUTE, claims.getStringClaim("acct"));
        assertEquals("user_gonzalo", claims.getSubject());

        answer = "{\"documents\":0,\"lagSeconds\":0,\"lastEventAt\":null}";
        assertNull(port.getIndexStatus(GONZALO, AS_GONZALO).block().lastEventAt(), "null before the first event");
    }

    @Test
    void aSearchPageKeepsTheIndexersDidYouMean() {
        answer = "{\"items\":[{\"wfrn\":\"wfrn:wf:data:global:123456789012:database/orders-postgres\",\"type\":\"database\",\"service\":\"data\","
                + "\"region\":\"global\",\"name\":\"orders-postgres\",\"state\":\"active\",\"score\":0.5,\"updatedAt\":\"2026-10-23T11:00:00Z\"}],"
                + "\"next\":null,\"didYouMean\":\"postgres\"}";
        SearchPage page = port.search(GONZALO, AS_GONZALO, new SearchQuery("postgress", null, null, Map.of(), null, null, 20, null)).block();
        assertEquals("postgres", page.didYouMean());
        assertEquals("orders-postgres", page.items().get(0).name());
        assertNull(page.next());

        answer = "{\"items\":[],\"next\":\"abc\"}";
        SearchPage plain = port.search(GONZALO, AS_GONZALO, new SearchQuery("web", null, null, Map.of(), null, null, 20, null)).block();
        assertNull(plain.didYouMean());
        assertEquals("abc", plain.next());
    }

    private static String decoded(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Trust trust() {
        ECKey key = OnBehalfOfAssertions.generateKey("console-bff-test");
        SigningKeyPort keys = new SigningKeyPort() {
            @Override
            public ECKey currentKey() {
                return key;
            }

            @Override
            public ECKey previousKey() {
                return null;
            }
        };
        TrustProperties properties = new TrustProperties("console-bff", "platform", Duration.ofSeconds(60), Duration.ofSeconds(30), null, null,
                Duration.ofMinutes(5), Duration.ofMinutes(5), null, Map.of());
        return new Trust(keys, properties, Clock.systemUTC());
    }
}
