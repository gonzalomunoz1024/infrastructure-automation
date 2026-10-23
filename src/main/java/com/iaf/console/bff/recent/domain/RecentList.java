package com.iaf.console.bff.recent.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.wfrn.Wfrn;

/**
 * A person's recent pages in one account (decision 32.3): the 20 latest, newest first, each page
 * once. A visit moves its page to the top with the title it has now; past 20 the oldest go.
 */
public final class RecentList {

    public static final int MAX = 20;
    public static final int MAX_TITLE = 200;
    private static final Pattern TYPE_KEY = Pattern.compile("^[a-z0-9-]{1,32}/[a-z0-9-]{1,64}$");

    public static final Comparator<RecentItem> NEWEST_FIRST = Comparator.comparing(RecentItem::visitedAt).reversed();

    private RecentList() {
    }

    /** The list after a visit: the page on top, its older entry gone, at most {@code max} pages. */
    public static List<RecentItem> visit(List<RecentItem> current, RecentItem visit, int max) {
        List<RecentItem> next = new ArrayList<>();
        next.add(visit);
        current.stream().filter(item -> !item.samePage(visit)).sorted(NEWEST_FIRST).limit(Math.max(0, max - 1L)).forEach(next::add);
        return List.copyOf(next);
    }

    /**
     * The visit the web posts, checked: {@code kind} type or resource; {@code key} a type key
     * ({@code service/type}) or the wfrn of a resource of the session's account; {@code title} 1 to
     * 200 characters. A 400 names each problem.
     */
    public static RecentItem visitOf(String kind, String key, String title, String accountId, Instant at) {
        List<Problem> problems = new ArrayList<>();
        if (!RecentItem.TYPE.equals(kind) && !RecentItem.RESOURCE.equals(kind)) {
            problems.add(Problem.error("enum", "kind", "kind is type or resource"));
        } else if (key == null || key.isBlank()) {
            problems.add(Problem.error("required", "key", "key is the type key or the wfrn"));
        } else if (RecentItem.TYPE.equals(kind) && !TYPE_KEY.matcher(key).matches()) {
            problems.add(Problem.error("pattern", "key", "a type's key is service/type, such as compute/vm"));
        } else if (RecentItem.RESOURCE.equals(kind) && !Wfrn.isValid(key)) {
            problems.add(Problem.error("pattern", "key", "a resource's key is its wfrn"));
        } else if (RecentItem.RESOURCE.equals(kind) && !Wfrn.parse(key).account().equals(accountId)) {
            problems.add(Problem.error("account", "key", "the resource is not in the session's account"));
        }
        if (title == null || title.isBlank()) {
            problems.add(Problem.error("required", "title", "title is what the page is called"));
        } else if (title.strip().length() > MAX_TITLE) {
            problems.add(Problem.error("range", "title", "title is at most " + MAX_TITLE + " characters"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("the visit is not valid", problems);
        }
        return new RecentItem(kind, key, title.strip(), at);
    }

    /** What a page is shown on: its type's {@code list} in the wfrn's region for a resource, the session's region for a type. */
    public static ListCheck checkOf(RecentItem item, String sessionRegion) {
        return RecentItem.TYPE.equals(item.kind()) ? ListCheck.ofTypeKey(item.key(), sessionRegion) : ListCheck.ofWfrn(Wfrn.parse(item.key()));
    }
}
