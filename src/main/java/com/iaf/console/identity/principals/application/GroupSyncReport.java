package com.iaf.console.identity.principals.application;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** What a group sync run did, account by account. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupSyncReport(Instant startedAt, Instant finishedAt, List<AccountReport> accounts) {

    public GroupSyncReport {
        accounts = List.copyOf(accounts);
    }

    public int added() {
        return accounts.stream().mapToInt(a -> a.added().size()).sum();
    }

    public int removed() {
        return accounts.stream().mapToInt(a -> a.removed().size()).sum();
    }

    public int skipped() {
        return accounts.stream().mapToInt(a -> a.skipped().size()).sum();
    }

    public long failed() {
        return accounts.stream().filter(a -> a.error() != null).count();
    }

    /**
     * One active account: the groups added and removed and the removals skipped (each a person and
     * a group), the identity provider groups its groups map that the directory does not have, and
     * whether its owning team is among them. A run that could not finish the account leaves it as it
     * was and says why in {@code error}, without {@code owningTeamMissing}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountReport(String accountId, String owningTeam, Boolean owningTeamMissing, List<Change> added, List<Change> removed, List<Change> skipped,
            List<String> missingIdpGroups, String error) {

        public AccountReport {
            added = List.copyOf(added);
            removed = List.copyOf(removed);
            skipped = List.copyOf(skipped);
            missingIdpGroups = List.copyOf(missingIdpGroups);
        }

        static AccountReport failed(String accountId, String owningTeam, String error) {
            return new AccountReport(accountId, owningTeam, null, List.of(), List.of(), List.of(), List.of(), error);
        }
    }

    /** A person and a group (by name); {@code reason} says why a removal was skipped. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Change(String userId, String email, String group, String reason) {
    }
}
