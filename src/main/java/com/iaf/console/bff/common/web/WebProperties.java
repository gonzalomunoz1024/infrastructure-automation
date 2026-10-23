package com.iaf.console.bff.common.web;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The web app's origins: allowed for CORS with credentials, and the only ones a write may come from. */
@ConfigurationProperties(prefix = "console.web")
public record WebProperties(@DefaultValue({"http://localhost:3000"}) List<String> origins) {
}
