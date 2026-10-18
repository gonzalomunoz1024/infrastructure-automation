package com.iaf.platform.runs.adapters.outbound;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.ports.outbound.ChangeTicketPort;

import reactor.core.publisher.Mono;

/**
 * The demo change system: a ServiceNow look-alike that numbers changes {@code CHG00xxxxx}, links to
 * them and keeps the link back to the job's evidence ({@code platform.change.evidence-url}) on each.
 */
@Component
public class MockServiceNowChangeTickets implements ChangeTicketPort {

    public static final String BASE_URL = "https://servicenow.example.com/change/";

    private final AtomicInteger counter = new AtomicInteger(41_200);
    private final Map<String, ChangeTicket> tickets = new ConcurrentHashMap<>();
    private final ChangeProperties props;

    public MockServiceNowChangeTickets() {
        this(new ChangeProperties());
    }

    @Autowired
    public MockServiceNowChangeTickets(ChangeProperties props) {
        this.props = props;
    }

    @Override
    public Mono<ChangeTicket> open(OpenChange change) {
        return Mono.fromSupplier(() -> {
            String id = String.format("CHG%07d", counter.incrementAndGet());
            ChangeTicket t = new ChangeTicket(id, BASE_URL + id, "implement", props.evidenceUrlOf(change.jobId()));
            tickets.put(id, t);
            return t;
        });
    }

    @Override
    public Mono<ChangeTicket> close(String changeId, boolean success) {
        return Mono.fromSupplier(() -> {
            ChangeTicket t = tickets.getOrDefault(changeId, new ChangeTicket(changeId, BASE_URL + changeId, "implement"));
            ChangeTicket closed = t.withState(success ? "closed" : "closed-unsuccessful");
            tickets.put(changeId, closed);
            return closed;
        });
    }

    public Map<String, ChangeTicket> tickets() {
        return Map.copyOf(tickets);
    }
}
