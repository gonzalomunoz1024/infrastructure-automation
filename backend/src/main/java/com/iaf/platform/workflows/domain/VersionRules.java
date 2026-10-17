package com.iaf.platform.workflows.domain;

import java.util.Collection;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which version a saved draft gets. The content files are the published versions; a draft of a
 * published workflow is the next patch of the latest version unless the author chose a higher
 * {@code metadata.version}; a new workflow starts at {@value #FIRST}.
 */
public final class VersionRules {

    public static final String FIRST = "0.1.0";
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$");

    private VersionRules() {
    }

    /**
     * @param existing  every stored version of the workflow (any status)
     * @param requested the {@code metadata.version} of the saved file, may be null or invalid
     */
    public static String draftVersion(Collection<WorkflowRecord> existing, String requested) {
        Optional<String> latest = existing.stream()
                .filter(r -> r.status() != WorkflowStatus.DRAFT)
                .map(WorkflowRecord::version)
                .max(Semver.ORDER);
        if (latest.isEmpty()) {
            return isSemver(requested) ? requested : FIRST;
        }
        if (isSemver(requested) && Semver.parse(requested).compareTo(Semver.parse(latest.get())) > 0) {
            return requested;
        }
        return Semver.parse(latest.get()).bumpPatch().toString();
    }

    /** The version a brand-new workflow gets: its own {@code metadata.version} when valid, else {@value #FIRST}. */
    public static String firstVersion(String requested) {
        return isSemver(requested) ? requested : FIRST;
    }

    /** The highest version among records, whatever their status; empty when there are none. */
    public static Optional<String> latest(Collection<WorkflowRecord> records) {
        return records.stream().map(WorkflowRecord::version).max(Semver.ORDER);
    }

    public static boolean isSemver(String s) {
        return s != null && SEMVER.matcher(s).matches();
    }

    /** The change-request branch of a version: {@code change/<name>-<version>}. */
    public static String branch(String name, String version) {
        return "change/" + name + "-" + version;
    }
}
