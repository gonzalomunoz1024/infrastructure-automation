package com.iaf.console.search.support;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/**
 * When a test fails on a timeout (a blocking read that never got its answer), prints every
 * thread's whole stack to stderr before the failure goes on, so a hang in CI says what each event
 * loop was doing. The failure itself is unchanged.
 */
public final class ThreadDumpOnHang implements TestExecutionExceptionHandler {

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        if (timedOut(failure)) {
            StringBuilder dump = new StringBuilder("THREAD DUMP after a timeout in ").append(context.getDisplayName()).append('\n');
            for (ThreadInfo thread : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
                append(dump, thread);
            }
            System.err.println(dump);
        }
        throw failure;
    }

    private static boolean timedOut(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** ThreadInfo's own toString keeps only eight frames. */
    private static void append(StringBuilder out, ThreadInfo thread) {
        out.append('"').append(thread.getThreadName()).append("\" ").append(thread.getThreadState());
        if (thread.getLockName() != null) {
            out.append(" on ").append(thread.getLockName());
        }
        if (thread.getLockOwnerName() != null) {
            out.append(" owned by \"").append(thread.getLockOwnerName()).append('"');
        }
        out.append('\n');
        StackTraceElement[] frames = thread.getStackTrace();
        for (int i = 0; i < frames.length; i++) {
            out.append("\tat ").append(frames[i]).append('\n');
            for (MonitorInfo monitor : thread.getLockedMonitors()) {
                if (monitor.getLockedStackDepth() == i) {
                    out.append("\t- locked ").append(monitor).append('\n');
                }
            }
        }
        for (LockInfo lock : thread.getLockedSynchronizers()) {
            out.append("\t- holds ").append(lock).append('\n');
        }
        out.append('\n');
    }
}
