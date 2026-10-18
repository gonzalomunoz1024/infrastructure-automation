package com.iaf.platform.observability.adapters.outbound;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.tracing.Traces;
import com.iaf.platform.observability.application.JobTracing;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCall;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;

import io.micrometer.observation.Observation;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Every {@link ProviderCallPort} bean (the recorded demo adapter, the HTTP one), proxied so each
 * call is an {@value #SPAN} span: under the step making it on the calling thread (else the job's
 * span), outside a job under the observation in the Reactor context (a "Try it" inside a request).
 * The span is the current observation downstream, so the HTTP adapter's WebClient hangs its client
 * span under it and sends its {@code traceparent} to the provider. A failure records the error
 * class from the provider's errors table, never the provider's message.
 */
@Component
public class TracedProviderCalls implements BeanPostProcessor {

    public static final String SPAN = "iaf.provider.call";

    private final ObjectProvider<Traces> traces;
    private final ObjectProvider<JobTracing> jobs;

    public TracedProviderCalls(ObjectProvider<Traces> traces, ObjectProvider<JobTracing> jobs) {
        this.traces = traces;
        this.jobs = jobs;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof ProviderCallPort)) {
            return bean;
        }
        Interceptor interceptor = new Interceptor(this);
        if (bean instanceof Advised advised && !advised.isFrozen()) {
            advised.addAdvice(interceptor);
            return bean;
        }
        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(subclassable(bean.getClass()));
        factory.addAdvice(interceptor);
        return factory.getProxy(bean.getClass().getClassLoader());
    }

    /** A class proxy keeps the bean injectable by its own class; a final or generated class gets an interface proxy. */
    private static boolean subclassable(Class<?> type) {
        return !Modifier.isFinal(type.getModifiers()) && !Proxy.isProxyClass(type) && !type.isHidden() && !type.isSynthetic()
                && !type.isAnonymousClass() && !type.isRecord();
    }

    /** {@code delegate}'s answer to {@code call}, as a span. */
    public Mono<JsonNode> traced(ProviderCall call, Mono<JsonNode> delegate) {
        Traces t = traces.getIfAvailable(Traces::off);
        JobTracing j = jobs.getIfAvailable();
        Observation step = j == null || call == null ? null : j.parentOf(call.jobId());
        return Mono.deferContextual(ctx -> {
            Observation span = t.start(SPAN, name(call), step != null ? step : Traces.current(ctx), low(call), high(call));
            return delegate
                    .doOnError(e -> Traces.failed(span, e instanceof ProviderCallException p ? p.errorClass() : e.getClass().getSimpleName()))
                    .contextWrite(c -> Traces.with(c, span))
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            span.lowCardinalityKeyValue(Traces.ERROR_CLASS, "cancelled");
                        } else if (signal != SignalType.ON_ERROR) {
                            span.lowCardinalityKeyValue(Traces.ERROR_CLASS, Traces.NONE);
                        }
                        span.stop();
                    });
        });
    }

    private static String name(ProviderCall call) {
        return "call " + provider(call) + "." + action(call);
    }

    private static Map<String, String> low(ProviderCall call) {
        Map<String, String> low = new LinkedHashMap<>();
        low.put("provider", provider(call));
        low.put("action", action(call));
        low.put("environment", call == null || call.environment() == null ? Traces.NONE : call.environment());
        low.put("dry.run", String.valueOf(call != null && call.dryRun()));
        return low;
    }

    private static Map<String, String> high(ProviderCall call) {
        Map<String, String> high = new LinkedHashMap<>();
        high.put("job.id", call == null ? null : call.jobId());
        return high;
    }

    private static String provider(ProviderCall call) {
        return call == null || call.provider() == null ? "unknown" : call.provider().name();
    }

    private static String action(ProviderCall call) {
        return call == null || call.action() == null ? "unknown" : call.action().id();
    }

    /** Wraps {@code call(ProviderCall)}; every other method goes straight through. */
    static final class Interceptor implements MethodInterceptor {

        private final TracedProviderCalls calls;

        Interceptor(TracedProviderCalls calls) {
            this.calls = calls;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Object[] args = invocation.getArguments();
            if (!"call".equals(invocation.getMethod().getName()) || args.length != 1 || !(args[0] instanceof ProviderCall call)) {
                return invocation.proceed();
            }
            Object answer = invocation.proceed();
            return answer instanceof Mono<?> mono ? calls.traced(call, (Mono<JsonNode>) mono) : answer;
        }
    }
}
