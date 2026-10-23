package com.iaf.console.drift.drift.adapters.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** One turn of a loop, run by this service's worker. */
@ActivityInterface
public interface LoopActivities {

    /** Runs the turn and answers a one-line summary for the workflow's history. */
    @ActivityMethod
    String tick(LoopSpec spec);
}
