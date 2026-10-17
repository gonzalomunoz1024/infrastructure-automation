package com.iaf.platform.resources.ports.inbound;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.resources.domain.Resource;

import reactor.core.publisher.Mono;

/** Write side of the registry: the interpreter's {@code register} step and the retire action. */
public interface ResourceCommands {

    /**
     * {@code register} from a running job. {@code id} may be null (the registry mints one);
     * registering an existing id updates its attributes and state.
     */
    record RegisterResource(String type, String id, String state, JsonNode attributes, List<String> builtFrom,
            String jobId, String workflow, String version, Identity requester) {
    }

    Mono<Resource> register(RegisterResource command);

    /** 409 with the dependents when something is built from the resource. */
    Mono<CommandAccepted> retire(String id);
}
