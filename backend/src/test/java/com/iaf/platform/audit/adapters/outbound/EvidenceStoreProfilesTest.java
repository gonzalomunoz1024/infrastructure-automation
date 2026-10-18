package com.iaf.platform.audit.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
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
import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.config.PlatformProperties;

/**
 * The evidence store a profile picks: a write-once store wins when {@code worm} or {@code worm-s3}
 * is active, else {@code mongo} keeps the evidence in the database, else it stays in memory. Under
 * {@code mongo} the in-memory store is not even created. {@code live} has no say in it. No MongoDB
 * is needed: the MongoDB store only creates its indexes when it is built, here on a mock.
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
                .withBean(Mongo.class, () -> Mockito.mock(Mongo.class))
                .withPropertyValues("platform.audit.worm.dir=" + dir)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profiles));
    }

    @Test
    void withoutAProfileEvidenceStaysInMemory() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EvidenceStore.class)).isInstanceOf(InMemoryEvidenceStore.class);
            assertThat(ctx).doesNotHaveBean(FileSystemEvidenceStore.class).doesNotHaveBean(MongoEvidenceStore.class);
        });
    }

    @Test
    void theMongoProfileKeepsEvidenceInTheDatabaseLiveOrNot() {
        for (String[] profiles : new String[][] {{"mongo"}, {"mongo", "live"}}) {
            String named = String.join("+", profiles);
            runner(profiles).run(ctx -> {
                assertThat(ctx).as(named).hasNotFailed();
                assertThat(ctx.getBean(EvidenceStore.class)).as(named).isInstanceOf(MongoEvidenceStore.class);
                assertThat(ctx.getBeansOfType(EvidenceStore.class)).as(named).hasSize(1);
                assertThat(ctx).as(named).doesNotHaveBean(FileSystemEvidenceStore.class).doesNotHaveBean(InMemoryEvidenceStore.class);
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
    void aWormProfileWinsOverMongo() {
        runner("mongo", "worm").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EvidenceStore.class)).isInstanceOf(FileSystemEvidenceStore.class);
            assertThat(ctx.getBeansOfType(EvidenceStore.class)).hasSize(1);
            assertThat(ctx).doesNotHaveBean(MongoEvidenceStore.class).doesNotHaveBean(InMemoryEvidenceStore.class);
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
        assertThat(primaryStores("mongo", "worm-s3")).containsExactly("S3ObjectLockEvidenceStore");
        assertThat(primaryStores("mongo", "live", "worm-s3")).containsExactly("S3ObjectLockEvidenceStore");
        assertThat(primaryStores("mongo", "worm")).containsExactly("FileSystemEvidenceStore");
        assertThat(primaryStores("mongo", "live")).containsExactly("MongoEvidenceStore");
        assertThat(primaryStores("mongo")).containsExactly("MongoEvidenceStore");
        assertThat(primaryStores("live")).isEmpty();
        assertThat(primaryStores()).isEmpty();
        assertThat(stores("mongo", "worm-s3")).as("under mongo nothing stays in memory").containsExactly("S3ObjectLockEvidenceStore");
        assertThat(stores("mongo", "worm")).containsExactly("FileSystemEvidenceStore");
        assertThat(stores("mongo")).containsExactly("MongoEvidenceStore");
        assertThat(stores()).containsExactly("InMemoryEvidenceStore");
    }

    /** The {@code @Primary} evidence stores the component scan admits under these profiles. */
    static Set<String> primaryStores(String... profiles) {
        return scanned(profiles).stream()
                .filter(c -> c.isAnnotationPresent(Primary.class))
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());
    }

    /** Every evidence store the component scan admits under these profiles, primary or not. */
    static Set<String> stores(String... profiles) {
        return scanned(profiles).stream().map(Class::getSimpleName).collect(Collectors.toSet());
    }

    static Set<Class<?>> scanned(String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(profiles);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true, env);
        return scanner.findCandidateComponents(InMemoryEvidenceStore.class.getPackageName()).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(EvidenceStoreProfilesTest::load)
                .filter(EvidenceStore.class::isAssignableFrom)
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
