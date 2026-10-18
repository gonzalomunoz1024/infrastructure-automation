package com.iaf.platform.audit.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.iaf.platform.audit.application.SiemExporter;
import com.iaf.platform.audit.application.SiemProperties;
import com.iaf.platform.audit.ports.outbound.SiemPort;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.PlatformProperties;

/** The siem profile turns on each SIEM adapter whose destination is configured, and only those. */
class SiemProfilesTest {

    @Configuration
    @ComponentScan(basePackageClasses = SplunkHecSiem.class)
    @Import({SiemExporter.class, SiemProperties.class})
    static class Siem {
    }

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, JacksonAutoConfiguration.class))
                .withUserConfiguration(Siem.class)
                .withBean(PlatformProperties.class, PlatformProperties::new)
                .withBean(CredentialsPort.class, () -> mock(CredentialsPort.class));
    }

    @Test
    void withoutTheProfileNothingIsExported() {
        runner().withPropertyValues("platform.siem.hec.url=http://127.0.0.1:1/services/collector/event", "platform.siem.hec.token=vault:siem/hec")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(SiemPort.class);
                    assertThat(ctx.getBean(SiemExporter.class).enabled()).isFalse();
                });
    }

    @Test
    void theProfileWiresTheConfiguredSiemsIntoTheExporter() {
        runner().withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("siem"))
                .withPropertyValues("platform.siem.hec.url=http://127.0.0.1:1/services/collector/event", "platform.siem.hec.token=vault:siem/hec")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(SplunkHecSiem.class);
                    assertThat(ctx).doesNotHaveBean(SyslogSiem.class);
                    assertThat(ctx.getBean(SiemExporter.class).enabled()).isTrue();
                });
    }

    @Test
    void bothSiemsCanRunSideBySide() {
        runner().withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("siem"))
                .withPropertyValues("platform.siem.hec.url=http://127.0.0.1:1/services/collector/event", "platform.siem.hec.token=vault:siem/hec",
                        "platform.siem.syslog.host=127.0.0.1", "platform.siem.syslog.protocol=udp")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeansOfType(SiemPort.class)).hasSize(2);
                });
    }
}
