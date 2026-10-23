package com.iaf.console.platform.model;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Whether an evidence record's hash chain and seals hold. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Verification(boolean valid, int entries, Integer brokenAt, String seal, List<SealCheck> seals) {

    /** One verified seal or checkpoint. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SealCheck(String kind, Instant at, Integer seq, String keyId, String status) {
    }
}
