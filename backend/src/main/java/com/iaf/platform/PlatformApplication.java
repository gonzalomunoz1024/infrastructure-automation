package com.iaf.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The DataSource exists only with the postgres profile (common/adapters/jdbc/PersistenceConfig); every other profile keeps its stores in memory. */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableScheduling
public class PlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
