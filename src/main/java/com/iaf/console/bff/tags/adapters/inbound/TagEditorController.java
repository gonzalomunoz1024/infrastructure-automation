package com.iaf.console.bff.tags.adapters.inbound;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.tags.application.TagEditor;
import com.iaf.console.bff.tags.domain.TagChange;
import com.iaf.console.bff.tags.domain.TagChangeResult;
import com.iaf.console.bff.tags.domain.TagEditorRow;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * The Tag Editor: {@code GET /console/v1/tags/resources} finds the account's resources across
 * types by tag ({@code type} repeated or comma separated, {@code tag.<name>}, {@code limit},
 * {@code after}); {@code POST /console/v1/tags:apply} sets and removes tags on up to 100 of them.
 */
@RestController
public class TagEditorController {

    private final TagEditor editor;

    public TagEditorController(TagEditor editor) {
        this.editor = editor;
    }

    @GetMapping(path = "/console/v1/tags/resources", produces = "application/json")
    public Mono<Page<TagEditorRow>> find(@RequestParam MultiValueMap<String, String> params, ServerWebExchange exchange) {
        List<String> types = new ArrayList<>();
        for (String value : params.getOrDefault("type", List.of())) {
            for (String one : value.split(",")) {
                if (!one.isBlank()) {
                    types.add(one.strip());
                }
            }
        }
        Map<String, String> tags = new LinkedHashMap<>();
        params.forEach((name, values) -> {
            if (name.startsWith("tag.") && name.length() > 4 && !values.isEmpty()) {
                tags.put(name.substring(4), values.get(0));
            }
        });
        Integer limit = params.getFirst("limit") == null ? null : parseLimit(params.getFirst("limit"));
        String after = params.getFirst("after");
        return CurrentSession.required(exchange).flatMap(session -> editor.find(session, types, tags, limit, after));
    }

    @PostMapping(path = "/console/v1/tags:apply", consumes = "application/json", produces = "application/json")
    public Mono<TagChangeResult> apply(@RequestBody TagChange change, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> editor.apply(session, change, OnBehalfOf.requestId(Correlation.of(exchange)), idempotencyKey));
    }

    private static Integer parseLimit(String value) {
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
