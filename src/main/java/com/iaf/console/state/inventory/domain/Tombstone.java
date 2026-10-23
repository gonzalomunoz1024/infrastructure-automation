package com.iaf.console.state.inventory.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/** What {@code GET /v1/wfrn/{wfrn}} answers with a 410 while a deleted resource's name stays reserved. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Tombstone(String error, String wfrn, Instant deletedAt, Instant tombstoneUntil, long lastVersion) {
}
