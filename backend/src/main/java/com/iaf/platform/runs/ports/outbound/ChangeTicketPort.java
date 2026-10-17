package com.iaf.platform.runs.ports.outbound;

import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.Preview;

import reactor.core.publisher.Mono;

/**
 * The change-management system a job is recorded in when {@code policy.change.required}. The demo
 * adapter is a mock ServiceNow ({@code CHG00xxxxx} with a link); the production adapter calls the
 * firm's ServiceNow change API (TODO: endpoint and template are the firm's to provide).
 */
public interface ChangeTicketPort {

    /** What the change says: the job, the workflow version, who asked, and the preview as the change description. */
    record OpenChange(String jobId, String workflow, String version, String requester, String summary, Preview preview) {
    }

    Mono<ChangeTicket> open(OpenChange change);

    /** Close the change when the job ends: {@code success} picks the close code. */
    Mono<ChangeTicket> close(String changeId, boolean success);
}
