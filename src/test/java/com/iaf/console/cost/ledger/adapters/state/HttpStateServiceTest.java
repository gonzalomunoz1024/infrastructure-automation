package com.iaf.console.cost.ledger.adapters.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.cost.ledger.domain.ResourceSnapshot;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * What the state service's answers become, from canned responses: the 410 of a resource moved to
 * another account (hub decision 30), a 410 that names nothing, the resource a move opened, and a
 * moved resource's tombstone in the deleted list.
 */
class HttpStateServiceTest {

    static final String OLD = "wfrn:wf:compute:global:123456789012:vm/web-01";
    static final String NEW = "wfrn:wf:compute:global:210987654321:vm/vm-01jcd5p8w2k4m6n8q0r2t4v6x8";
    static final Instant MOVED = Instant.parse("2026-10-12T15:30:00Z");
    static final AccountScope FROM = AccountScope.of("123456789012", "global", "console-cost-service");
    static final AccountScope TO = AccountScope.of("210987654321", "global", "console-cost-service");

    @Test
    void a410ResourceTransferredIsTheTombstoneThatNamesTheWfrnItBecame() {
        ResourceSnapshot gone = answering(HttpStatus.GONE, "{\"error\":\"resource.transferred\",\"wfrn\":\"" + OLD
                + "\",\"deletedAt\":\"2026-10-12T15:30:00Z\",\"lastVersion\":3,\"transferredTo\":\"" + NEW + "\"}").find(FROM, OLD).block();
        assertThat(gone).isNotNull();
        assertThat(gone.tombstone()).isTrue();
        assertThat(gone.charged()).isFalse();
        assertThat(gone.accountId()).isEqualTo("123456789012");
        assertThat(gone.version()).isEqualTo(3);
        assertThat(gone.changedAt()).isEqualTo(MOVED);
        assertThat(gone.transferredTo()).isEqualTo(NEW);
    }

    @Test
    void a410ThatNamesNothingIsStillTheTombstoneOfTheWfrnAskedFor() {
        for (String body : List.of("", "{}", "Gone", "{\"error\":\"resource.transferred\"}")) {
            ResourceSnapshot gone = answering(HttpStatus.GONE, body).find(FROM, OLD).block();
            assertThat(gone).as("410 with %s", body).isNotNull();
            assertThat(gone.wfrn()).isEqualTo(OLD);
            assertThat(gone.tombstone()).isTrue();
            assertThat(gone.version()).isZero();
            assertThat(gone.changedAt()).isNull();
        }
    }

    @Test
    void theResourceAMoveOpenedNamesTheWfrnItWas() {
        ResourceSnapshot opened = answering(HttpStatus.OK, resource(NEW, "active", 1, "\"transferredFrom\":\"" + OLD + "\",\"source\":\"transfer\""))
                .find(TO, NEW).block();
        assertThat(opened).isNotNull();
        assertThat(opened.transferredFrom()).isEqualTo(OLD);
        assertThat(opened.accountId()).isEqualTo("210987654321");
        assertThat(opened.createdAt()).isEqualTo(MOVED.plusMillis(3));
        assertThat(opened.charged()).isTrue();
    }

    @Test
    void theDeletedListHoldsAMovedResourcesTombstoneDeletedAtTheMove() {
        String page = "{\"items\":[" + resource(OLD, "deleted", 3, "\"transferredTo\":\"" + NEW + "\",\"deletedAt\":\"2026-10-12T15:30:00Z\"")
                + "],\"next\":null}";
        List<ResourceSnapshot> deleted = answering(HttpStatus.OK, page).list(FROM, null, true, null).collectList().block();
        assertThat(deleted).singleElement().satisfies(r -> {
            assertThat(r.transferredTo()).isEqualTo(NEW);
            assertThat(r.charged()).isFalse();
            assertThat(r.changedAt()).isEqualTo(MOVED);
        });
    }

    private static String resource(String wfrn, String state, long version, String more) {
        return "{\"wfrn\":\"" + wfrn + "\",\"state\":\"" + state + "\",\"version\":" + version + ",\"attributes\":{\"cpu\":4},\"tags\":{\"team\":\"web\"},"
                + "\"createdAt\":\"2026-10-12T15:30:00.003Z\",\"updatedAt\":\"2026-10-12T15:30:00.003Z\"," + more + "}";
    }

    /** The adapter over a WebClient whose every call answers this. */
    private static HttpStateService answering(HttpStatus status, String body) {
        return new HttpStateService(WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(status).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build())).build());
    }
}
