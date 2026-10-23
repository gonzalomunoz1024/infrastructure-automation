package com.iaf.console.bff.notifications.domain;

import java.time.LocalDate;

/**
 * The console paths notifications open (decision 32.1), and the absolute address of one for an
 * email or a chat message: the web app's base URL and its hash route.
 */
public final class Links {

    private Links() {
    }

    public static String job(String jobId) {
        return "/jobs/" + jobId;
    }

    public static String resource(String type, String wfrn) {
        return "/r/" + type + "/" + wfrn;
    }

    public static String accessRequest(String requestId) {
        return "/iam/access-requests/" + requestId;
    }

    public static String digest(LocalDate weekStart) {
        return "/digests/" + weekStart;
    }

    public static String budget(String budgetId) {
        return "/cost/budgets/" + budgetId;
    }

    public static String quota(String quotaKey) {
        return "/iam/quotas/" + quotaKey;
    }

    public static String settings() {
        return "/settings/notifications";
    }

    /** {@code <baseUrl>/#<path>}: the web app routes on the hash. */
    public static String absolute(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return base + "/#" + (path.startsWith("/") ? path : "/" + path);
    }
}
