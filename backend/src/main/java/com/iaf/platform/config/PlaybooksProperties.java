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
