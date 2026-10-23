package com.iaf.console.drift.monitoring.application;

/**
 * What one evaluation pass did ({@code AlarmsReport}): the alarms evaluated, the ones that started
 * firing, the ones that went back to ok, the ones without points, and the ones that could not be
 * evaluated (their resource's read failed, or their write did).
 */
public record AlarmsReport(int evaluated, int fired, int cleared, int insufficient, int errors) {

    public static AlarmsReport empty() {
        return new AlarmsReport(0, 0, 0, 0, 0);
    }

    public AlarmsReport plus(AlarmsReport o) {
        return new AlarmsReport(evaluated + o.evaluated, fired + o.fired, cleared + o.cleared, insufficient + o.insufficient, errors + o.errors);
    }
}
