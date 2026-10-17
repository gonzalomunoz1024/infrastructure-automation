package com.iaf.platform.runs.domain;

/** The change record opened for a job when {@code policy.change.required}: {@code CHG00xxxxx} in ServiceNow. */
public record ChangeTicket(String id, String url, String state) {

    public ChangeTicket withState(String s) {
        return new ChangeTicket(id, url, s);
    }
}
