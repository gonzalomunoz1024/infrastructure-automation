package com.iaf.console.bff.resources.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.tags.domain.TagPolicy;

/**
 * Launch more like this (WP 4.6): a create form filled from a resource, for the target account.
 * {@code input} holds the source's values of the create workflow's fields (secrets left out, the
 * name suggested anew); {@code tags} the source's tags, the account's own defaults swapped for the
 * target's; {@code notes} what to look at before creating (a field left out, a resource the
 * target account may not see); {@code targets} every account of the person's, with whether they
 * may create the type there. Nothing is created: the wizard opens with the draft.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CloneDraft(String type, String workflow, Source source, String accountId, Map<String, Object> input, Map<String, String> tags,
        List<Note> notes, List<Target> targets, TagPolicy tagPolicy) {

    public CloneDraft {
        input = input == null ? Map.of() : Map.copyOf(input);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        notes = notes == null ? List.of() : List.copyOf(notes);
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    /** The resource the draft is copied from. */
    public record Source(String wfrn, String name, String accountId, String region) {
    }

    /** A field to look at before creating: why it was left out or needs a second look. */
    public record Note(String field, String code, String message) {
    }

    /** An account the person may create the copy in, or why not. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Target(String accountId, String name, boolean current, boolean allowed, Denial denied) {
    }
}
