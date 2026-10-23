package com.iaf.console.kit.errors;

import java.util.List;

/** Input that failed its schema or names something unknown: a 400 with {@code problems}. */
public class InvalidInputException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final transient List<Problem> problems;

    public InvalidInputException(String message, List<Problem> problems) {
        super(message);
        this.problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }
}
