package com.iaf.platform.runs.domain;

/**
 * The change record opened for a job when {@code policy.change.required}: {@code CHG00xxxxx} in
 * ServiceNow. {@code evidenceUrl} is the link back to the job's evidence record that the change
 * carries (null when the change system was not given one).
 */
public record ChangeTicket(String id, String url, String state, String evidenceUrl) {

    public ChangeTicket(String id, String url, String state) {
        this(id, url, state, null);
    }

    public ChangeTicket withState(String s) {
        return new ChangeTicket(id, url, s, evidenceUrl);
    }
}
