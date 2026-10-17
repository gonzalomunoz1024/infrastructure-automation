package com.iaf.platform.catalog.domain;

import java.util.List;

import com.iaf.platform.common.domain.Problem;

/** Some rows of a batch fail the schema: 400 with every invalid row, before anything starts. */
public class BatchProblemsException extends IllegalArgumentException {

    /** One invalid row: its index in {@code items} and what is wrong with it. */
    public record RowProblems(int index, List<Problem> problems) {
    }

    private final transient List<RowProblems> rows;

    public BatchProblemsException(String message, List<RowProblems> rows) {
        super(message);
        this.rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public List<RowProblems> rows() {
        return rows;
    }
}
