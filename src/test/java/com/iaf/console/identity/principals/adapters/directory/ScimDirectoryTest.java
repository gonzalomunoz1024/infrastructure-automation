package com.iaf.console.identity.principals.adapters.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.identity.principals.domain.DirectoryGroup;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** The SCIM directory against a small SCIM 2.0 stub: the group by displayName, its users page by page, the bearer token on every call. */
class ScimDirectoryTest {

    private static final String TOKEN = "scim-" + Long.toHexString(System.nanoTime());

    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scim/v2/Groups", this::groups);
        server.createContext("/scim/v2/Users", this::users);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ScimDirectory directory(int pageSize) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
        return new ScimDirectory(WebClient.builder(), new ScimProperties(base, TOKEN, pageSize, Duration.ofSeconds(5)));
    }

    @Test
    void readsTheGroupByDisplayNameAndPagesItsUsers() {
        DirectoryGroup group = directory(2).findGroup("team-compute").block();
        assertThat(group).isNotNull();
        assertThat(group.name()).isEqualTo("team-compute");
        assertThat(group.members()).containsExactly(
                new DirectoryGroup.Member("dana@example.com", "u-dana"),
                new DirectoryGroup.Member("Mateo@Example.com", "u-mateo"),
                new DirectoryGroup.Member("ana@example.com", "u-ana"));
        assertThat(group.hasEmail("mateo@example.com")).as("emails match lowercased").isTrue();
        assertThat(calls).containsExactly(
                "/Groups filter=displayName eq \"team-compute\"",
                "/Users filter=groups.value eq \"g-compute\" startIndex=1 count=2",
                "/Users filter=groups.value eq \"g-compute\" startIndex=3 count=2");
        assertThat(authorizations).hasSize(3).containsOnly("Bearer " + TOKEN);
    }

    @Test
    void aGroupTheDirectoryLacksIsEmptyAndAnEmptyGroupHasNoMembers() {
        assertThat(directory(100).findGroup("team-gone").block()).isNull();
        DirectoryGroup empty = directory(100).findGroup("team-empty").block();
        assertThat(empty).isNotNull();
        assertThat(empty.members()).isEmpty();
    }

    @Test
    void aFailingDirectoryIsAnErrorNotAnEmptyGroup() {
        failing = true;
        assertThatThrownBy(() -> directory(100).findGroup("team-compute").block()).hasMessageContaining("500").hasMessageNotContaining(TOKEN);
    }

    @Test
    void pagesThatNeverEndAreAnError() {
        assertThatThrownBy(() -> directory(1).findGroup("team-endless").block()).hasMessageContaining("did not end");
    }

    @Test
    void theTokenIsRequiredAndNeverInTheSettingsText() {
        ScimProperties properties = new ScimProperties("https://idp.example.com/scim/v2", TOKEN, null, null);
        assertThat(properties.toString()).doesNotContain(TOKEN).contains("(set)");
        assertThat(properties.pageSize()).isEqualTo(ScimProperties.DEFAULT_PAGE_SIZE);
        assertThat(new ScimProperties(null, null, 5000, null).pageSize()).isEqualTo(ScimProperties.MAX_PAGE_SIZE);
        assertThatThrownBy(() -> new ScimDirectory(WebClient.builder(), new ScimProperties("https://idp.example.com/scim/v2", " ", null, null)))
                .hasMessageContaining("console.identity.directory.scim.token");
        assertThatThrownBy(() -> new ScimDirectory(WebClient.builder(), new ScimProperties(null, TOKEN, null, null)))
                .hasMessageContaining("base-url").hasMessageNotContaining(TOKEN);
    }

    @Test
    void filterLiteralsAreQuotedAndEscaped() {
        assertThat(ScimDirectory.quoted("team \"a\\b\"")).isEqualTo("\"team \\\"a\\\\b\\\"\"");
    }

    private void groups(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        record(exchange, "/Groups filter=" + query.get("filter"));
        if (failing) {
            respond(exchange, 500, "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:Error\"],\"status\":\"500\"}");
            return;
        }
        String id = switch (query.get("filter")) {
            case "displayName eq \"team-compute\"" -> "g-compute";
            case "displayName eq \"team-empty\"" -> "g-empty";
            case "displayName eq \"team-endless\"" -> "g-endless";
            default -> null;
        };
        respond(exchange, 200, id == null ? list(0, 1, "") : list(1, 1, "{\"id\":\"" + id + "\",\"displayName\":\"x\"}"));
    }

    private void users(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        int start = Integer.parseInt(query.get("startIndex"));
        record(exchange, "/Users filter=" + query.get("filter") + " startIndex=" + start + " count=" + query.get("count"));
        String body = switch (query.get("filter")) {
            case "groups.value eq \"g-compute\"" -> start == 1
                    ? list(4, start, user("u-dana", "dana", "[{\"value\":\"old@example.com\"},{\"value\":\"dana@example.com\",\"primary\":true}]")
                            + "," + user("u-mateo", "Mateo@Example.com", null))
                    : list(4, start, user("u-ana", "ana", "[{\"value\":\"ana@example.com\"}]") + "," + user("u-robot", "robot", null));
            case "groups.value eq \"g-endless\"" -> list(1_000_000, start, user("u-" + start, "same@example.com", null));
            default -> list(0, start, "");
        };
        respond(exchange, 200, body);
    }

    private void record(HttpExchange exchange, String call) {
        calls.add(call);
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private static String user(String id, String userName, String emails) {
        return "{\"id\":\"" + id + "\",\"userName\":\"" + userName + "\"" + (emails == null ? "" : ",\"emails\":" + emails) + "}";
    }

    private static String list(int total, int start, String resources) {
        return "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\"totalResults\":" + total + ",\"startIndex\":" + start
                + ",\"Resources\":[" + resources + "]}";
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        for (String pair : raw == null ? new String[0] : raw.split("&")) {
            int eq = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
