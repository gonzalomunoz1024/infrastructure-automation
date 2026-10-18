package com.iaf.platform.notifications.adapters.outbound;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.identity.ports.outbound.UserDirectory;
import com.iaf.platform.notifications.application.NotificationProperties;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.outbound.NotificationPort;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Email delivery (profile {@code mail}, over {@code spring-boot-starter-mail}; the server is
 * {@code spring.mail.host}/{@code port}/{@code username}). A notification goes to the addressed
 * users' own addresses (from the user directory), the teams' mailboxes
 * ({@code platform.notifications.teams.<team>.email}) and the channel's list
 * ({@code platform.notifications.channels.<name>.email}). The SMTP password is a {@code vault:}
 * reference ({@code platform.notifications.mail.password}) handed to the mail sender on first use.
 */
@Component
@Profile("mail")
@Primary
public class EmailNotifications implements NotificationPort {

    private final JavaMailSender sender;
    private final NotificationProperties props;
    private final ObjectProvider<UserDirectory> users;
    private final CredentialsPort credentials;
    private volatile boolean passwordSet;

    public EmailNotifications(ObjectProvider<JavaMailSender> senders, NotificationProperties props, ObjectProvider<UserDirectory> users,
            CredentialsPort credentials) {
        JavaMailSender s = senders.getIfAvailable();
        if (s == null) {
            throw new IllegalStateException("the mail profile needs a mail server: set spring.mail.host (and port, username)");
        }
        String password = props.getMail().getPassword();
        if (password != null && !password.isBlank() && !password.startsWith("vault:")) {
            throw new IllegalArgumentException("platform.notifications.mail.password must be a vault: reference, never the password itself");
        }
        this.sender = s;
        this.props = props;
        this.users = users;
        this.credentials = credentials;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public Mono<Boolean> deliver(Notification n) {
        Set<String> to = recipients(n);
        if (to.isEmpty()) {
            return Mono.just(false);
        }
        return password().then(Mono.fromCallable(() -> {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(props.getMail().getFrom());
            mail.setTo(to.toArray(String[]::new));
            mail.setSubject(subject(n));
            mail.setText(body(n));
            sender.send(mail);
            return true;
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** The users' own addresses, the teams' mailboxes and the channel's list, each once. */
    Set<String> recipients(Notification n) {
        Set<String> to = new LinkedHashSet<>();
        UserDirectory directory = users.getIfAvailable();
        if (directory != null) {
            for (String user : n.users()) {
                directory.byId(user).map(Identity::email).filter(EmailNotifications::address).ifPresent(to::add);
            }
        }
        for (String team : n.teams()) {
            props.team(team).map(NotificationProperties.Team::getEmail).filter(EmailNotifications::address).ifPresent(to::add);
        }
        props.channel(n.channel()).ifPresent(c -> c.getEmail().stream().filter(EmailNotifications::address).forEach(to::add));
        return to;
    }

    String subject(Notification n) {
        String prefix = props.getMail().getSubjectPrefix();
        String severity = "info".equals(n.severity()) ? "" : "[" + n.severity() + "] ";
        return (prefix == null || prefix.isBlank() ? "" : prefix + " ") + severity + (n.title() == null ? n.kind() : n.title());
    }

    String body(Notification n) {
        StringBuilder b = new StringBuilder();
        if (n.message() != null) {
            b.append(n.message()).append("\n");
        }
        Optional.ofNullable(props.absolute(n.link())).filter(l -> !l.isBlank()).ifPresent(l -> b.append("\nOpen in the platform: ").append(l).append('\n'));
        if (n.channel() != null) {
            b.append("\nSent to ").append(n.channel()).append('\n');
        }
        return b.toString();
    }

    /** Resolves the SMTP password once and hands it to the sender; nothing to do without a reference. */
    private Mono<Void> password() {
        String ref = props.getMail().getPassword();
        if (passwordSet || ref == null || ref.isBlank() || !(sender instanceof JavaMailSenderImpl impl)) {
            return Mono.empty();
        }
        return credentials.resolve(ref)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("the mail password reference " + ref + " resolves to nothing")))
                .doOnNext(secret -> {
                    impl.setPassword(secret);
                    passwordSet = true;
                })
                .then();
    }

    private static boolean address(String value) {
        return value != null && !value.isBlank() && value.contains("@");
    }
}
