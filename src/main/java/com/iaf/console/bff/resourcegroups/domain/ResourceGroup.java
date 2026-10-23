package com.iaf.console.bff.resourcegroups.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.wfrn.Ulid;
import com.iaf.console.wfrn.Wfrn;

/**
 * A resource group (the AWS Resource Groups analog): a saved tag query over an account's
 * resources in one region, with a name unique in that account and region that never changes. The
 * version grows by one with every change; the description is empty when there is none.
 */
public record ResourceGroup(
        String groupId,
        String wfrn,
        String name,
        String description,
        GroupQuery query,
        String accountId,
        String region,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** The service and type of a group's wfrn, and so of the actions on it ({@code resource-groups:<verb>}). */
    public static final String SERVICE = "resource-groups";
    public static final String TYPE = "group";
    public static final int MAX_NAME = 128;
    public static final int MAX_DESCRIPTION = 512;
    public static final String NAME_RULE = "group.name";
    private static final String ID_PREFIX = "group-";
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]{1," + MAX_NAME + "}$");

    public ResourceGroup {
        description = description == null ? "" : description;
        query = query == null ? new GroupQuery(null, null) : query;
    }

    /** A new group, at version 1, made in an account and region by {@code createdBy} at {@code at}. */
    public static ResourceGroup of(String partition, String accountId, String region, String createdBy, NewGroup group, Instant at) {
        String groupId = ID_PREFIX + Ulid.next().toLowerCase(Locale.ROOT);
        return new ResourceGroup(groupId, wfrnOf(partition, region, accountId, groupId), group.name(), group.description(), group.query().distinct(),
                accountId, region, createdBy, at, at, 1);
    }

    /** The group with the changes made at {@code at}, at the next version; this group itself when they change nothing. */
    public ResourceGroup with(GroupChanges changes, Instant at) {
        String nextDescription = changes.description() == null ? description : changes.description();
        GroupQuery nextQuery = changes.query() == null ? query : changes.query().distinct();
        if (nextDescription.equals(description) && nextQuery.equals(query)) {
            return this;
        }
        return new ResourceGroup(groupId, wfrn, name, nextDescription, nextQuery, accountId, region, createdBy, createdAt, at, version + 1);
    }

    /** Whether the group belongs to an account and region. */
    public boolean in(String otherAccountId, String otherRegion) {
        return accountId.equals(otherAccountId) && region.equals(otherRegion);
    }

    /** A group's name: {@code wfrn:<partition>:resource-groups:<region>:<account>:group/<groupId>}. */
    public static String wfrnOf(String partition, String region, String accountId, String groupId) {
        return new Wfrn(partition, SERVICE, region, accountId, TYPE, groupId).format();
    }

    /** Whether a string is a group id as this service mints them: {@code group-} and a lowercase ULID. */
    public static boolean isGroupId(String id) {
        return id != null && id.startsWith(ID_PREFIX) && Ulid.isValid(id.substring(ID_PREFIX.length()));
    }

    /** The 400 for a group's problems: the sentence of the one problem, or all of them. */
    public static InvalidInputException invalid(List<Problem> problems) {
        String message = problems.size() == 1 ? problems.get(0).message()
                : "the group has " + problems.size() + " problems: " + problems.stream().map(Problem::message).collect(Collectors.joining("; "));
        return new InvalidInputException(message, problems);
    }

    static void checkName(String name, List<Problem> problems) {
        if (name == null || name.isEmpty()) {
            problems.add(Problem.error("required", "/name", "name is required"));
        } else if (!NAME.matcher(name).matches()) {
            problems.add(Problem.error(NAME_RULE, "/name", "a group's name is 1 to " + MAX_NAME + " letters, digits, '.', '_' or '-'"));
        }
    }

    static void checkDescription(String description, List<Problem> problems) {
        if (description != null && description.length() > MAX_DESCRIPTION) {
            problems.add(Problem.error("length", "/description", "description is at most " + MAX_DESCRIPTION + " characters"));
        }
    }
}
