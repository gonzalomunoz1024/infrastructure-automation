package com.iaf.console.bff.recent.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.wfrn.Wfrn;

/**
 * A person's favourite resources in one account (decision 32.3), at most 100, newest first. Putting
 * one is idempotent: a resource already there keeps its place and when it was added, and takes
 * the type and name it is put with; a new one past the hundredth is refused.
 */
public final class Favourites {

    public static final int MAX = 100;
    public static final int MAX_NAME = 200;
    /** The code of the 409 when a new favourite would be the 101st. */
    public static final String FULL = "favourites.full";

    public static final Comparator<Favourite> NEWEST_FIRST = Comparator.comparing(Favourite::addedAt).reversed().thenComparing(Favourite::wfrn);

    /** The outcome of a put: the list after it and the favourite as kept, or {@code full} (and no favourite) when there was no room. */
    public record Put(List<Favourite> favourites, Favourite kept, boolean full) {
    }

    private Favourites() {
    }

    public static Put put(List<Favourite> current, Favourite favourite, int max) {
        Optional<Favourite> existing = current.stream().filter(f -> f.wfrn().equals(favourite.wfrn())).findFirst();
        if (existing.isPresent()) {
            Favourite kept = new Favourite(favourite.wfrn(), favourite.type(), favourite.name(), existing.get().addedAt());
            List<Favourite> next = new ArrayList<>(current);
            next.replaceAll(f -> f.wfrn().equals(kept.wfrn()) ? kept : f);
            return new Put(List.copyOf(next), kept, false);
        }
        if (current.size() >= max) {
            return new Put(List.copyOf(current), null, true);
        }
        List<Favourite> next = new ArrayList<>(current);
        next.add(favourite);
        return new Put(List.copyOf(next), favourite, false);
    }

    /**
     * The favourite a {@code PUT} asks for: the wfrn's type unless the body names it (it must be
     * the wfrn's), the name the body gives (at most 200 characters) or else the last part of the
     * wfrn's id. A 400 names each problem.
     */
    public static Favourite of(Wfrn wfrn, String type, String name, Instant now) {
        List<Problem> problems = new ArrayList<>();
        if (type != null && !type.isBlank() && !type.equals(wfrn.type())) {
            problems.add(Problem.error("mismatch", "type", "type is the wfrn's type, " + wfrn.type()));
        }
        if (name != null && name.strip().length() > MAX_NAME) {
            problems.add(Problem.error("range", "name", "name is at most " + MAX_NAME + " characters"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("the favourite is not valid", problems);
        }
        String id = wfrn.id();
        String shown = name == null || name.isBlank() ? id.substring(id.lastIndexOf('/') + 1) : name.strip();
        return new Favourite(wfrn.format(), wfrn.type(), shown, now);
    }
}
