package com.iaf.platform.common.adapters.jdbc;

import java.sql.DatabaseMetaData;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;

/**
 * Where the schema comes from: {@code db/migration/common} for every database, plus the folder
 * of the database at hand ({@code db/migration/postgresql}: the append-only triggers of the audit
 * evidence; the tests add {@code db/migration/h2}). Spring's Flyway runs them on start with the
 * {@code postgres} profile (see {@link PersistenceConfig}); the adapter tests call
 * {@link #migrate} on their own database.
 */
public final class Migrations {

    public static final String COMMON = "classpath:db/migration/common";

    private Migrations() {
    }

    /** The common folder and the folder named after the database, such as {@code classpath:db/migration/postgresql}. */
    public static String[] locations(DataSource dataSource) {
        return new String[] {COMMON, "classpath:db/migration/" + vendor(dataSource)};
    }

    /** Bring a database up to date; returns how many migrations ran. */
    public static int migrate(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(locations(dataSource))
                .load()
                .migrate()
                .migrationsExecuted;
    }

    /** The database's id as Spring Boot names it (postgresql, h2), from its JDBC URL. */
    static String vendor(DataSource dataSource) {
        try {
            String url = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getURL);
            return DatabaseDriver.fromJdbcUrl(url).getId();
        } catch (MetaDataAccessException e) {
            throw new IllegalStateException("cannot tell which database " + dataSource + " is", e);
        }
    }
}
