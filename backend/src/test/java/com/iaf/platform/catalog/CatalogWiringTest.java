package com.iaf.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.WebFilter;

import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.catalog.adapters.inbound.GeneratedRouteWebFilter;
import com.iaf.platform.catalog.adapters.inbound.RestControllerCatalogAdapter;
import com.iaf.platform.catalog.application.CatalogQueryHandler;
import com.iaf.platform.catalog.application.McpServer;
import com.iaf.platform.catalog.ports.inbound.CatalogQueries;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.resources.adapters.inbound.RestControllerResourceAdapter;
import com.iaf.platform.resources.adapters.outbound.InMemoryResourceStore;
import com.iaf.platform.resources.application.ResourceCommandHandler;
import com.iaf.platform.resources.application.ResourceQueryHandler;
import com.iaf.platform.resources.ports.inbound.ResourceCommands;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

/**
 * The two slices wire as beans: the adapters and the handlers both implement the inbound ports,
 * and injecting a port must resolve to the handler. The other slices' ports are mocked.
 */
class CatalogWiringTest {

    @Configuration
    @ComponentScan(basePackageClasses = {CatalogQueryHandler.class, GeneratedRouteWebFilter.class, RestControllerCatalogAdapter.class,
            ResourceQueryHandler.class, RestControllerResourceAdapter.class, InMemoryResourceStore.class})
    static class Slices {
    }

    @Test
    void theSlicesWireAndPortsResolveToTheHandlers() {
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir(SampleCatalog.ROOT.toString());
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(Slices.class)
                .withBean(PlatformProperties.class, () -> props)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(EventBus.class, () -> new EventBus(Clock.systemUTC()))
                .withBean(WorkflowQueries.class, () -> mock(WorkflowQueries.class))
                .withBean(RunsCommands.class, () -> mock(RunsCommands.class))
                .withBean(RunsQueries.class, () -> mock(RunsQueries.class))
                .withBean(AssistantCommands.class, () -> mock(AssistantCommands.class))
                .withBean(PolicyPort.class, () -> mock(PolicyPort.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(GeneratedRouteWebFilter.class);
                    assertThat(ctx).hasSingleBean(McpServer.class);
                    assertThat(ctx).hasSingleBean(RestControllerCatalogAdapter.class);
                    assertThat(ctx).hasSingleBean(RestControllerResourceAdapter.class);
                    assertThat(ctx.getBean(CatalogQueries.class)).isInstanceOf(CatalogQueryHandler.class);
                    assertThat(ctx.getBean(ResourceQueries.class)).isInstanceOf(ResourceQueryHandler.class);
                    assertThat(ctx.getBean(ResourceCommands.class)).isInstanceOf(ResourceCommandHandler.class);
                    assertThat(ctx.getBeansOfType(WebFilter.class).values()).anyMatch(f -> f instanceof GeneratedRouteWebFilter);
                    InMemoryResourceStore store = ctx.getBean(InMemoryResourceStore.class);
                    assertThat(store.types()).hasSize(5);
                    assertThat(store.resources()).isNotEmpty();
                });
    }
}
