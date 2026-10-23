package com.iaf.console.search.index.adapters.opensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.search.index.adapters.SearchIndexContractTest;
import com.iaf.console.search.index.domain.Fixtures;
import com.iaf.console.search.index.domain.IndexedResource;
import com.iaf.console.search.index.domain.SuggestQuery;
import com.iaf.console.search.index.domain.Suggestion;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;

import reactor.core.publisher.Mono;

/** OpenSearch keeps the contract, on a fresh alias and index per test, with writes that wait for a refresh. */
@EnabledIf("com.iaf.console.search.index.adapters.opensearch.OpenSearchTestSupport#available")
class OpenSearchIndexTest extends SearchIndexContractTest {

    private static final List<String> CREATED = new ArrayList<>();
    private static final WebClient WEB = OpenSearchTestSupport.available() ? OpenSearchTestSupport.client() : null;

    @Override
    protected SearchIndexPort index() {
        return fresh();
    }

    private OpenSearchIndex fresh() {
        String name = "console-test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        OpenSearchIndex index = new OpenSearchIndex(WEB, name, "wait_for", clock);
        CREATED.add(index.concreteIndex());
        index.ensureReady().block();
        // a second call finds the alias and changes nothing
        index.ensureReady().block();
        return index;
    }

    @Test
    void suggestionsCountTypesAndTagsOncePerHalfMinute() {
        OpenSearchIndex index = fresh();
        index.index(Fixtures.document(Fixtures.VM, "web-03", 1, Map.of("env", "prod"), Fixtures.T0)).block();
        assertThat(tagLabels(index, "pro")).containsExactly("env=prod (1)");
        index.index(Fixtures.document(Fixtures.VM.substring(0, Fixtures.VM.length() - 1) + "b", "web-04", 1, Map.of("env", "prod"), Fixtures.T0)).block();
        // the resources themselves are found at once; their counts wait for the next count
        assertThat(index.suggest(SCOPE, SuggestQuery.of("web", null, 10)).block()).extracting(Suggestion::value).contains("web-03", "web-04");
        assertThat(tagLabels(index, "pro")).containsExactly("env=prod (1)");
        clock.advance(OpenSearchIndex.SUGGESTION_WORDS_TTL);
        assertThat(tagLabels(index, "pro")).containsExactly("env=prod (2)");
    }

    private static List<String> tagLabels(OpenSearchIndex index, String prefix) {
        return index.suggest(SCOPE, SuggestQuery.of(prefix, null, 10)).block().stream().filter(s -> s.kind() == Suggestion.Kind.TAG)
                .map(Suggestion::label).toList();
    }

    @Test
    void theDocumentRoundTrips() {
        OpenSearchIndex index = fresh();
        IndexedResource doc = Fixtures.document(Fixtures.VM, "web-03", 2, Map.of("env", "prod"), Fixtures.T0).toBuilder()
                .field("environment", "prod").field("cpu", 4L).drift(true).driftSeverity("warning").driftFindingId("f-1").lastJobId("job-1")
                .workflow("create-vm").build();
        index.index(doc).block();
        IndexedResource read = index.find(SCOPE, Fixtures.VM).block();
        assertThat(read).isEqualTo(doc);
        assertThat(index.ping().block()).isTrue();
    }

    @Test
    void theAliasFrontsAVersionedIndex() {
        OpenSearchIndex index = fresh();
        JsonNode aliases = WEB.get().uri("/_alias/{alias}", index.alias()).retrieve().bodyToMono(JsonNode.class).block();
        assertThat(aliases.has(index.concreteIndex())).isTrue();
        assertThat(aliases.path(index.concreteIndex()).path("aliases").path(index.alias()).path("is_write_index").asBoolean()).isTrue();
        JsonNode mapping = WEB.get().uri("/{index}/_mapping", index.concreteIndex()).retrieve().bodyToMono(JsonNode.class).block();
        assertThat(mapping.path(index.concreteIndex()).path("mappings").path("dynamic").asText()).isEqualTo("strict");
    }

    @Test
    void aWriteNeverCreatesAnIndexOfItsOwn() {
        OpenSearchIndex index = fresh();
        IndexedResource doc = Fixtures.document(Fixtures.VM, "web-01", 1, Map.of(), Fixtures.T0);
        status(HttpMethod.DELETE, "/" + index.concreteIndex());
        assertThatThrownBy(() -> index.index(doc).block()).isInstanceOf(OpenSearchException.class);
        assertThatThrownBy(() -> index.indexAll(List.of(doc)).block()).isInstanceOf(OpenSearchException.class);
        assertThat(status(HttpMethod.HEAD, "/" + index.alias())).as("nothing was created under the alias's name").isEqualTo(404);
        index.ensureReady().block();
        index.index(doc).block();
        assertThat(index.find(SCOPE, Fixtures.VM).blockOptional()).isPresent();
    }

    @Test
    void aRemovedAliasIsAttachedAgain() {
        OpenSearchIndex index = fresh();
        IndexedResource doc = Fixtures.document(Fixtures.VM, "web-01", 1, Map.of(), Fixtures.T0);
        index.index(doc).block();
        assertThat(status(HttpMethod.DELETE, "/" + index.concreteIndex() + "/_alias/" + index.alias())).isEqualTo(200);
        assertThat(index.ping().block()).isFalse();
        index.ensureReady().block();
        assertThat(index.ping().block()).isTrue();
        assertThat(index.find(SCOPE, Fixtures.VM).block()).as("the documents are still there").isEqualTo(doc);
    }

    private static int status(HttpMethod method, String path) {
        return WEB.method(method).uri(path).exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value())).block();
    }

    @AfterAll
    static void dropIndexes() {
        if (WEB == null) {
            return;
        }
        for (String name : CREATED) {
            WEB.delete().uri("/{index}", name).exchangeToMono(r -> r.releaseBody()).onErrorResume(e -> Mono.empty()).block();
        }
    }
}
