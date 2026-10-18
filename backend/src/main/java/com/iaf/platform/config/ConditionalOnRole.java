package com.iaf.platform.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** A bean only an instance with the given {@link Role} creates ({@code platform.roles}). */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnRole.OnRole.class)
public @interface ConditionalOnRole {

    Role value();

    class OnRole extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnRole.class.getName());
            Role role = (Role) attributes.get("value");
            Set<Role> roles = Role.of(context.getEnvironment());
            ConditionMessage.Builder message = ConditionMessage.forCondition(ConditionalOnRole.class, role);
            return roles.contains(role)
                    ? ConditionOutcome.match(message.found(Role.PROPERTY).items(roles))
                    : ConditionOutcome.noMatch(message.didNotFind("role " + role + " in " + Role.PROPERTY).items(roles));
        }
    }
}
