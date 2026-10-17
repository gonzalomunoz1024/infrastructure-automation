package com.iaf.platform.workflows.domain;

import java.util.List;

/**
 * The replay check: the inputs of recent jobs of the previous published version, dry-run against
 * the new definition. A divergence is a job whose input the new version no longer handles.
 */
public record ReplayResult(boolean passed, int replayed, List<Divergence> divergences) {

    public record Divergence(String jobId, String step, String reason) {
    }

    public static ReplayResult none() {
        return new ReplayResult(true, 0, List.of());
    }
}
