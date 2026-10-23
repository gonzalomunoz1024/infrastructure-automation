package com.iaf.console.drift.rightsizing.application;

/**
 * What one utilisation pass did ({@code UtilisationReport}): resources read, resources skipped (no
 * metrics read for the type, a change in flight, too few points, or not due yet),
 * recommendations opened (or reopened), updated and superseded, and resources that failed.
 */
public record UtilisationReport(int read, int skipped, int opened, int updated, int superseded, int errors) {

    static final UtilisationReport SKIPPED = new UtilisationReport(0, 1, 0, 0, 0, 0);
    static final UtilisationReport OPENED = new UtilisationReport(0, 0, 1, 0, 0, 0);
    static final UtilisationReport UPDATED = new UtilisationReport(0, 0, 0, 1, 0, 0);
    static final UtilisationReport SUPERSEDED = new UtilisationReport(0, 0, 0, 0, 1, 0);
    static final UtilisationReport ERROR = new UtilisationReport(0, 0, 0, 0, 0, 1);

    public static UtilisationReport empty() {
        return new UtilisationReport(0, 0, 0, 0, 0, 0);
    }

    public UtilisationReport plus(UtilisationReport o) {
        return new UtilisationReport(read + o.read, skipped + o.skipped, opened + o.opened, updated + o.updated, superseded + o.superseded,
                errors + o.errors);
    }
}
