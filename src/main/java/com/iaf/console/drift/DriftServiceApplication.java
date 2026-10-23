package com.iaf.console.drift;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * console-drift-service: reads every managed resource's truth from its provider through the
 * platform's observe API (P-09) on its tier's interval, opens a finding when it differs from the
 * desired state the state service holds, and lets the account revert, accept or ignore it. A
 * revert is always a person's call; nothing here reverts on its own.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DriftServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriftServiceApplication.class, args);
    }
}
