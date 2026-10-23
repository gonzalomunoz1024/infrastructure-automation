package com.iaf.console.bff.session.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.session.domain.CsrfTokens;
import com.iaf.console.bff.session.domain.LoginTicketCodec;
import com.iaf.console.bff.session.domain.OrganizationAdmins;
import com.iaf.console.bff.session.domain.StepUpPolicy;
import com.iaf.console.bff.session.ports.outbound.IdentityProviderPort;
import com.iaf.console.bff.session.ports.outbound.PrincipalResolverPort;
import com.iaf.console.bff.session.ports.outbound.RegionsPort;
import com.iaf.console.bff.session.ports.outbound.SessionStore;

/** Wires the session services; the secret is generated when none is configured (one instance only). */
@Configuration(proxyBeanMethods = false)
public class SessionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SessionConfiguration.class);

    @Bean
    public SessionSecret sessionSecret(SessionProperties properties) {
        if (properties.secret() == null || properties.secret().isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            log.warn("console.session.secret is not set: CSRF tokens and login tickets use a secret generated for this run; sessions do not survive a restart and a second instance cannot read them");
            return new SessionSecret(generated);
        }
        byte[] bytes = Base64.getDecoder().decode(properties.secret().trim());
        if (bytes.length < 32) {
            throw new IllegalArgumentException("console.session.secret must be at least 32 bytes, base64");
        }
        return new SessionSecret(bytes);
    }

    @Bean
    public CsrfTokens csrfTokens(SessionSecret secret) {
        return new CsrfTokens(secret.bytes());
    }

    @Bean
    public LoginTicketCodec loginTicketCodec(SessionSecret secret) {
        return new LoginTicketCodec(secret.bytes());
    }

    @Bean
    public StepUpPolicy stepUpPolicy(StepUpProperties properties) {
        return new StepUpPolicy(properties.maxAge(), properties.actions());
    }

    @Bean
    public OrganizationAdmins organizationAdmins(OrganizationProperties properties) {
        return new OrganizationAdmins(properties.adminGroups());
    }

    @Bean
    public Login login(IdentityProviderPort provider, PrincipalResolverPort resolver, SessionStore sessions, LoginTicketCodec tickets,
            SessionProperties properties, OrganizationAdmins organizationAdmins, Clock clock) {
        return new Login(provider, resolver, sessions, tickets, properties, organizationAdmins, clock);
    }

    @Bean
    public MembershipChanges membershipChanges(SessionProperties properties, Clock clock) {
        return new MembershipChanges(properties.rolesRefreshInterval(), clock);
    }

    @Bean
    public Sessions sessions(SessionStore store, CsrfTokens csrf, StepUpPolicy stepUp, RegionsPort regions, PrincipalResolverPort resolver,
            MembershipChanges changes, SessionProperties properties, Clock clock) {
        return new Sessions(store, csrf, stepUp, regions, resolver, changes, properties, clock);
    }

    /** The server's secret, held as a bean so the two codecs share it. */
    public record SessionSecret(byte[] bytes) {
    }
}
