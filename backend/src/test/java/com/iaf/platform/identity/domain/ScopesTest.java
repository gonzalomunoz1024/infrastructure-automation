package com.iaf.platform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;

/** Which scope each endpoint needs, what a set of scopes allows, and how an account's scopes are chosen. */
class ScopesTest {

    static String scope(String method, String path) {
        return Scopes.required(method, path, false);
    }

    @Test
    void readsOfTheRegistryAndCatalogNeedRegistryRead() {
        assertThat(scope("GET", "/api/v1/registry/providers")).isEqualTo(Scopes.REGISTRY_READ);
        assertThat(scope("GET", "/api/v1/registry/workflows/search")).isEqualTo(Scopes.REGISTRY_READ);
        assertThat(scope("GET", "/api/v1/workflows/vm-restart/versions/1.2.0")).isEqualTo(Scopes.REGISTRY_READ);
        assertThat(scope("GET", "/api/v1/providers/vsphere")).isEqualTo(Scopes.REGISTRY_READ);
        assertThat(scope("GET", "/api/v1/resources/res-1/lineage")).isEqualTo(Scopes.REGISTRY_READ);
        assertThat(scope("GET", "/openapi.json")).isEqualTo(Scopes.REGISTRY_READ);
    }

    @Test
    void checksDryRunsWritesJobsAndProvidersEachHaveTheirScope() {
        assertThat(scope("POST", "/api/v1/workflows:validate")).isEqualTo(Scopes.WORKFLOWS_VALIDATE);
        assertThat(scope("POST", "/api/v1/providers:validate")).isEqualTo(Scopes.WORKFLOWS_VALIDATE);
        assertThat(scope("POST", "/api/v1/workflows:dry-run")).isEqualTo(Scopes.WORKFLOWS_DRY_RUN);
        assertThat(scope("PUT", "/api/v1/workflows/vm-restart/draft")).isEqualTo(Scopes.WORKFLOWS_WRITE);
        assertThat(scope("POST", "/api/v1/workflows/vm-restart:publish")).isEqualTo(Scopes.WORKFLOWS_WRITE);
        assertThat(scope("POST", "/api/v1/change-requests/cr_1:merge")).isEqualTo(Scopes.WORKFLOWS_WRITE);
        assertThat(scope("POST", "/api/v1/jobs")).isEqualTo(Scopes.JOBS_START);
        assertThat(scope("POST", "/api/v1/jobs/job_1:cancel")).isEqualTo(Scopes.JOBS_START);
        assertThat(scope("POST", "/api/v1/triggers/alerts")).isEqualTo(Scopes.JOBS_START);
        assertThat(scope("GET", "/api/v1/jobs/job_1/events")).isEqualTo(Scopes.JOBS_READ);
        assertThat(scope("GET", "/ws/jobs/job_1")).isEqualTo(Scopes.JOBS_READ);
        assertThat(scope("GET", "/api/v1/audit")).isEqualTo(Scopes.JOBS_READ);
        assertThat(scope("GET", "/api/v1/audit/platform")).isEqualTo(Scopes.JOBS_READ);
        assertThat(scope("GET", "/api/v1/audit/job_1:verify")).isEqualTo(Scopes.JOBS_READ);
        assertThat(scope("GET", "/api/v1/audit/shared/tok_1")).as("the link's token is the credential").isEqualTo(Scopes.ANY);
        assertThat(scope("POST", "/api/v1/providers:publish")).isEqualTo(Scopes.PROVIDERS_WRITE);
        assertThat(scope("POST", "/api/v1/providers/vsphere:refresh-spec")).isEqualTo(Scopes.PROVIDERS_WRITE);
        assertThat(scope("PUT", "/api/v1/providers/vsphere/slo")).isEqualTo(Scopes.PROVIDERS_WRITE);
        // listing credentials (names, types, owners; never a value) is a read, storing one a write
        assertThat(scope("GET", "/api/v1/credentials")).isEqualTo(Scopes.REGISTRY_READ);
        assertThat(scope("POST", "/api/v1/credentials")).isEqualTo(Scopes.PROVIDERS_WRITE);
    }

