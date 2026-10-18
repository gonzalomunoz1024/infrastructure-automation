package com.iaf.platform.common.adapters.jdbc;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.platform.config.PlatformProperties;
import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

/**
 * The {@code postgres} profile: a connection pool over {@code spring.datasource.*} (so
 * {@code SPRING_DATASOURCE_URL}, {@code SPRING_DATASOURCE_USERNAME} and
 * {@code SPRING_DATASOURCE_PASSWORD} from the environment), Spring's Flyway on the platform's
 * migrations, and the {@link Jdbc} every JDBC adapter uses, created once the migrations ran.
 * Without the profile there is no DataSource at all ({@code PlatformApplication} leaves the
 * DataSource auto-configuration out), and the in-memory adapters serve every port.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@Profile("postgres")
@EnableConfigurationProperties(DataSourceProperties.class)
public class PersistenceConfig {

    /** The seed name of the demo history in {@code store_seeds}. */
    public static final String DEMO_HISTORY = "demo-history";

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName("platform");
        return pool;
    }

    /** {@code db/migration/common} plus the database's own folder, whatever {@code spring.flyway.locations} says. */
    @Bean
    public FlywayConfigurationCustomizer platformMigrations() {
        return configuration -> configuration.locations(Migrations.locations(configuration.getDataSource()));
    }

    @Bean
    @DependsOnDatabaseInitialization
    public Jdbc jdbc(DataSource dataSource, PlatformProperties props, Clock clock) {
        Jdbc jdbc = new Jdbc(dataSource);
        demoHistoryOnce(jdbc, props, clock);
        return jdbc;
    }

    /**
     * The demo history ({@code platform.demo.seed-history}: two weeks of jobs, provider calls,
     * releases and resources) is written once per database. The first start claims it; a later
     * one finds it claimed and turns the switch off before any seeder runs (the stores a seeder
     * writes to are built on this bean, and the job and telemetry seeders wait for the application
     * to be ready), so a restart does not stack another history on top.
     */
    static void demoHistoryOnce(Jdbc jdbc, PlatformProperties props, Clock clock) {
        if (props.getDemo() == null || !props.getDemo().isSeedHistory()) {
            return;
        }
        if (jdbc.claimSeed(DEMO_HISTORY, clock.instant())) {
            log.info("demo history: seeding this database (once)");
            return;
        }
        props.getDemo().setSeedHistory(false);
        log.info("demo history: the database has it since {}; not seeded again", jdbc.seededAt(DEMO_HISTORY).map(Object::toString).orElse("an earlier start"));
    }
}
