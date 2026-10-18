package com.iaf.platform.common.adapters.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.ClassUtils;

import com.iaf.platform.PlatformApplication;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.workflows.adapters.outbound.GitWorkflowStore;
import com.iaf.platform.workflows.adapters.outbound.InMemoryWorkflowStore;
import com.iaf.platform.workflows.adapters.outbound.JdbcWorkflowStore;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

class PersistenceConfigTest {

    static final Instant FIRST_START = Instant.parse("2026-10-17T10:00:00Z");

    @RegisterExtension
    final TestDatabase db = TestDatabase.h2();

    static PlatformProperties withDemoHistory(boolean on) {
        PlatformProperties props = new PlatformProperties();
        props.getDemo().setSeedHistory(on);
        return props;
    }

    @Test
    void theFirstStartOnADatabaseSeedsTheDemoHistoryAndALaterStartDoesNot() {
        PlatformProperties first = withDemoHistory(true);
        PersistenceConfig.demoHistoryOnce(db.jdbc(), first, Clock.fixed(FIRST_START, ZoneOffset.UTC));
        assertThat(first.getDemo().isSeedHistory()).isTrue();
        assertThat(db.jdbc().seededAt(PersistenceConfig.DEMO_HISTORY)).contains(FIRST_START);

        PlatformProperties second = withDemoHistory(true);
        PersistenceConfig.demoHistoryOnce(new Jdbc(db.dataSource()), second, Clock.fixed(FIRST_START.plusSeconds(60), ZoneOffset.UTC));
        assertThat(second.getDemo().isSeedHistory()).isFalse();
        assertThat(db.jdbc().seededAt(PersistenceConfig.DEMO_HISTORY)).contains(FIRST_START);
    }

    @Test
    void aStartWithoutTheDemoHistoryClaimsNothing() {
        PlatformProperties off = withDemoHistory(false);
        PersistenceConfig.demoHistoryOnce(db.jdbc(), off, Clock.fixed(FIRST_START, ZoneOffset.UTC));
        assertThat(db.jdbc().seededAt(PersistenceConfig.DEMO_HISTORY)).isEmpty();

        PlatformProperties on = withDemoHistory(true);
        PersistenceConfig.demoHistoryOnce(db.jdbc(), on, Clock.fixed(FIRST_START, ZoneOffset.UTC));
        assertThat(on.getDemo().isSeedHistory()).isTrue();
    }

    /**
     * The whole application under {@code postgres} with {@code platform.content.git} on, over a
     * content dir of its own (never this repository, whose checked-out branch the store would
     * commit to): the Git store holds the workflows, read back from the commit it made of the
     * content, and every other store is its JDBC adapter.
     */
    abstract static class GitStoreUnderPostgres {

        @TempDir
        Path dir;

        /** The JDBC URL of the database the application starts on. */
        abstract String url();

        @Test
        void keepsTheWorkflowsInGitAndEveryOtherStoreInTheDatabase() throws IOException {
            Path content = dir.resolve("content");
            Files.createDirectories(content.resolve("workflows/team-compute"));
            Files.writeString(content.resolve("workflows/team-compute/thing-refresh.yaml"), PostgresProfileRestartTest.YAML);
            List<Class<?>> jdbcStores = jdbcStores();
            assertThat(jdbcStores).extracting(Class::getSimpleName)
                    .contains("JdbcWorkflowStore", "JdbcChangeRequests", "JdbcProviderStore", "JdbcProjectionStore", "JdbcEvidenceStore",
                            "JdbcTelemetry", "JdbcQuotaStore", "JdbcInboxStore");

            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(PlatformApplication.class)
                    .profiles("demo", "postgres")
                    .bannerMode(Banner.Mode.OFF)
                    .logStartupInfo(false)
                    .run("--server.port=0",
                            "--spring.datasource.url=" + url(),
                            "--spring.datasource.username=sa",
                            "--spring.datasource.password=",
                            "--platform.content.dir=" + content,
                            "--platform.content.git=true",
                            "--platform.demo.seed-history=false",
                            "--platform.audit.signing-key=test-signing-key")) {
                WorkflowStorePort workflows = ctx.getBean(WorkflowStorePort.class);
                assertThat(workflows).isInstanceOf(GitWorkflowStore.class);
                assertThat(ctx.getBeanNamesForType(JdbcWorkflowStore.class)).isEmpty();
                assertThat(ctx.getBeanNamesForType(InMemoryWorkflowStore.class)).isEmpty();
                assertThat(workflows.published("thing-refresh")).hasValueSatisfying(r -> assertThat(r.path()).isEqualTo("workflows/team-compute/thing-refresh.yaml"));
                assertThat(content.resolve(".git")).isDirectory();

                for (Class<?> store : jdbcStores) {
                    if (store == JdbcWorkflowStore.class) {
                        continue;
                    }
                    Object adapter = ctx.getBean(store);
                    for (Class<?> port : ClassUtils.getAllInterfacesForClassAsSet(store)) {
                        if (port.getName().startsWith("com.iaf.platform.")) {
                            assertThat(ctx.getBean(port)).as(port.getSimpleName()).isSameAs(adapter);
                        }
                    }
                }
            }
        }

        /** The JDBC adapter of every store: the {@code Jdbc*} components of the {@code postgres} profile. */
        static List<Class<?>> jdbcStores() {
            StandardEnvironment postgres = new StandardEnvironment();
            postgres.setActiveProfiles("postgres");
            ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false, postgres);
            scan.addIncludeFilter((reader, factory) -> ClassUtils.getShortName(reader.getClassMetadata().getClassName()).startsWith("Jdbc")
                    && reader.getAnnotationMetadata().hasAnnotation(Profile.class.getName()));
            return scan.findCandidateComponents("com.iaf.platform").stream()
                    .<Class<?>>map(d -> ClassUtils.resolveClassName(d.getBeanClassName(), PersistenceConfigTest.class.getClassLoader()))
                    .toList();
        }
    }

    @Nested
    class GitStoreOnH2 extends GitStoreUnderPostgres {
        @Override
        String url() {
            return "jdbc:h2:file:" + dir.resolve("platform") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = TestDatabase.POSTGRES_URL, matches = ".+")
    class GitStoreOnPostgres extends GitStoreUnderPostgres {
        @RegisterExtension
        final TestDatabase postgres = TestDatabase.postgres();

        @Override
        String url() {
            return postgres.url();
        }
    }
}
