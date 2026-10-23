package com.iaf.console.drift.rightsizing.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the last utilisation pass kept of one resource: its size then (the size fields of its desired
 * state), since when it has had that size as far as this service saw, and its utilisation. The next
 * pass compares the size: a resource that changed size supersedes its recommendation, and only the
 * points since the change judge the new size.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceUtilisation(String wfrn, String accountId, String region, String type, ObjectNode size, Instant sizeSince, Utilisation utilisation,
        Instant readAt) {
}
