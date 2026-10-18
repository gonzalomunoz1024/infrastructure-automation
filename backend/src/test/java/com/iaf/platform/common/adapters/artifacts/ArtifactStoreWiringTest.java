package com.iaf.platform.common.adapters.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.ArtifactsProperties;

/** The filesystem store by default; the S3 store with the s3 profile, which needs a bucket. */
class ArtifactStoreWiringTest {

    @TempDir
    Path tmp;

    ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ArtifactsProperties.class, FilesystemArtifactStore.class, S3ArtifactStore.class)
                .withBean(CredentialsPort.class, () -> mock(CredentialsPort.class))
                .withPropertyValues("platform.artifacts.dir=" + tmp.resolve("artifacts"));
    }

    @Test
    void theFilesystemStoreIsTheDefault() {
        context().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ArtifactStorePort.class)).isInstanceOf(FilesystemArtifactStore.class);
            assertThat(ctx).doesNotHaveBean(S3ArtifactStore.class);
        });
    }

    @Test
    void theS3ProfileSwitchesToTheBucket() {
        context().withPropertyValues("spring.profiles.active=s3", "platform.artifacts.s3.bucket=platform-artifacts",
                "platform.artifacts.s3.endpoint=http://127.0.0.1:9", "platform.artifacts.s3.credential=vault:s3/platform").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(ArtifactStorePort.class)).isInstanceOf(S3ArtifactStore.class);
                });
        context().withPropertyValues("spring.profiles.active=s3").run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().rootCause().hasMessage("platform.artifacts.s3.bucket is required with the s3 profile"));
    }
}
