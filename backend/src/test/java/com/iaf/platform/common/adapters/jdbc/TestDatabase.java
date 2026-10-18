package com.iaf.platform.common.adapters.jdbc;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A fresh, migrated database for each test of a JDBC adapter, built without Spring:
 * {@link #h2()} is H2 in PostgreSQL mode (always available); {@link #postgres()} is a new schema
 * in the PostgreSQL named by {@value #POSTGRES_URL} (for example
 * {@code jdbc:postgresql://localhost:5432/platform?user=platform}), dropped after the test.
 * Register it with {@code @RegisterExtension}; it is ready before the test's own {@code @BeforeEach}.
 */
public final class TestDatabase implements BeforeEachCallback, AfterEachCallback {

    /** The environment variable naming a PostgreSQL for the tests; unset, the PostgreSQL tests are skipped. */
    public static final String POSTGRES_URL = "PLATFORM_TEST_POSTGRES_URL";

    private final boolean postgres;
    private DriverManagerDataSource dataSource;
    private String schema;
    private Jdbc jdbc;

    private TestDatabase(boolean postgres) {
        this.postgres = postgres;
    }

    public static TestDatabase h2() {
        return new TestDatabase(false);
    }

    public static TestDatabase postgres() {
        return new TestDatabase(true);
    }

    /** An H2 database with a name, migrated; it lives until {@code SHUTDOWN} or the end of the JVM. */
    public static DataSource h2Named(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource(h2Url(name), "sa", "");
        Migrations.migrate(ds);
        return ds;
    }

    public static String h2Url(String name) {
        return "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        String name = "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        if (postgres) {
            String url = System.getenv(POSTGRES_URL);
            if (url == null || url.isBlank()) {
                throw new IllegalStateException(POSTGRES_URL + " is not set");
            }
            schema = name;
            new Jdbc(new DriverManagerDataSource(url)).update("CREATE SCHEMA " + schema);
            dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        } else {
            dataSource = new DriverManagerDataSource(h2Url(name), "sa", "");
        }
        Migrations.migrate(dataSource);
        jdbc = new Jdbc(dataSource);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (dataSource == null) {
            return;
        }
        if (postgres) {
            new Jdbc(new DriverManagerDataSource(System.getenv(POSTGRES_URL))).update("DROP SCHEMA " + schema + " CASCADE");
        } else {
            jdbc.update("SHUTDOWN");
        }
        dataSource = null;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public Jdbc jdbc() {
        return jdbc;
    }

    public boolean isPostgres() {
        return postgres;
    }
}
