package com.iaf.console.identity.common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/** The checks every input shares: names, emails, lengths, and the 400 with problems that reports them. */
public final class Names {

    /** A group, role or policy name as the contract spells it. */
    public static final Pattern NAME = Pattern.compile("^[a-z0-9-]{2,64}$");
    public static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    public static final Pattern QUOTA_KEY = Pattern.compile("^[a-z0-9.-]{1,64}$");

    private Names() {
    }

    public static boolean isName(String s) {
        return s != null && NAME.matcher(s).matches();
    }

    public static boolean isEmail(String s) {
        return s != null && s.length() <= 254 && EMAIL.matcher(s).matches();
    }

    public static String normalizeEmail(String s) {
        return s == null ? null : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Collects problems and throws the 400 when any is an error. */
    public static final class Check {

        private final List<Problem> problems = new ArrayList<>();

        public Check require(boolean ok, String field, String message) {
            if (!ok) {
                problems.add(Problem.error("invalid", field, message));
            }
            return this;
        }

        public Check problem(String code, String field, String message) {
            problems.add(Problem.error(code, field, message));
            return this;
        }

        public boolean ok() {
            return problems.isEmpty();
        }

        public List<Problem> problems() {
            return List.copyOf(problems);
        }

        public void done(String what) {
            if (!problems.isEmpty()) {
                throw new InvalidInputException(what + " is not valid", problems);
            }
        }
    }
}
