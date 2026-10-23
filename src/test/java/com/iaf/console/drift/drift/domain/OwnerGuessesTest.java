package com.iaf.console.drift.drift.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

class OwnerGuessesTest {

    private static final AccountInfo COMPUTE = new AccountInfo("123456789012", "Compute", "team-compute");
    private static final AccountInfo DATA = new AccountInfo("210987654321", "Data", "team-data");
    private static final AccountInfo PAYMENTS = new AccountInfo("333333333333", "Payments Core", "team-payments");
    private static final List<AccountInfo> ACCOUNTS = List.of(COMPUTE, DATA, PAYMENTS);

    private static ObjectNode observed(String... pairs) {
        ObjectNode o = Json.object();
        for (int i = 0; i < pairs.length; i += 2) {
            o.put(pairs[i], pairs[i + 1]);
        }
        return o;
    }

    @Test
    void aTagNamingATeamIdOrNameIsAHighGuess() {
        OwnerGuesses guesses = new OwnerGuesses(ACCOUNTS, null);

        OwnerGuess byTeam = guesses.guess("vm-1042", observed("owner", "team-compute"));
        assertThat(byTeam.accountId()).isEqualTo(COMPUTE.accountId());
        assertThat(byTeam.accountName()).isEqualTo("Compute");
        assertThat(byTeam.team()).isEqualTo("team-compute");
        assertThat(byTeam.confidence()).isEqualTo(OwnerGuess.HIGH);
        assertThat(byTeam.reasons()).containsExactly("Tagged owner=team-compute");

        ObjectNode tagged = Json.object();
        tagged.putObject("tags").put("Owning-Team", "payments core");
        assertThat(guesses.guess("vm-7", tagged).accountId()).isEqualTo(PAYMENTS.accountId());

        ObjectNode labelled = Json.object();
        labelled.putObject("labels").put("account_id", DATA.accountId());
        OwnerGuess byId = guesses.guess("pod-9", labelled);
        assertThat(byId.accountId()).isEqualTo(DATA.accountId());
        assertThat(byId.confidence()).isEqualTo(OwnerGuess.HIGH);
    }

    @Test
    void anAccountsOrTeamsNameInTheObjectsNameIsAMediumGuess() {
        OwnerGuesses guesses = new OwnerGuesses(ACCOUNTS, null);

        OwnerGuess data = guesses.guess("data-etl-1043", observed());
        assertThat(data.accountId()).isEqualTo(DATA.accountId());
        assertThat(data.confidence()).isEqualTo(OwnerGuess.MEDIUM);
        assertThat(data.reasons()).containsExactly("The name data-etl-1043 contains the account's name, Data");

        // a name of several words matches as consecutive words, whatever the separators
        assertThat(guesses.guess("PaymentsCore-api", observed())).isNull();
        assertThat(guesses.guess("payments_core-api-2", observed()).accountId()).isEqualTo(PAYMENTS.accountId());
        assertThat(guesses.guess("team-payments-api", observed()).reasons()).containsExactly("The name team-payments-api contains the team's name, team-payments");
        // a word inside another word is not the name: "database" is not "data"
        assertThat(guesses.guess("database-7", observed())).isNull();
    }

    @Test
    void aStepThatPointsAtSeveralAccountsSaysNothingAndTheNextStepIsTried() {
        OwnerGuesses guesses = new OwnerGuesses(ACCOUNTS, null);

        assertThat(guesses.guess("compute-to-data-bridge", observed())).isNull();
        // two tags naming two accounts: no high guess, so the name decides
        OwnerGuess fromName = guesses.guess("data-sync", observed("owner", "team-compute", "team", "team-payments"));
        assertThat(fromName.accountId()).isEqualTo(DATA.accountId());
        assertThat(fromName.confidence()).isEqualTo(OwnerGuess.MEDIUM);
    }

    @Test
    void aPlaceSharedWithOneAccountsManagedResourcesOnlyIsALowGuess() {
        OwnerGuesses.Neighbours neighbours = new OwnerGuesses.Neighbours();
        neighbours.add(COMPUTE.accountId(), observed("folder", "/dc1/web", "network", "shared-net"));
        neighbours.add(DATA.accountId(), observed("folder", "/dc1/etl", "network", "shared-net"));
        OwnerGuesses guesses = new OwnerGuesses(ACCOUNTS, neighbours);

        OwnerGuess web = guesses.guess("vm-1", observed("folder", "/dc1/web", "network", "shared-net"));
        assertThat(web.accountId()).isEqualTo(COMPUTE.accountId());
        assertThat(web.confidence()).isEqualTo(OwnerGuess.LOW);
        assertThat(web.reasons()).containsExactly("Shares folder /dc1/web only with Compute's resources");

        // a network both accounts use says nothing, and two accounts manage the type: no guess
        assertThat(guesses.guess("vm-2", observed("network", "shared-net"))).isNull();
    }

    @Test
    void theOnlyAccountManagingTheTypeIsALowGuessWhenNothingElsePoints() {
        OwnerGuesses.Neighbours neighbours = new OwnerGuesses.Neighbours();
        neighbours.add(COMPUTE.accountId(), observed("cpu", "4"));
        neighbours.add(COMPUTE.accountId(), null);
        OwnerGuesses guesses = new OwnerGuesses(ACCOUNTS, neighbours);

        OwnerGuess guess = guesses.guess("vm-2001", observed("powerState", "poweredOff"));
        assertThat(guess.accountId()).isEqualTo(COMPUTE.accountId());
        assertThat(guess.confidence()).isEqualTo(OwnerGuess.LOW);
        assertThat(guess.reasons()).containsExactly("Compute is the only account with managed resources of this type here");
        // a stronger step still wins
        assertThat(guesses.guess("data-2001", observed()).accountId()).isEqualTo(DATA.accountId());
    }

    @Test
    void shortNamesAndUnknownAccountsGuessNothing() {
        AccountInfo qa = new AccountInfo("444444444444", "QA", "qa");
        OwnerGuesses guesses = new OwnerGuesses(List.of(qa), null);

        assertThat(guesses.guess("qa-runner-1", observed())).isNull();
        assertThat(new OwnerGuesses(ACCOUNTS, null).guess("vm-1", observed("owner", "team-unknown"))).isNull();
        assertThat(new OwnerGuesses(List.of(), new OwnerGuesses.Neighbours()).guess("vm-1", null)).isNull();
    }
}
