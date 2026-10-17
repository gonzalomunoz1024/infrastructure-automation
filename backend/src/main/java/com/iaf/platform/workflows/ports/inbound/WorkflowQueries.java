package com.iaf.platform.workflows.ports.inbound;

import java.util.List;

import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.workflows.domain.CheckContext;
import com.iaf.platform.workflows.domain.WorkflowRecord;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read side of the workflows slice. Other slices (catalog, runs, releases, assistant) read
 * published definitions through this port, never through the store.
 */
public interface WorkflowQueries {

    /** The current published version of a workflow; empty Mono when none. */
    Mono<WorkflowRecord> published(String name);

    /** A specific version, published or not; empty Mono when unknown. */
    Mono<WorkflowRecord> version(String name, String version);

    /** The highest published version satisfying a range like {@code ^1}; empty when none. */
    Mono<WorkflowRecord> resolve(String name, String range);

    Flux<WorkflowRecord> publishedAll();

    /** The catalog the checks look things up in (providers, published workflows, resource types). */
    Mono<CheckContext> checkContext();

    /** Run the checks on YAML text against the current catalog. */
    Mono<List<Problem>> check(String yaml);
}
