package com.iaf.console.drift.monitoring.domain;

import com.iaf.console.kit.paging.Cursor;

/** What a page of an account's alarms asks for: one resource or all, one state or all, and where the last page ended. */
public record AlarmQuery(String wfrn, AlarmState state, int limit, Cursor after) {
}
