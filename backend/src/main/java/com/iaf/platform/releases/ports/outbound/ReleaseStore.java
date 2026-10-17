package com.iaf.platform.releases.ports.outbound;

import java.util.List;
import java.util.Optional;

import com.iaf.platform.releases.domain.Release;

/** Where releases live. The demo keeps them in memory; production would use the projection store. */
public interface ReleaseStore {

    Release save(Release release);

    Optional<Release> find(String id);

    /** Newest first; {@code workflow} null means every workflow. */
    List<Release> list(String workflow);

    /** Every release that still owns traffic decisions (canary or paused), newest first. */
    List<Release> active();

    /** The release that decides routing for a workflow, when one is active. */
    Optional<Release> active(String workflow);
}
