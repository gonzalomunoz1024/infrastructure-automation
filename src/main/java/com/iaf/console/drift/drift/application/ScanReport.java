package com.iaf.console.drift.drift.application;

/** What a scan did: resources read, findings opened, updated and closed, reads deferred by the budget, errors. */
public record ScanReport(int checked, int opened, int updated, int closed, int deferred, int errors, int pages) {

    public static ScanReport empty() {
        return new ScanReport(0, 0, 0, 0, 0, 0, 0);
    }

    public static ScanReport checked(int n) {
        return new ScanReport(n, 0, 0, 0, 0, 0, 0);
    }

    public static ScanReport deferred(int n) {
        return new ScanReport(0, 0, 0, 0, n, 0, 0);
    }

    public static ScanReport errors(int n) {
        return new ScanReport(0, 0, 0, 0, 0, n, 0);
    }

    public static ScanReport page() {
        return new ScanReport(0, 0, 0, 0, 0, 0, 1);
    }

    public ScanReport plus(ScanReport o) {
        return new ScanReport(checked + o.checked, opened + o.opened, updated + o.updated, closed + o.closed, deferred + o.deferred, errors + o.errors,
                pages + o.pages);
    }

    public boolean idle() {
        return checked == 0 && deferred == 0 && errors == 0;
    }
}