    @Test
    void anythingNoRuleNamesNeedsAdminAndEveryVersionIsJudgedAlike() {
        assertThat(scope("GET", "/api/v1/service-accounts")).isEqualTo(Scopes.ADMIN);
        assertThat(scope("POST", "/api/v1/assistant/playbooks:sync")).isEqualTo(Scopes.ADMIN);
        assertThat(scope("POST", "/api/v1/approvals/apr_1:approve")).isEqualTo(Scopes.ADMIN);
        assertThat(scope("POST", "/api/v1/releases/rel_1:promote")).isEqualTo(Scopes.ADMIN);
        assertThat(scope("POST", "/api/v1/audit:seal")).isEqualTo(Scopes.ADMIN);
        assertThat(scope("POST", "/api/v1/audit/job_1:share")).isEqualTo(Scopes.ADMIN);
        assertThat(scope("POST", "/api/v1/audit:export-pack")).isEqualTo(Scopes.ADMIN);
        assertThat(scope("POST", "/api/v1/audit/shared/tok_1")).isEqualTo(Scopes.ADMIN);
        assertThat(scope("DELETE", "/api/v1/jobs")).isEqualTo(Scopes.ADMIN);
        assertThat(scope("GET", "/")).isEqualTo(Scopes.ADMIN);
        assertThat(scope("POST", "/api/v2/workflows:validate")).isEqualTo(Scopes.WORKFLOWS_VALIDATE);
        assertThat(scope("GET", "/api/v1/me")).isEqualTo(Scopes.ANY);
        assertThat(scope("POST", "/api/v1/mcp")).isEqualTo(Scopes.ANY);
        assertThat(scope("GET", "/actuator/health")).isEqualTo(Scopes.ANY);
        assertThat(Scopes.required("POST", "/api/v1/vms/vm-1:restart", true)).isEqualTo(Scopes.ANY);
        assertThat(Scopes.required("POST", "/api/v1/vms/vm-1:restart", false)).isEqualTo(Scopes.ADMIN);
    }

    @Test
    void adminAllowsEverythingAndAnAccountOnlyItsOwnScopes() {
        List<String> assistant = Scopes.PRESETS.get("assistant");
        assertThat(assistant).containsExactly(Scopes.REGISTRY_READ, Scopes.WORKFLOWS_VALIDATE, Scopes.WORKFLOWS_DRY_RUN);
        assertThat(Scopes.allows(assistant, Scopes.WORKFLOWS_DRY_RUN)).isTrue();
        assertThat(Scopes.allows(assistant, Scopes.WORKFLOWS_WRITE)).isFalse();
        assertThat(Scopes.allows(assistant, Scopes.JOBS_START)).isFalse();
        assertThat(Scopes.allows(assistant, Scopes.ANY)).isTrue();
        assertThat(Scopes.allows(List.of(Scopes.ADMIN), Scopes.PROVIDERS_WRITE)).isTrue();
        assertThatThrownBy(() -> Scopes.check(assistant, Scopes.JOBS_START, "start vm-restart"))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("needs scope jobs:start");
        Scopes.check(null, Scopes.ADMIN, "anything");   // a person: no scope limits
    }

    @Test
    void anAccountsScopesAreCheckedPresetOrDefault() {
        assertThat(Scopes.resolve(null, null)).isEqualTo(Scopes.DEFAULT);
        assertThat(Scopes.resolve(List.of(), "assistant")).containsExactly(Scopes.REGISTRY_READ, Scopes.WORKFLOWS_VALIDATE, Scopes.WORKFLOWS_DRY_RUN);
        assertThat(Scopes.resolve(List.of("jobs:read", " jobs:read", "registry:read"), null)).containsExactly(Scopes.JOBS_READ, Scopes.REGISTRY_READ);
        assertThat(Scopes.resolve(List.of("registry:read"), "assistant")).containsExactly(Scopes.REGISTRY_READ);
        assertThatThrownBy(() -> Scopes.resolve(List.of("jobs:everything"), null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("jobs:everything");
        assertThatThrownBy(() -> Scopes.resolve(List.of("workflows:write"), "assistant")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("assistant");
        assertThatThrownBy(() -> Scopes.resolve(null, "root")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("preset");
    }

    @Test
    void anAccountStoredWithoutScopesHasTheDefaultsAndTheAdminScopeMakesAnAdmin() {
        ServiceAccount legacy = new ServiceAccount("sa_1", "old", List.of(), List.of(), "hash", Instant.EPOCH);
        assertThat(legacy.grantedScopes()).isEqualTo(Scopes.DEFAULT);
        assertThat(legacy.identity().admin()).isFalse();
        assertThat(legacy.identity().kind()).isEqualTo(Identity.Kind.SERVICE);
        ServiceAccount root = new ServiceAccount("sa_2", "root", List.of(), List.of(), "hash", Instant.EPOCH, List.of(Scopes.ADMIN));
        assertThat(root.identity().admin()).isTrue();
        assertThat(root.withoutSecret().tokenHash()).isNull();
        assertThat(root.withoutSecret().scopes()).containsExactly(Scopes.ADMIN);
    }
}
