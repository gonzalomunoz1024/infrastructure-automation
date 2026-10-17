package com.iaf.platform.catalog.domain;

import java.util.List;

import com.iaf.platform.common.domain.Problem;

/** The input of one call fails its schema: 400 with {@code { error, problems }}. */
public class InputProblemsException extends IllegalArgumentException {

    private final transient List<Problem> problems;

    public InputProblemsException(String message, List<Problem> problems) {
        super(message);
        this.problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }
}
