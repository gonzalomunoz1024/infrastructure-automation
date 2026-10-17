package com.iaf.platform.workflows.ports.outbound;

import java.util.List;
import java.util.Optional;

import com.iaf.platform.workflows.domain.ChangeRequest;

/** Change requests on the content repo: in memory (merged from the UI) or GitHub pull requests (planned). */
public interface ChangeRequestPort {

    List<ChangeRequest> all();

    Optional<ChangeRequest> get(String id);

    void save(ChangeRequest cr);
}
