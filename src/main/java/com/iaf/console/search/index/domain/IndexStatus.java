package com.iaf.console.search.index.domain;

import java.time.Instant;

/** The contract's {@code IndexStatus}: how many documents, how far behind the events, whether a rebuild runs. */
public record IndexStatus(long documents, double lagSeconds, Instant lastEventAt, boolean rebuilding) {
}
