package com.iaf.console.platform.model;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Registry ids registered by more than one provider or type. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CollisionReport(List<Collision> collisions) {

    /** One colliding id and its registrations. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Collision(String id, List<Registration> registrations) {
    }

    /** One registration of a colliding id. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Registration(String jobId, String provider, String type, String owner, Instant at) {
    }
}
