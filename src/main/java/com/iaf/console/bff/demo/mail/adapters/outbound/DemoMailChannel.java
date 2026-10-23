package com.iaf.console.bff.demo.mail.adapters.outbound;

import java.time.Clock;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.common.web.WebProperties;
import com.iaf.console.bff.demo.mail.application.DemoMailbox;
import com.iaf.console.bff.demo.mail.domain.DemoMail;
import com.iaf.console.bff.notifications.domain.Channels;
import com.iaf.console.bff.notifications.domain.Mails;
import com.iaf.console.bff.notifications.domain.Notification;
import com.iaf.console.bff.notifications.domain.Recipient;
import com.iaf.console.bff.notifications.ports.outbound.NotificationChannel;

import reactor.core.publisher.Mono;

/** The email channel of the demo: the email SMTP would send, kept in the demo's mailbox instead. */
@Component
@Profile("demo")
public class DemoMailChannel implements NotificationChannel {

    private final DemoMailbox mailbox;
    private final WebProperties web;
    private final Clock clock;

    public DemoMailChannel(DemoMailbox mailbox, WebProperties web, Clock clock) {
        this.mailbox = mailbox;
        this.web = web;
        this.clock = clock;
    }

    @Override
    public String id() {
        return Channels.EMAIL;
    }

    @Override
    public Mono<Void> deliver(Recipient recipient, Notification notification) {
        return Mono.fromRunnable(() -> {
            Mails.Mail mail = Mails.of(recipient, notification, web.baseUrl());
            mailbox.add(new DemoMail(mail.to(), mail.subject(), mail.text(), mail.link(), notification.id(), notification.kind(), notification.accountId(),
                    clock.instant()));
        });
    }
}
