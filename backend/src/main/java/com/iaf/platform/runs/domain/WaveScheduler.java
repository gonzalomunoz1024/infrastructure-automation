package com.iaf.platform.runs.domain;

import java.util.ArrayList;
import java.util.List;

import com.iaf.platform.runs.domain.Batch.Wave;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;

/**
 * How a batch is released: the rollout's waves are cumulative percentages of the rows
 * ({@code [5, 25, 100]} on 12 rows is 1, then 2 more, then the remaining 9), each wave runs at most
 * {@code maxParallel} rows at once, and the batch stops after a wave when the failed share of the
 * rows processed so far exceeds {@code stopIfFailedPercent}.
 */
public final class WaveScheduler {

    private WaveScheduler() {
    }

    /** The waves for {@code rows} rows; empty waves are dropped, the last wave always reaches every row. */
    public static List<Wave> plan(int rows, RolloutPolicy rollout) {
        RolloutPolicy r = rollout == null ? RolloutPolicy.DEFAULT : rollout;
        List<Integer> percents = r.waves() == null || r.waves().isEmpty() ? RolloutPolicy.DEFAULT.waves() : r.waves();
        List<Wave> out = new ArrayList<>();
        if (rows <= 0) {
            return out;
        }
        int assigned = 0;
        for (int i = 0; i < percents.size(); i++) {
            boolean last = i == percents.size() - 1;
            int percent = Math.max(0, Math.min(100, percents.get(i)));
            int target = last ? rows : (int) Math.min(rows, Math.ceil(rows * percent / 100.0));
            int size = target - assigned;
            if (size <= 0 && !last) {
                continue;
            }
            if (size <= 0) {
                break;
            }
            out.add(new Wave(out.size(), percent, size, "pending", null, null));
            assigned += size;
        }
        if (assigned < rows) {
            Wave tail = out.isEmpty() ? null : out.get(out.size() - 1);
            if (tail != null) {
                out.set(out.size() - 1, tail.withSize(tail.size() + rows - assigned));
            } else {
                out.add(new Wave(0, 100, rows, "pending", null, null));
            }
        }
        return out;
    }

    /** The row indexes of each wave, in order: wave 0 takes the first rows, wave 1 the next, and so on. */
    public static List<List<Integer>> rowsOf(List<Wave> waves) {
        List<List<Integer>> out = new ArrayList<>();
        int next = 0;
        for (Wave w : waves) {
            List<Integer> rows = new ArrayList<>();
            for (int i = 0; i < w.size(); i++) {
                rows.add(next++);
            }
            out.add(rows);
        }
        return out;
    }

    /** After a wave: stop when the failed share of the rows processed so far exceeds the policy. */
    public static boolean shouldStop(long failed, long processed, int stopIfFailedPercent) {
        if (processed <= 0 || failed <= 0) {
            return false;
        }
        return failed * 100.0 / processed > stopIfFailedPercent;
    }

    public static int maxParallel(RolloutPolicy rollout) {
        return rollout == null || rollout.maxParallel() < 1 ? RolloutPolicy.DEFAULT.maxParallel() : rollout.maxParallel();
    }
}
