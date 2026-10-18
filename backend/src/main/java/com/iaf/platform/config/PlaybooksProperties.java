package com.iaf.platform.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import lombok.Data;

/** {@code platform.playbooks}: upload limits and the git remotes playbooks come from. */
@Data
@Configuration
@ConfigurationProperties(prefix = "platform.playbooks")
public class PlaybooksProperties {

    /** The largest file {@code POST /playbooks:upload} accepts. */
    private DataSize maxUploadSize = DataSize.ofMegabytes(20);
    /** The most entries an archive may hold. */
    private int maxEntries = 2000;
    /** The most bytes an archive may unpack to (counted while reading, never from its headers). */
    private DataSize maxUnpackedSize = DataSize.ofMegabytes(100);
    /**
     * A linter to run on every upload once unpacked, such as {@code ansible-lint --offline {playbook}};
     * {@code {dir}} and {@code {playbook}} are replaced, it runs in the unpacked directory and a
     * non-zero exit refuses the upload with its output. Blank skips the lint, and the upload says so.
     */
    private String lintCommand = "";
    /** How long the linter may take; a slower one is killed and the upload refused. */
    private Duration lintTimeout = Duration.ofSeconds(60);
    private Git git = new Git();

    @Data
    public static class Git {
        /** Connect and read timeout of ls-remote, clone and fetch. */
        private Duration timeout = Duration.ofSeconds(20);
        /** Where checkouts are cached, one directory per repo and commit. */
        private String cacheDir = System.getProperty("java.io.tmpdir") + "/iaf-platform/git";
        /** Tests only: accept {@code file://} repositories. Remote repositories are https only. */
        private boolean allowFileUrls = false;
    }
}
