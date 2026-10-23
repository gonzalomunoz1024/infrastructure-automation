package com.iaf.console.bff.session.application;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The step-up policy: which actions need a fresh authentication, and how fresh. */
@ConfigurationProperties(prefix = "console.auth.step-up")
public record StepUpProperties(
        @DefaultValue("10m") Duration maxAge,
        @DefaultValue({"*:delete"}) List<String> actions) {
}
