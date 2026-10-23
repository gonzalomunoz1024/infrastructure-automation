package com.iaf.console.bff.notifications.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The people a draft is for, from its audience and the account's people. A person the event names
 * is told when they are still a user of the account, with the address the identity service holds;
 * group members, admins and members come from the identity service alone. When the account's people
 * could not be read, only the people the event itself names are told, with what it says of them.
 * Only console users ({@code user_…}) are told, each once, and never someone the draft leaves out.
 */
public final class Recipients {

    private Recipients() {
    }

    /** The recipients, in the audience's order; {@code people} is null when the identity service did not answer. */
    public static List<Recipient> of(Draft draft, AccountPeople people) {
        Map<String, Recipient> out = new LinkedHashMap<>();
        for (Audience audience : draft.audience()) {
            for (Recipient r : resolve(audience, people)) {
                if (Recipient.isConsoleUser(r.userId()) && !draft.exclude().contains(r.userId())) {
                    out.putIfAbsent(r.userId(), r);
                }
            }
        }
        return List.copyOf(out.values());
    }

    private static List<Recipient> resolve(Audience audience, AccountPeople people) {
        List<Recipient> out = new ArrayList<>();
        switch (audience) {
            case Audience.Person p -> {
                if (people == null) {
                    out.add(new Recipient(p.userId(), p.email(), p.name()));
                } else if (people.users().containsKey(p.userId())) {
                    Recipient known = people.users().get(p.userId());
                    out.add(new Recipient(known.userId(), known.email() != null ? known.email() : p.email(), known.name() != null ? known.name() : p.name()));
                }
            }
            case Audience.People p -> p.userIds().forEach(id -> {
                if (people == null) {
                    out.add(new Recipient(id, null, null));
                } else if (people.users().containsKey(id)) {
                    out.add(people.users().get(id));
                }
            });
            case Audience.GroupMembers g -> addAll(out, people, people == null ? Set.of() : people.membersOf(g.group()));
            case Audience.Admins a -> addAll(out, people, people == null ? Set.of() : people.admins());
            case Audience.Members m -> {
                if (people != null) {
                    out.addAll(people.users().values());
                }
            }
        }
        return out;
    }

    private static void addAll(List<Recipient> out, AccountPeople people, Set<String> ids) {
        if (people == null) {
            return;
        }
        ids.stream().sorted().forEach(id -> {
            Recipient r = people.users().get(id);
            if (r != null) {
                out.add(r);
            }
        });
    }
}
