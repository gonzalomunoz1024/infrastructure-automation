package com.iaf.console.drift.drift.domain;

/** What the owner guesses need to know of a console account: its id, its name and the team that owns it. */
public record AccountInfo(String accountId, String name, String team) {
}
