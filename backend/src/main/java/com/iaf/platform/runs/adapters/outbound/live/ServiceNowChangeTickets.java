package com.iaf.platform.runs.adapters.outbound.live;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.ports.outbound.ChangeTicketPort;

import reactor.core.publisher.Mono;

/**
 * Production change records: {@code POST /api/sn_chg_rest/change/standard/{template}} on the
 * firm's ServiceNow with the job, the workflow version, the requester and the preview as the
 * description; close with the close code the job's outcome picks.
 *
 * <p>TODO: the instance URL, the standard-change template id and the credential reference are
 * the firm's to provide ({@code platform.change.*}); until then the live profile refuses to start
 * change-managed jobs rather than running them without a record.</p>
 */
@Component
@Profile("live")
@Primary
public class ServiceNowChangeTickets implements ChangeTicketPort {

    @Override
    public Mono<ChangeTicket> open(OpenChange change) {
        return Mono.error(new UnsupportedOperationException(
                "ServiceNow is not wired yet (profile live): no change can be opened for job " + change.jobId()
                        + ". Configure the instance, the standard-change template and the credential, then implement ServiceNowChangeTickets."));
    }

    @Override
    public Mono<ChangeTicket> close(String changeId, boolean success) {
        return Mono.error(new UnsupportedOperationException("ServiceNow is not wired yet (profile live): change " + changeId + " cannot be closed."));
    }
}
