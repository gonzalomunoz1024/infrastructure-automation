package com.iaf.console.bff.notifications.adapters.outbound;

import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.common.web.WebProperties;
import com.iaf.console.bff.notifications.application.NotificationsProperties;
import com.iaf.console.bff.notifications.domain.Channels;
import com.iaf.console.bff.notifications.domain.Mails;
import com.iaf.console.bff.notifications.domain.Notification;
import com.iaf.console.bff.notifications.domain.Recipient;
import com.iaf.console.bff.notifications.ports.outbound.NotificationChannel;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Notification emails over SMTP ({@code console.notifications.email.*}), outside the demo profile
 * and only when a host is set: plain text, from the configured sender to the person's
 * identity-provider address, with the absolute link into the console. The sender is built here
 * rather than as a bean, so the health endpoint does not ask the mail server.
 */
@Component
@Profile("!demo")
@ConditionalOnExpression("'${console.notifications.email.host:}' != ''")
public class SmtpEmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailChannel.class);
    static final int TIMEOUT_MILLIS = 10_000;

    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final String from;
    private final WebProperties web;

    public SmtpEmailChannel(NotificationsProperties properties, WebProperties web) {
        NotificationsProperties.Email email = properties.email();
        sender.setHost(email.host());
        sender.setPort(email.port());
        if (email.username() != null && !email.username().isBlank()) {
            sender.setUsername(email.username());
            sender.setPassword(email.password());
        }
        Properties smtp = sender.getJavaMailProperties();
        smtp.put("mail.transport.protocol", "smtp");
        smtp.put("mail.smtp.auth", String.valueOf(email.username() != null && !email.username().isBlank()));
        smtp.put("mail.smtp.starttls.enable", String.valueOf(email.starttls()));
        smtp.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MILLIS));
        smtp.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MILLIS));
        smtp.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MILLIS));
        this.from = email.from();
        this.web = web;
        log.info("notification emails go through {}:{} from {}", email.host(), email.port(), email.from());
    }

    @Override
    public String id() {
        return Channels.EMAIL;
    }

    @Override
    public Mono<Void> deliver(Recipient recipient, Notification notification) {
        return Mono.fromRunnable(() -> {
            Mails.Mail mail = Mails.of(recipient, notification, web.baseUrl());
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(mail.to());
            message.setSubject(mail.subject());
            message.setText(mail.text());
            sender.send(message);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
