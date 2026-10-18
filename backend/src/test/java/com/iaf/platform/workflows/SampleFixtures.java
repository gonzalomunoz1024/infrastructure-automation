package com.iaf.platform.workflows;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The repo's sample content and Mockito stand-ins for the other slices' ports, as the workflows tests need them. */
public final class SampleFixtures {

    public static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    public static final DryRunResult DRY_RUN_PASSED = new DryRunResult(true, List.of(), List.of(), null);

    private SampleFixtures() {
    }

    public static List<ProviderDefinition> providers() {
        return ContentFiles.ofKind(ROOT, ProviderDefinition.KIND).stream().map(f -> ProviderYaml.parse(f.yaml())).toList();
    }

    public static List<ResourceTypeDefinition> resourceTypes() {
        return ContentFiles.ofKind(ROOT, ResourceTypeDefinition.KIND).stream().map(f -> ResourceTypeDefinition.parse(f.yaml())).toList();
    }

    public static String yaml(String relativePath) {
        try {
            return Files.readString(ROOT.resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ProviderQueries providerQueries() {
        List<ProviderDefinition> all = providers();
        ProviderQueries q = mock(ProviderQueries.class);
        when(q.all()).thenReturn(Flux.fromIterable(all));
        when(q.provider(anyString())).thenAnswer(inv -> Mono.justOrEmpty(all.stream().filter(p -> p.name().equals(inv.getArgument(0))).findFirst()));
        return q;
    }

    public static ResourceQueries resourceQueries() {
        List<ResourceTypeDefinition> all = resourceTypes();
        ResourceQueries q = mock(ResourceQueries.class);
        when(q.types()).thenReturn(Flux.fromIterable(all));
        when(q.type(anyString())).thenAnswer(inv -> Mono.justOrEmpty(all.stream().filter(t -> t.name().equals(inv.getArgument(0))).findFirst()));
        return q;
    }

    public static CredentialsPort credentials() {
        CredentialsPort c = mock(CredentialsPort.class);
        Instant at = Instant.parse("2026-10-01T00:00:00Z");
        when(c.list()).thenReturn(Mono.just(List.of(
                new CredentialsPort.CredentialInfo("vsphere/token", "token", "vault:vsphere/token", null, at),
                new CredentialsPort.CredentialInfo("ssh/ops-automation", "ssh-key", "vault:ssh/ops-automation", null, at))));
        return c;
    }

    public static RunsCommands runs() {
        RunsCommands r = mock(RunsCommands.class);
        when(r.dryRun(any(), any(), any())).thenReturn(Mono.just(DRY_RUN_PASSED));
        return r;
    }

    public static RunsQueries runsQueries() {
        RunsQueries r = mock(RunsQueries.class);
        when(r.outcomes(any(), any(), any())).thenReturn(Mono.just(List.of()));
        when(r.steps(any())).thenReturn(Mono.just(List.of()));
        return r;
    }

    public static PolicyPort policy() {
        PolicyPort p = mock(PolicyPort.class);
        when(p.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        when(p.engine()).thenReturn("mock");
        return p;
    }
}
