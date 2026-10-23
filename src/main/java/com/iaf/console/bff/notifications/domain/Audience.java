package com.iaf.console.bff.notifications.domain;

import java.util.List;

/**
 * Who a notification is for, before the identity service names the people (decision 32.1): one
 * person the event names, people the event lists by id, the members of a group, the account's
 * admins, or every member of the account.
 */
public sealed interface Audience {

    /** A person the event names, with what it says about them. */
    record Person(String userId, String email, String name) implements Audience {
    }

    /** People the event lists by user id (an access request's approvers). */
    record People(List<String> userIds) implements Audience {

        public People {
            userIds = userIds == null ? List.of() : List.copyOf(userIds);
        }
    }

    /** The members of a group of the account, by its name (an approval's group). */
    record GroupMembers(String group) implements Audience {
    }

    /** The account's admins: the members of a group that holds the account-admin role. */
    record Admins() implements Audience {
    }

    /** Every member of the account. */
    record Members() implements Audience {
    }

    static Audience person(String userId, String email, String name) {
        return new Person(userId, email, name);
    }

    static Audience admins() {
        return new Admins();
    }

    static Audience members() {
        return new Members();
    }

    static Audience group(String name) {
        return new GroupMembers(name);
    }

    static Audience people(List<String> userIds) {
        return new People(userIds);
    }
}
