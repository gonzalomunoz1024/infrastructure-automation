package com.iaf.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.platform.notifications.adapters.inbound.RestControllerNotificationAdapter;
import com.iaf.platform.notifications.ports.inbound.NotificationCommands;
import com.iaf.platform.notifications.ports.inbound.NotificationQueries;
import com.iaf.platform.playbooks.adapters.inbound.RestControllerPlaybookAdapter;
import com.iaf.platform.playbooks.ports.inbound.PlaybookCommands;
import com.iaf.platform.playbooks.ports.inbound.PlaybookQueries;
import com.iaf.platform.providers.adapters.inbound.RestControllerProviderAdapter;
import com.iaf.platform.providers.ports.inbound.ProviderCommands;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.releases.adapters.inbound.RestControllerReleaseAdapter;
import com.iaf.platform.releases.ports.inbound.ReleaseCommands;
import com.iaf.platform.releases.ports.inbound.ReleaseQueries;

/**
 * docs/architecture.md: {@code RestController<Slice>Adapter} implements the slice's inbound
 * ports. Read off the class path, so a new slice is held to it too: every REST adapter
 * implements at least one port of its slice, and every endpoint it serves that a port of the
 * slice declares (same name and parameters) is served through that port, not beside it.
 */
class InboundAdaptersImplementPortsTest {

    static final String ADAPTERS = "classpath*:com/iaf/platform/*/adapters/inbound/*.class";

    @Test
    void everyRestAdapterImplementsTheInboundPortsItServes() throws IOException {
        List<Class<?>> adapters = classes(ADAPTERS).stream()
                .filter(c -> c.getEnclosingClass() == null && c.isAnnotationPresent(RestController.class))
                .filter(c -> c.getSimpleName().matches("RestController\\w+Adapter"))
                .toList();
        assertThat(adapters).extracting(Class::getSimpleName).contains("RestControllerProviderAdapter", "RestControllerReleaseAdapter",
                "RestControllerNotificationAdapter", "RestControllerPlaybookAdapter", "RestControllerRunsAdapter", "RestControllerCatalogAdapter");

        SoftAssertions softly = new SoftAssertions();
        for (Class<?> adapter : adapters) {
            String slice = adapter.getPackageName().replace(".adapters.inbound", "");
            List<Class<?>> ports = classes("classpath*:" + slice.replace('.', '/') + "/ports/inbound/*.class").stream()
                    .filter(c -> c.isInterface() && !c.isAnnotation() && c.getEnclosingClass() == null)
                    .toList();
            Set<Class<?>> implemented = ClassUtils.getAllInterfacesForClassAsSet(adapter).stream().filter(ports::contains).collect(Collectors.toSet());
            softly.assertThat(implemented).as("%s implements an inbound port of %s", adapter.getSimpleName(), slice).isNotEmpty();
            for (Method endpoint : adapter.getMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(endpoint, RequestMapping.class)) {
                    continue;
                }
                for (Class<?> port : ports) {
                    if (declares(port, endpoint)) {
                        softly.assertThat(implemented).as("%s.%s serves %s.%s, so the adapter implements that port",
                                adapter.getSimpleName(), endpoint.getName(), port.getSimpleName(), endpoint.getName()).contains(port);
                    }
                }
            }
        }
        softly.assertAll();
    }

    @Test
    void theFourAdaptersThatServedAPortWithoutImplementingItNowDo() {
        assertThat(RestControllerProviderAdapter.class).isAssignableTo(ProviderCommands.class).isAssignableTo(ProviderQueries.class);
        assertThat(RestControllerReleaseAdapter.class).isAssignableTo(ReleaseCommands.class).isAssignableTo(ReleaseQueries.class);
        assertThat(RestControllerNotificationAdapter.class).isAssignableTo(NotificationQueries.class).isAssignableTo(NotificationCommands.class);
        assertThat(RestControllerPlaybookAdapter.class).isAssignableTo(PlaybookCommands.class).isAssignableTo(PlaybookQueries.class);
    }

    /** True when the port has a method of the same name and parameter types. */
    static boolean declares(Class<?> port, Method endpoint) {
        return Arrays.stream(port.getMethods())
                .anyMatch(m -> m.getName().equals(endpoint.getName()) && Arrays.equals(m.getParameterTypes(), endpoint.getParameterTypes()));
    }

    static List<Class<?>> classes(String pattern) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> out = new ArrayList<>();
        for (Resource r : resolver.getResources(pattern)) {
            String name = readers.getMetadataReader(r).getClassMetadata().getClassName();
            try {
                out.add(ClassUtils.forName(name, InboundAdaptersImplementPortsTest.class.getClassLoader()));
            } catch (ClassNotFoundException | LinkageError e) {
                throw new IllegalStateException("cannot load " + name, e);
            }
        }
        return out;
    }
}
