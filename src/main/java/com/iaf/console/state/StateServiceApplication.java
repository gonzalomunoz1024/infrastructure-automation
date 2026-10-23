package com.iaf.console.state;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The console's resource inventory: every resource by wfrn, from the first request to the tombstone. */
@SpringBootApplication
public class StateServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StateServiceApplication.class, args);
    }
}
