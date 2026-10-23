package com.iaf.console.bff.access.domain;

import java.util.List;

import com.iaf.console.kit.errors.ForbiddenException;

/**
 * A 403 that names the layer and statement that decided, rendered with a {@code denied} block; a
 * refused batch also names every row refused ({@code deniedRows}), the denial being the first's.
 */
public final class DeniedException extends ForbiddenException {

    private static final long serialVersionUID = 1L;

    private final transient Denial denial;
    private final transient List<DeniedRow> rows;

    public DeniedException(Denial denial) {
        this(denial, null);
    }

    public DeniedException(Denial denial, List<DeniedRow> rows) {
        super(denial.reason());
        this.denial = denial;
        this.rows = rows == null ? null : List.copyOf(rows);
    }

    public Denial denial() {
        return denial;
    }

    /** The refused rows of a batch; null for anything else. */
    public List<DeniedRow> rows() {
        return rows;
    }
}
