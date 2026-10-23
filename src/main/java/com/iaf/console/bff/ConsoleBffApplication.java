package com.iaf.console.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The console's backend for frontend: the one door the browser uses. It signs users in through the
 * firm's identity provider, holds their sessions, composes the type descriptors the console draws
 * every page from, relays live updates, and calls the platform for each account with that account's
 * own service account, naming the user in a signed assertion (P-02).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ConsoleBffApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsoleBffApplication.class, args);
    }
}
