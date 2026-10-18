package com.iaf.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The MongoDB client exists only with the mongo profile (common/adapters/mongo/MongoConfig); every other profile keeps its stores in memory. */
@SpringBootApplication(exclude = MongoAutoConfiguration.class)
@EnableScheduling
public class PlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
