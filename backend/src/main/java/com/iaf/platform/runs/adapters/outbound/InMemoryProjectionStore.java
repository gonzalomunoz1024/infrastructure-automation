package com.iaf.platform.runs.adapters.outbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobEvent;
import com.iaf.platform.runs.ports.outbound.ProjectionStore;

/** The demo runs index: jobs, approvals and event histories in maps, gone with the process. */
@Profile("!postgres")
@Component
public class InMemoryProjectionStore implements ProjectionStore {

    static final Comparator<Job> NEWEST_FIRST = Comparator
            .comparing((Job j) -> j.startedAt() == null ? Instant.EPOCH : j.startedAt()).reversed()
            .thenComparing(Job::id);
    static final Comparator<Job> ROWS_THEN_OLDEST = Comparator
            .comparing((Job j) -> j.row() == null ? Integer.MAX_VALUE : j.row())
            .thenComparing(j -> j.startedAt() == null ? Instant.EPOCH : j.startedAt())
            .thenComparing(Job::id);
    static final Comparator<Approval> APPROVALS_NEWEST_FIRST = Comparator
            .comparing((Approval a) -> a.requestedAt() == null ? Instant.EPOCH : a.requestedAt()).reversed()
            .thenComparing(Approval::id);

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
    private final Map<String, List<JobEvent>> histories = new ConcurrentHashMap<>();

    @Override
    public Optional<Job> job(String id) {
        return Optional.ofNullable(id == null ? null : jobs.get(id));
    }

    @Override
    public void save(Job job) {
        jobs.put(job.id(), job);
        for (Approval a : job.approvals()) {
            approvals.put(a.id(), a);
        }
    }

    @Override
    public List<Job> jobs(JobQuery query) {
        JobQuery q = query == null ? JobQuery.ALL : query;
        List<Job> out = new ArrayList<>();
        for (Job j : jobs.values()) {
            if (q.matches(j)) {
                out.add(j);
            }
        }
        out.sort(NEWEST_FIRST);
        return q.limit() > 0 && out.size() > q.limit() ? new ArrayList<>(out.subList(0, q.limit())) : out;
    }

    @Override
    public List<Job> children(String parentId) {
        List<Job> out = new ArrayList<>();
        for (Job j : jobs.values()) {
            if (parentId != null && parentId.equals(j.parentId())) {
                out.add(j);
            }
        }
        out.sort(ROWS_THEN_OLDEST);
        return out;
    }

    @Override
    public Optional<Approval> approval(String id) {
        return Optional.ofNullable(id == null ? null : approvals.get(id));
    }

    @Override
    public List<Approval> approvals() {
        List<Approval> out = new ArrayList<>(approvals.values());
        out.sort(APPROVALS_NEWEST_FIRST);
        return out;
    }

    @Override
    public void append(JobEvent event) {
        histories.computeIfAbsent(event.jobId(), k -> Collections.synchronizedList(new ArrayList<>())).add(event);
    }

    @Override
    public List<JobEvent> history(String jobId) {
        List<JobEvent> h = jobId == null ? null : histories.get(jobId);
        if (h == null) {
            return List.of();
        }
        synchronized (h) {
            return List.copyOf(h);
        }
    }

    @Override
    public long lastSeq(String jobId) {
        List<JobEvent> h = jobId == null ? null : histories.get(jobId);
        if (h == null) {
            return 0;
        }
        synchronized (h) {
            return h.isEmpty() ? 0 : h.get(h.size() - 1).seq();
        }
    }
}
