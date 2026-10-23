package com.iaf.console.bff.digests.domain;

import java.time.Instant;
import java.time.LocalDate;

/** A digest in the list of an account's digests: its week, headline and when it was written. */
public record DigestSummary(LocalDate weekStart, String headline, Instant generatedAt) {
}
