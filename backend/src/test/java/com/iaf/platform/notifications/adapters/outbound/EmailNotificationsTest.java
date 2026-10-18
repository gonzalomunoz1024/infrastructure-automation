package com.iaf.platform.notifications.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.identity.ports.outbound.UserDirectory;
import com.iaf.platform.notifications.application.NotificationProperties;
import com.iaf.platform.notifications.domain.Notification;

import reactor.core.publisher.Mono;

class EmailNotificationsTest {

    final NotificationProperties props = new NotificationProperties();
    final CredentialsPort credentials = mock(CredentialsPort.class);
    final UserDirectory directory = id -> "ana".equals(id)
            ? Optional.of(new Identity("ana", "Ana", "ana@example.com", List.of(), List.of(), false, Identity.Kind.USER))
            : Optional.empty();

    /** A one-connection SMTP server that accepts one message and keeps its envelope and data. */
    static final class Smtp implements AutoCloseable {
        final ServerSocket server;
        final List<String> recipients = new CopyOnWriteArrayList<>();
        final StringBuilder data = new StringBuilder();
        final CompletableFuture<Void> done;

        Smtp() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            done = CompletableFuture.runAsync(this::serve);
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try (Socket s = server.accept();
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)) {
                reply(out, "220 localhost ESMTP test");
                String line;
                while ((line = in.readLine()) != null) {
                    String command = line.toUpperCase(Locale.ROOT);
                    if (command.startsWith("EHLO") || command.startsWith("HELO")) {
                        reply(out, "250 localhost");
                    } else if (command.startsWith("MAIL FROM")) {
                        reply(out, "250 OK");
                    } else if (command.startsWith("RCPT TO")) {
                        recipients.add(line.substring(line.indexOf('<') + 1, line.indexOf('>')));
                        reply(out, "250 OK");
                    } else if (command.equals("DATA")) {
                        reply(out, "354 End data with <CR><LF>.<CR><LF>");
                        while (!".".equals(line = in.readLine())) {
                            data.append(line).append('\n');
                        }
                        reply(out, "250 OK queued");
                    } else if (command.equals("QUIT")) {
                        reply(out, "221 Bye");
                        return;
                    } else {
                        reply(out, "250 OK");
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void reply(Writer out, String line) throws IOException {
            out.write(line + "\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    static JavaMailSenderImpl sender(int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        Properties p = new Properties();
        p.put("mail.smtp.localhost", "localhost");
        p.put("mail.smtp.connectiontimeout", "5000");
        p.put("mail.smtp.timeout", "5000");
        sender.setJavaMailProperties(p);
        return sender;
    }

    EmailNotifications email(JavaMailSender sender) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (sender != null) {
            beans.addBean("sender", sender);
        }
        beans.addBean("directory", directory);
        return new EmailNotifications(beans.getBeanProvider(JavaMailSender.class), props, beans.getBeanProvider(UserDirectory.class), credentials);
    }

    @Test
    void mailsTheUsersTheTeamsMailboxAndTheChannelsListOverSmtp() throws Exception {
        NotificationProperties.Team approvers = new NotificationProperties.Team();
        approvers.setEmail("data-approvers@example.com");
        props.getTeams().put("data-approvers", approvers);
        NotificationProperties.Channel dataOps = new NotificationProperties.Channel();
        dataOps.setEmail(List.of("data-ops@example.com", "ana@example.com"));
        props.getChannels().put("data-ops", dataOps);
        props.setLinkBase("https://iaf.example/#");
        props.getMail().setFrom("iaf@example.com");
        Notification n = Notification.builder().id("ntf_1").kind(Notification.APPROVAL_REQUESTED).title("Approval needed: create-database")
                .message("Job job_1 waits for data-approvers.").link("/jobs/job_1").channel("#data-ops").users(List.of("ana", "ghost"))
                .teams(List.of("data-approvers")).build();

        try (Smtp smtp = new Smtp()) {
            assertThat(email(sender(smtp.port())).deliver(n).block(Duration.ofSeconds(10))).isTrue();
            smtp.done.get(10, TimeUnit.SECONDS);

            assertThat(smtp.recipients).containsExactly("ana@example.com", "data-approvers@example.com", "data-ops@example.com");
            String message = smtp.data.toString();
            assertThat(message).contains("Subject: [IAF] Approval needed: create-database");
            assertThat(message).contains("From: iaf@example.com");
            assertThat(message).contains("Job job_1 waits for data-approvers.");
            assertThat(message).contains("Open in the platform: https://iaf.example/#/jobs/job_1");
        }
    }

    @Test
    void aNotificationWithNoKnownAddressIsNotMailed() {
        JavaMailSender sender = mock(JavaMailSender.class);
        Notification n = Notification.builder().id("ntf_2").kind(Notification.NOTIFY).title("x").users(List.of("ghost")).teams(List.of("nobody"))
                .build();

        assertThat(email(sender).deliver(n).block(Duration.ofSeconds(5))).isFalse();
    }

    @Test
    void theSmtpPasswordComesFromVaultOnFirstUse() {
        props.getMail().setPassword("vault:mail/smtp");
        when(credentials.resolve("vault:mail/smtp")).thenReturn(Mono.just("smtp-pw"));
        JavaMailSenderImpl sender = new JavaMailSenderImpl() {
            @Override
            public void send(org.springframework.mail.SimpleMailMessage... messages) {
                // delivered nowhere: this test is about the password
            }
        };
        Notification n = Notification.builder().id("ntf_3").kind(Notification.NOTIFY).title("x").users(List.of("ana")).build();

        assertThat(email(sender).deliver(n).block(Duration.ofSeconds(5))).isTrue();
        assertThat(sender.getPassword()).isEqualTo("smtp-pw");
    }

    @Test
    void theProfileNeedsAMailServerAndAReferenceForThePassword() {
        assertThatThrownBy(() -> email(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.mail.host");

        props.getMail().setPassword("plain-password");
        assertThatThrownBy(() -> email(mock(JavaMailSender.class))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vault:").hasMessageNotContaining("plain-password");
    }
}
