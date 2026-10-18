package com.iaf.platform.audit.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.StandardEnvironment;

import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.config.PlatformProperties;

/**
 * The evidence store a profile picks: a write-once store wins when {@code worm} or {@code worm-s3}
 * is active, else {@code postgres} keeps the evidence in the database, else it stays in memory.
 * {@code live} has no say in it.
 */
class EvidenceStoreProfilesTest {

    @Configuration
    @ComponentScan(basePackageClasses = InMemoryEvidenceStore.class)
    static class Stores {
    }

    @TempDir
    Path dir;

    ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(Stores.class)
                .withBean(PlatformProperties.class, PlatformProperties::new)
                .withBean(Jdbc.class, () -> new Jdbc(TestDatabase.h2Named("evidence-profiles")))
                .withPropertyValues("platform.audit.worm.dir=" + dir)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profiles));
    }

    @Test
    void withoutAProfileEvidenceStaysInMemory() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EvidenceStore.class)).isInstanceOf(InMemoryEvidenceStore.class);
            assertThat(ctx).doesNotHaveBean(FileSystemEvidenceStore.class).doesNotHaveBean(JdbcEvidenceStore.class);
        });
    }

    @Test
    void thePostgresProfileKeepsEvidenceInTheDatabaseLiveOrNot() {
        for (String[] profiles : new String[][] {{"postgres"}, {"postgres", "live"}}) {
            String named = String.join("+", profiles);
            runner(profiles).run(ctx -> {
                assertThat(ctx).as(named).hasNotFailed();
                assertThat(ctx.getBean(EvidenceStore.class)).as(named).isInstanceOf(JdbcEvidenceStore.class);
                assertThat(ctx).as(named).doesNotHaveBean(FileSystemEvidenceStore.class);
            });
        }
    }

    @Test
    void theWormProfileWritesEvidenceToTheConfiguredFolder() {
        runner("worm").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EvidenceStore.class)).isInstanceOf(FileSystemEvidenceStore.class);
            assertThat(ctx.getBean(FileSystemEvidenceStore.class).objects().describe()).contains(dir.toString());
        });
    }

    @Test
    void aWormProfileWinsOverPostgres() {
        runner("postgres", "worm").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EvidenceStore.class)).isInstanceOf(FileSystemEvidenceStore.class);
            assertThat(ctx).doesNotHaveBean(JdbcEvidenceStore.class);
        });
    }

    /**
     * {@code worm-s3} needs a bucket with Object Lock before its store exists, so its choice is
     * read off the component scan, which admits or refuses each store by its profiles without
     * creating any: the same rule the context tests above exercise, for every combination.
     */
    @Test
    void theS3ProfilePicksTheObjectLockStoreWhateverElseIsOn() {
        assertThat(primaryStores("worm-s3")).containsExactly("S3ObjectLockEvidenceStore");
        assertThat(primaryStores("postgres", "worm-s3")).containsExactly("S3ObjectLockEvidenceStore");
        assertThat(primaryStores("postgres", "live", "worm-s3")).containsExactly("S3ObjectLockEvidenceStore");
        assertThat(primaryStores("postgres", "worm")).containsExactly("FileSystemEvidenceStore");
        assertThat(primaryStores("postgres", "live")).containsExactly("JdbcEvidenceStore");
        assertThat(primaryStores("postgres")).containsExactly("JdbcEvidenceStore");
        assertThat(primaryStores("live")).isEmpty();
        assertThat(primaryStores()).isEmpty();
    }

    /** The {@code @Primary} evidence stores the component scan admits under these profiles. */
    static Set<String> primaryStores(String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(profiles);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true, env);
        return scanner.findCandidateComponents(InMemoryEvidenceStore.class.getPackageName()).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(EvidenceStoreProfilesTest::load)
                .filter(EvidenceStore.class::isAssignableFrom)
                .filter(c -> c.isAnnotationPresent(Primary.class))
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());
    }

    static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
