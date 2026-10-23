package com.iaf.console.bff.assistant.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * What an assistant answers for one turn, before the BFF reviews it: words, proposals, an
 * explanation (summary and sections; the BFF adds the wfrn and the facts), the reading of a failed
 * job, or a refusal with its reason. The review decides what of it the person sees.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantAnswer(String text, List<AssistantProposal> proposals, ResourceExplanation explanation, FailureReading failure, boolean refused,
        String reason) {

    /** The most proposals an answer is read with; the review keeps at most five. */
    static final int READ_AT_MOST = 20;

    public AssistantAnswer {
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
    }

    /**
     * Why a job failed and the fix to try: what went wrong in plain words, the fix, an input to
     * merge over the job's when the fix changes it, and how sure the assistant is (high, medium,
     * low). The platform's own message stays the failure's detail.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FailureReading(String title, String fix, Map<String, Object> input, String confidence) {
    }

    public static AssistantAnswer of(String text, List<AssistantProposal> proposals) {
        return new AssistantAnswer(text, proposals, null, null, false, null);
    }

    public static AssistantAnswer explaining(String text, String summary, List<ResourceExplanation.Section> sections) {
        return new AssistantAnswer(text, List.of(), new ResourceExplanation(null, summary, sections, null), null, false, null);
    }

    public static AssistantAnswer reading(FailureReading failure) {
        return new AssistantAnswer(null, List.of(), null, failure, false, null);
    }

    public static AssistantAnswer refusal(String reason) {
        return new AssistantAnswer(null, List.of(), null, null, true, reason);
    }

    /**
     * An answer in the answer schema's JSON, read leniently: a field of another type is left out
     * and said in {@code notes}, so the review decides on what is there. Throws
     * {@link IllegalArgumentException} for JSON that is no answer at all.
     */
    public static AssistantAnswer read(JsonNode node, List<String> notes) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("the answer is not a JSON object");
        }
        boolean refused = node.path("refused").isBoolean() && node.get("refused").booleanValue();
        String reason = SearchFilter.text(node, "reason");
        List<AssistantProposal> proposals = new ArrayList<>();
        JsonNode list = node.get("proposals");
        if (list != null && list.isArray()) {
            for (JsonNode item : list) {
                if (proposals.size() >= READ_AT_MOST) {
                    notes.add("more than " + READ_AT_MOST + " proposals");
                    break;
                }
                AssistantProposal proposal = AssistantProposal.read(item, notes);
                if (proposal != null) {
                    proposals.add(proposal);
                }
            }
        } else if (list != null && !list.isNull()) {
            notes.add("proposals is not a list");
        }
        ResourceExplanation explanation = null;
        JsonNode e = node.get("explanation");
        if (e != null && e.isObject()) {
            List<ResourceExplanation.Section> sections = new ArrayList<>();
            for (JsonNode section : e.path("sections")) {
                String title = SearchFilter.text(section, "title");
                String text = SearchFilter.text(section, "text");
                if (title != null && text != null) {
                    sections.add(new ResourceExplanation.Section(title, text));
                } else {
                    notes.add("a section without a title or a text");
                }
            }
            explanation = new ResourceExplanation(null, SearchFilter.text(e, "summary"), sections, null);
        } else if (e != null && !e.isNull()) {
            notes.add("explanation is not an object");
        }
        FailureReading failure = null;
        JsonNode f = node.get("failure");
        if (f != null && f.isObject()) {
            Map<String, Object> input = null;
            JsonNode in = f.get("input");
            if (in != null && in.isObject()) {
                input = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> field : in.properties()) {
                    Object value = AssistantProposal.value(field.getValue());
                    if (value != null) {
                        input.put(field.getKey(), value);
                    }
                }
            }
            failure = new FailureReading(SearchFilter.text(f, "title"), SearchFilter.text(f, "fix"), input, SearchFilter.text(f, "confidence"));
        } else if (f != null && !f.isNull()) {
            notes.add("failure is not an object");
        }
        String text = SearchFilter.text(node, "text");
        if (!refused && text == null && proposals.isEmpty() && explanation == null && failure == null && !node.has("text")) {
            throw new IllegalArgumentException("the JSON has none of an answer's fields");
        }
        return new AssistantAnswer(text, proposals, explanation, failure, refused, reason);
    }
}
