package com.iaf.platform.common.redaction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.ports.CredentialsPort;

/**
 * Wraps every {@link CredentialsPort} bean (the in-memory vault, Vault, whatever comes next) in a
 * {@link RecordingCredentials}, so each secret the platform resolves is known to the redactor.
 */
@Component
public class CredentialsRecordingPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<SecretValues> secrets;

    public CredentialsRecordingPostProcessor(ObjectProvider<SecretValues> secrets) {
        this.secrets = secrets;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof CredentialsPort port && !(bean instanceof RecordingCredentials)) {
            return new RecordingCredentials(port, secrets.getObject());
        }
        return bean;
    }
}
