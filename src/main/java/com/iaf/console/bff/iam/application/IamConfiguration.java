package com.iaf.console.bff.iam.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.identity.application.IdentityClient;
import com.iaf.console.bff.session.application.MembershipChanges;

/** Wires the IAM pass-through. */
@Configuration(proxyBeanMethods = false)
public class IamConfiguration {

    @Bean
    public IamProxy iamProxy(IdentityClient identity, Access access, MembershipChanges changes, ObjectMapper json) {
        return new IamProxy(identity, access, changes, json);
    }
}
