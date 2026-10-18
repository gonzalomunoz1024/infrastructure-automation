package com.iaf.platform.audit.adapters.outbound;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.config.PlatformProperties;

/**
 * Profile {@code worm}: evidence as read-only, write-once files under
 * {@code platform.audit.worm.dir}, each with a retention sidecar
 * ({@code platform.audit.retention-years} after it was written). See {@link WormEvidenceStore}
 * for the layout and {@link FileSystemWormObjects} for the write path.
 */
@Component
@Profile("worm")
@Primary
public class FileSystemEvidenceStore extends WormEvidenceStore {

    @Autowired
    public FileSystemEvidenceStore(WormProperties worm, PlatformProperties platform) {
        this(Path.of(worm.getDir()), platform.getAudit().getRetentionYears());
    }

    public FileSystemEvidenceStore(Path dir, int retentionYears) {
        super(new FileSystemWormObjects(dir), retentionYears);
    }
}
