package com.iaf.platform.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.adapters.credentials.InMemoryCredentials;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.adapters.outbound.InMemoryProviderStore;
import com.iaf.platform.providers.domain.ActionInfo;
import com.iaf.platform.providers.domain.Health;
import com.iaf.platform.providers.domain.ProviderChecks;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderHealthRule;
import com.iaf.platform.providers.domain.ProviderSummary;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;

import reactor.test.StepVerifier;

/** The repo's own sample providers must parse, pass the checks and seed the demo adapters: they are what the demo shows. */
class SampleProvidersTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static PlatformProperties props() {
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir(ROOT.toString());
        return props;
    }

    @Test
    void everySampleProviderParsesAndPassesTheChecksWithoutAProblem() {
        List<ContentFiles.ContentFile> files = ContentFiles.ofKind(ROOT, "Provider");
        assertThat(files).hasSizeGreaterThanOrEqualTo(8);
        StringBuilder report = new StringBuilder();
        for (var f : files) {
            ProviderDefinition def = ProviderYaml.parse(f.yaml());
            assertThat(def.name()).isNotBlank();
            ProviderChecks.Result r = ProviderChecks.check(f.yaml());
            for (Problem p : r.problems()) {
                report.append(f.relative(ROOT)).append(": ").append(p.level().json()).append(' ').append(p.code())
                        .append(p.step() == null ? "" : " @" + p.step()).append(p.field() == null ? "" : "." + p.field())
                        .append(" - ").append(p.message()).append('\n');
            }
            assertThat(r.checks()).extracting(c -> c.status()).doesNotContain("failed");
        }
        assertThat(report.toString()).as("problems in sample providers").isEmpty();
    }

    @Test
    void healthIsUnknownWithoutTelemetry() {
        for (var f : ContentFiles.ofKind(ROOT, "Provider")) {
            ProviderDefinition def = ProviderYaml.parse(f.yaml());
            Health h = ProviderHealthRule.judge(List.of(), def.slo(), NOW);
            assertThat(h.status()).as(def.name()).isEqualTo(Health.Status.UNKNOWN);
            assertThat(h.calls()).isZero();
            assertThat(h.errorTrend()).hasSize(14);
            ProviderSummary s = ProviderSummary.of(def, h);
            assertThat(s.actionCount()).isGreaterThan(0);
            assertThat(s.alerts().channel()).startsWith("#");
        }
    }

    @Test
    void oneSampleActionCarriesAnSloOfItsOwnSoTheViewHasOneToShow() {
        ProviderDefinition vsphere = ProviderYaml.parse(ContentFiles.ofKind(ROOT, "Provider").stream()
                .filter(f -> f.yaml().contains("name: vsphere")).findFirst().orElseThrow().yaml());
        var wait = vsphere.action("vm.waitForPowerState").orElseThrow();
        assertThat(wait.slo()).as("a poll is allowed to be slower than a plain read").isEqualTo(new ProviderDefinition.Slo(97.0, 2500));
        assertThat(vsphere.sloOf(wait)).isEqualTo(wait.slo());
        assertThat(vsphere.sloOf(vsphere.action("vm.get").orElseThrow())).isEqualTo(vsphere.slo());
        assertThat(vsphere.actions().stream().filter(a -> a.slo() != null).count()).isEqualTo(1);
    }

    @Test
    void theStoreSeedsEveryProviderFromTheContentDir() {
        InMemoryProviderStore store = new InMemoryProviderStore(props(), CLOCK);
        store.seed();
        assertThat(store.all()).extracting(p -> p.definition().name())
                .containsExactly("backup", "dns-records", "idp-tiles", "image-builder", "ip-pool", "mongodb", "ops-playbooks", "terraform-modules", "vault",
                        "vsphere");
        StoredProvider vsphere = store.get("vsphere").orElseThrow();
        assertThat(vsphere.path()).isEqualTo("providers/team-compute/vsphere.yaml");
        assertThat(vsphere.updatedBy()).isEqualTo("content");
        assertThat(vsphere.yaml()).contains("kind: Provider");
        assertThat(vsphere.definition().actions()).hasSize(11);
        assertThat(store.get("nope")).isEmpty();
    }

    @Test
    void theVaultSeedsEveryReferenceOfTheSamplesWithoutExposingValues() {
        InMemoryCredentials vault = new InMemoryCredentials(props(), CLOCK);
        vault.seed();
        StepVerifier.create(vault.list())
                .assertNext(list -> {
                    assertThat(list).extracting(CredentialInfo::name).contains(
                            "vsphere/token", "mongodb/ops-manager", "backup/api-key", "idp/token", "image-builder/token",
                            "platform/vault-token", "terraform/platform", "ssh/ops-automation", "ssh/dba-automation");
                    assertThat(list).extracting(CredentialInfo::name).noneMatch(n -> n.startsWith("databases/"));
                    assertThat(list).extracting(CredentialInfo::path).allMatch(p -> p.startsWith("vault:"));
                    assertThat(list).extracting(CredentialInfo::owner).as("seeded credentials belong to no team: anyone may use them").containsOnlyNulls();
                    assertThat(type(list, "vsphere/token")).isEqualTo("token");
                    assertThat(type(list, "mongodb/ops-manager")).isEqualTo("basic");
                    assertThat(type(list, "backup/api-key")).isEqualTo("api-key");
                    assertThat(type(list, "ssh/ops-automation")).isEqualTo("ssh-key");
                })
                .verifyComplete();
        StepVerifier.create(vault.resolve("vault:vsphere/token")).assertNext(v -> assertThat(v).isNotBlank()).verifyComplete();
        StepVerifier.create(vault.resolve("vault:nope/nothing")).verifyComplete();
        assertThat(vault.has("vault:ssh/dba-automation")).isTrue();
    }

    static String type(List<CredentialInfo> list, String name) {
        return list.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow().type();
    }

    @Test
    void actionInfoMarksTheOverriddenActionsOfTheSamples() {
        ProviderDefinition vsphere = ProviderYaml.parse(ContentFiles.ofKind(ROOT, "Provider").stream()
                .filter(f -> f.path().endsWith("vsphere.yaml")).findFirst().orElseThrow().yaml());
        ActionInfo get = ActionInfo.of(vsphere, vsphere.action("vm.get").orElseThrow());
        assertThat(get.overridden()).isTrue();
        assertThat(get.output().path("properties").path("powerState").path("type").asText()).isEqualTo("string");
        assertThat(get.operation()).isEqualTo("getVm");
        assertThat(get.sample()).isNotNull();
        ActionInfo delete = ActionInfo.of(vsphere, vsphere.action("vm.delete").orElseThrow());
        assertThat(delete.overridden()).isFalse();
        assertThat(delete.approval()).isEqualTo("required");
        assertThat(delete.plan()).isEqualTo("vm.get");
    }
}
