package com.iaf.platform.audit.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.config.PlatformProperties;

/** The evidence store a profile picks: in memory by default, write-once files with {@code worm}. */
class EvidenceStoreProfilesTest {

    @Configuration
    @ComponentScan(basePackageClasses = InMemoryEvidenceStore.class)
    static class Stores {
    }

    @TempDir
    Path dir;

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(Stores.class)
                .withBean(PlatformProperties.class, PlatformProperties::new)
                .withPropertyValues("platform.audit.worm.dir=" + dir);
    }

    @Test
    void withoutAProfileEvidenceStaysInMemory() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EvidenceStore.class)).isInstanceOf(InMemoryEvidenceStore.class);
            assertThat(ctx).doesNotHaveBean(FileSystemEvidenceStore.class);
        });
    }

    @Test
    void theWormProfileWritesEvidenceToTheConfiguredFolder() {
        runner().withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("worm")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EvidenceStore.class)).isInstanceOf(FileSystemEvidenceStore.class);
            assertThat(ctx.getBean(FileSystemEvidenceStore.class).objects().describe()).contains(dir.toString());
        });
    }
}
