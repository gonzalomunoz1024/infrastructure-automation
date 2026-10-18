package com.iaf.platform.playbooks.domain;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.iaf.platform.common.archives.PlaybookArchive;
import com.iaf.platform.common.archives.PlaybookArchive.Kind;
import com.iaf.platform.common.archives.PlaybookArchive.Listing;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;

/**
 * What an upload must satisfy beyond the archive checks: a clean file name, an entry playbook
 * that is in the archive, and a playbook that parses as a YAML list of plays.
 */
public final class UploadRules {

    /** The largest playbook whose YAML is checked; real playbooks are far smaller. */
    public static final int MAX_CHECKED_BYTES = 1024 * 1024;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final int PREVIEW = 12;

    private UploadRules() {
    }

    /** Uploading and resolving playbooks is for authors: members of a team, or admins. */
    public static void requireAuthor(Identity who, String what) {
        if (!who.admin() && (who.teams() == null || who.teams().isEmpty())) {
            throw new ForbiddenException("Only members of a team can " + what);
        }
    }

    /** The name the upload is kept under: the base name the client sent, checked like an archive entry. */
    public static String fileName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("The file part has no file name");
        }
        String n = raw.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        Kind.of(n);
        return PlaybookArchive.safeName(n, false);
    }

    /**
     * The playbook to run inside the upload, checked: it must be one of the archive's files and a
     * .yml or .yaml file. Null when none is named; a single playbook needs none.
     */
    public static String path(Listing listing, String fileName, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = PlaybookArchive.safeName(path.trim(), false);
        if (listing.kind() == Kind.YAML) {
            if (!p.equals(fileName)) {
                throw new IllegalArgumentException("path names a playbook inside an archive; " + fileName + " is a single playbook");
            }
            return p;
        }
        if (!listing.files().contains(p)) {
            throw new IllegalArgumentException("path " + p + " is not in " + fileName + "; it holds " + preview(listing.files()));
        }
        if (!p.endsWith(".yml") && !p.endsWith(".yaml")) {
            throw new IllegalArgumentException("path names the playbook to run, a .yml or .yaml file; got " + p);
        }
        return p;
    }

    /** A playbook is a YAML list of plays; larger files than {@link #MAX_CHECKED_BYTES} are not parsed. */
    public static void requirePlaybook(String name, byte[] content) {
        if (content == null || content.length > MAX_CHECKED_BYTES) {
            return;
        }
        JsonNode doc;
        try {
            doc = YAML.readTree(content);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("");
            throw new IllegalArgumentException(name + " is not valid YAML: " + msg);
        }
        if (doc == null || !doc.isArray() || doc.isEmpty()) {
            throw new IllegalArgumentException(name + " is not a playbook: a playbook is a YAML list of plays");
        }
    }

    static String preview(List<String> files) {
        if (files.isEmpty()) {
            return "no files";
        }
        String shown = String.join(", ", files.subList(0, Math.min(PREVIEW, files.size())));
        return files.size() > PREVIEW ? shown + " and " + (files.size() - PREVIEW) + " more" : shown;
    }
}
