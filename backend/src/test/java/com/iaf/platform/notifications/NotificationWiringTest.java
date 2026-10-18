package com.iaf.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.identity.ports.outbound.UserDirectory;
import com.iaf.platform.notifications.adapters.inbound.RestControllerNotificationAdapter;
import com.iaf.platform.notifications.adapters.outbound.EmailNotifications;
import com.iaf.platform.notifications.adapters.outbound.SlackWebhookNotifications;
import com.iaf.platform.notifications.application.NotificationHandler;
import com.iaf.platform.notifications.ports.inbound.NotificationCommands;
import com.iaf.platform.notifications.ports.inbound.NotificationQueries;
import com.iaf.platform.notifications.ports.outbound.NotificationPort;

/** The slice wires as beans; the inbox is always on, chat and email join it with their profiles. */
class NotificationWiringTest {

    @Configuration
    @ComponentScan(basePackageClasses = NotificationHandler.class, basePackages = "com.iaf.platform.notifications")
    static class Slice {
    }

    ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, JacksonAutoConfiguration.class,
                        MailSenderAutoConfiguration.class))
                .withUserConfiguration(Slice.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(EventBus.class, () -> new EventBus(Clock.systemUTC()))
                .withBean(CredentialsPort.class, () -> mock(CredentialsPort.class))
                .withBean(UserDirectory.class, () -> id -> Optional.empty());
    }

    @Test
    void byDefaultOnlyTheInboxDeliversAndThePortsResolveToTheHandler() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(NotificationPort.class).values()).extracting(NotificationPort::name).containsExactly("inbox");
            assertThat(ctx.getBean(NotificationCommands.class)).isInstanceOf(NotificationHandler.class);
            assertThat(ctx.getBean(NotificationQueries.class)).isInstanceOf(NotificationHandler.class);
            assertThat(ctx).hasSingleBean(RestControllerNotificationAdapter.class);
        });
    }

    @Test
    void theSlackAndMailProfilesAddTheirDeliveries() {
        runner().withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("slack", "mail"))
                .withPropertyValues("spring.mail.host=127.0.0.1", "platform.notifications.channels.vm-ops.webhook=vault:slack/vm-ops")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(SlackWebhookNotifications.class);
                    assertThat(ctx).hasSingleBean(EmailNotifications.class);
                    assertThat(ctx.getBeansOfType(NotificationPort.class)).hasSize(3);
                });
    }
}
