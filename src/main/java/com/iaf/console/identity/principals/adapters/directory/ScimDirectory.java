package com.iaf.console.identity.principals.adapters.directory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.identity.principals.domain.DirectoryGroup;
import com.iaf.console.identity.principals.ports.outbound.DirectoryPort;

import reactor.core.publisher.Mono;

/**
 * The {@code scim} profile's directory: the identity provider's SCIM 2.0 service (RFC 7643 and
 * RFC 7644) over HTTP with a bearer token. A group is found by {@code displayName}
 * ({@code GET /Groups?filter=displayName eq "<name>"}); its members are the users whose
 * {@code groups} hold its id, paged with {@code startIndex} and {@code count}
 * ({@code GET /Users?filter=groups.value eq "<id>"}). A member's email is their primary email (else
 * their first, else a {@code userName} that is one) and their subject the SCIM id; a user without
 * an email is left out, since the sync matches people by email. A list whose pages never end is an
 * error rather than a partial answer, so a misbehaving server cannot make the sync drop people. The
 * token goes in the {@code Authorization} header only; nothing here logs a request.
 */
public final class ScimDirectory implements DirectoryPort {

    static final MediaType SCIM_JSON = MediaType.parseMediaType("application/scim+json");
    static final int MAX_PAGES = 1000;

    private final WebClient web;
    private final int pageSize;
    private final Duration timeout;

    public ScimDirectory(WebClient.Builder builder, ScimProperties properties) {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new IllegalStateException("the scim profile needs console.identity.directory.scim.base-url");
        }
        if (properties.token() == null || properties.token().isBlank()) {
            throw new IllegalStateException("the scim profile needs console.identity.directory.scim.token");
        }
        this.web = builder.clone()
                .baseUrl(properties.baseUrl())
                .defaultHeaders(headers -> {
                    headers.setBearerAuth(properties.token());
                    headers.setAccept(List.of(SCIM_JSON, MediaType.APPLICATION_JSON));
                })
                .build();
        this.pageSize = properties.pageSize();
        this.timeout = properties.timeout();
    }

    @Override
    public Mono<DirectoryGroup> findGroup(String name) {
        return web.get()
                .uri(b -> b.path("/Groups").queryParam("filter", "{filter}").queryParam("attributes", "id,displayName").build("displayName eq " + quoted(name)))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .flatMap(list -> {
                    JsonNode resources = list.path("Resources");
                    if (!resources.isArray() || resources.isEmpty() || !resources.get(0).path("id").isTextual()) {
                        return Mono.empty();
                    }
                    String groupId = resources.get(0).path("id").asText();
                    return page(groupId, 1, 1)
                            .expand(p -> p.nextStartIndex() == 0 ? Mono.empty() : page(groupId, p.nextStartIndex(), p.number() + 1))
                            .flatMapIterable(UsersPage::members)
                            .collectList()
                            .map(members -> new DirectoryGroup(name, members));
                });
    }

    /** One page of the group's users: its members, and where the next page starts (0 after the last one). */
    private record UsersPage(List<DirectoryGroup.Member> members, int nextStartIndex, int number) {
    }

    private Mono<UsersPage> page(String groupId, int startIndex, int number) {
        if (number > MAX_PAGES) {
            return Mono.error(new IllegalStateException("the directory's users of group " + groupId + " did not end after " + MAX_PAGES + " pages"));
        }
        return web.get()
                .uri(b -> b.path("/Users")
                        .queryParam("filter", "{filter}")
                        .queryParam("startIndex", startIndex)
                        .queryParam("count", pageSize)
                        .queryParam("attributes", "id,userName,emails")
                        .build("groups.value eq " + quoted(groupId)))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(page -> {
                    JsonNode resources = page.path("Resources");
                    int returned = resources.isArray() ? resources.size() : 0;
                    List<DirectoryGroup.Member> members = new ArrayList<>();
                    for (int i = 0; i < returned; i++) {
                        DirectoryGroup.Member member = member(resources.get(i));
                        if (member != null) {
                            members.add(member);
                        }
                    }
                    boolean last = returned == 0 || startIndex - 1 + returned >= page.path("totalResults").asInt(0);
                    return new UsersPage(List.copyOf(members), last ? 0 : startIndex + returned, number);
                });
    }

    static DirectoryGroup.Member member(JsonNode user) {
        String email = null;
        JsonNode emails = user.path("emails");
        if (emails.isArray()) {
            for (JsonNode e : emails) {
                if (e.path("value").isTextual() && (email == null || e.path("primary").asBoolean(false))) {
                    email = e.path("value").asText();
                    if (e.path("primary").asBoolean(false)) {
                        break;
                    }
                }
            }
        }
        if (email == null && user.path("userName").isTextual() && user.path("userName").asText().contains("@")) {
            email = user.path("userName").asText();
        }
        if (email == null || email.isBlank()) {
            return null;
        }
        return new DirectoryGroup.Member(email.trim(), user.path("id").isTextual() ? user.path("id").asText() : null);
    }

    /** A SCIM filter's string literal: in double quotes, with backslashes and quotes escaped (RFC 7644, 3.4.2.2). */
    static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
