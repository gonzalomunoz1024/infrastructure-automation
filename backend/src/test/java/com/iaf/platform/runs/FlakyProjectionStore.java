package com.iaf.platform.runs;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import com.iaf.platform.runs.adapters.outbound.InMemoryProjectionStore;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobEvent;
import com.iaf.platform.runs.ports.outbound.ProjectionStore;

/** An in-memory runs index that refuses the first {@code times} matching saves or appends, like a database that is briefly down. */
public final class FlakyProjectionStore implements ProjectionStore {

    private final InMemoryProjectionStore delegate = new InMemoryProjectionStore();
    private final Predicate<Job> failSave;
    private final Predicate<JobEvent> failAppend;
    private final AtomicInteger left;
    private final AtomicInteger refused = new AtomicInteger();

    public FlakyProjectionStore(Predicate<Job> failSave, Predicate<JobEvent> failAppend, int times) {
        this.failSave = failSave;
        this.failAppend = failAppend;
        this.left = new AtomicInteger(times);
    }

    /** A store that refuses the first {@code times} saves of a job in a terminal state. */
    public static FlakyProjectionStore refusingTerminalSaves(int times) {
        return new FlakyProjectionStore(job -> job.status() != null && job.status().terminal(), e -> false, times);
    }

    /** How many writes it refused so far. */
    public int refused() {
        return refused.get();
    }

    private void maybeFail(boolean matches) {
        if (matches && left.getAndDecrement() > 0) {
            refused.incrementAndGet();
            throw new IllegalStateException("the store is down");
        }
    }

    @Override
    public Optional<Job> job(String id) {
        return delegate.job(id);
    }

    @Override
    public void save(Job job) {
        maybeFail(failSave.test(job));
        delegate.save(job);
    }

    @Override
    public List<Job> jobs(JobQuery query) {
        return delegate.jobs(query);
    }

    @Override
    public List<Job> children(String parentId) {
        return delegate.children(parentId);
    }

    @Override
    public Optional<Approval> approval(String id) {
        return delegate.approval(id);
    }

    @Override
    public List<Approval> approvals() {
        return delegate.approvals();
    }

    @Override
    public void append(JobEvent event) {
        maybeFail(failAppend.test(event));
        delegate.append(event);
    }

    @Override
    public List<JobEvent> history(String jobId) {
        return delegate.history(jobId);
    }

    @Override
    public long lastSeq(String jobId) {
        return delegate.lastSeq(jobId);
    }
}
