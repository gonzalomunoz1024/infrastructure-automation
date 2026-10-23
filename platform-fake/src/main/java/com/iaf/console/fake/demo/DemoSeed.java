package com.iaf.console.fake.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Ids;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.content.domain.ProviderDef;
import com.iaf.console.fake.content.domain.RegionDef;
import com.iaf.console.fake.content.domain.ResourceTypeDef;
import com.iaf.console.fake.content.domain.WorkflowDef;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Delegation;
import com.iaf.console.fake.identity.domain.DevUsers;
import com.iaf.console.fake.identity.domain.RequestContext;
import com.iaf.console.fake.identity.domain.Scopes;
import com.iaf.console.fake.runs.application.Jobs;
import com.iaf.console.fake.runs.domain.ApprovalRecord;
import com.iaf.console.fake.runs.domain.Job;
import com.iaf.console.fake.runs.domain.JobStatus;
import com.iaf.console.kit.events.EventKeys;

/**
 * The demo world: the content's publication events, two console accounts with their service
 * accounts, a handful of resources made by running the real workflows with no delay, one job
 * waiting for an approval and one that needs attention.
 */
public final class DemoSeed {

    private static final Logger log = LoggerFactory.getLogger(DemoSeed.class);

    /** The console account team-compute owns. */
    public static final String ACCOUNT_COMPUTE = "123456789012";

    /** The console account team-data owns. */
    public static final String ACCOUNT_DATA = "210987654321";

    /** The id of the console-internal service account. */
    public static final String CONSOLE_INTERNAL = "sa-console-internal";

    private static final String REGION = "global";
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(60);

    /** A console account of the demo and the service account that acts for it. */
    public record ConsoleAccount(String accountId, String team, String serviceAccountId, String token) {
    }

    /** What the demo seeded. */
    public record Result(ConsoleAccount compute, ConsoleAccount data, String internalServiceAccountId, String internalToken, List<String> imageIds,
            List<String> vmIds, List<String> databaseIds, List<String> volumeIds, String environmentId, String waitingApprovalJobId,
            String needsAttentionJobId) {
    }

    private DemoSeed() {
    }

    /** Seeds a fake; the ids and tokens of the service accounts are the same for one seed value. */
    public static Result seed(PlatformFake fake) {
        publishContent(fake);
        Ids seeded = Ids.seeded(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC), fake.seed());
        ConsoleAccount compute = consoleAccount(fake, seeded, ACCOUNT_COMPUTE, "team-compute");
        ConsoleAccount data = consoleAccount(fake, seeded, ACCOUNT_DATA, "team-data");
        String internalToken = seeded.token();
        fake.serviceAccounts().seed(CONSOLE_INTERNAL, "console internal", List.of(), List.of(), new LinkedHashSet<>(Scopes.PRESETS.get("console-internal")),
                null, internalToken);
        log.info("demo service account {} for console account {} ({}): bearer {}", compute.serviceAccountId(), ACCOUNT_COMPUTE, "team-compute",
                compute.token());
        log.info("demo service account {} for console account {} ({}): bearer {}", data.serviceAccountId(), ACCOUNT_DATA, "team-data", data.token());
        log.info("demo service account {} (console-internal): bearer {}", CONSOLE_INTERNAL, internalToken);

        Caller platform = DevUsers.byName("mia");
        Caller computeDeveloper = delegated(fake, compute, "dana", "Dana Reyes", List.of("team-compute", "developer"));
        Caller computeOperator = delegated(fake, compute, "mateo", "Mateo Silva", List.of("team-compute", "ops"));
        Caller dataDeveloper = delegated(fake, data, "ana", "Ana Lima", List.of("team-data", "developer", "dba"));

        List<String> images = new ArrayList<>();
        images.add(output(run(fake, platform, "build-vm-image", input("os", "ubuntu-24.04", "hardening", "cis-l1", "name", "ubuntu-24.04-cis-l1-2026-10-01")),
                "imageId"));
        String deprecated = output(run(fake, platform, "build-vm-image", input("os", "rhel-9", "hardening", "cis-l1", "name", "rhel-9-cis-l1-2026-09-15")),
                "imageId");
        images.add(deprecated);
        fake.registry().find(deprecated).ifPresent(record -> record.status("deprecated", fake.clock().now(), fake.clock().nanos()));
        String image = images.get(0);

        List<String> vms = new ArrayList<>();
        for (String[] vm : new String[][] {{"web-01", "medium", "prod"}, {"api-01", "small", "dev"}, {"web-02", "medium", "prod"},
                {"batch-01", "small", "dev"}, {"cache-01", "medium", "prod"}, {"shop-01", "small", "dev"}}) {
            vms.add(output(run(fake, computeDeveloper, "create-vm",
                    input("name", vm[0], "image", image, "size", vm[1], "environment", vm[2], "owner", "team-compute")), "vmId"));
        }

        List<String> databases = new ArrayList<>();
        databases.add(output(run(fake, dataDeveloper, "create-database", input("name", "orders-db", "environment", "prod", "tier", "M30", "owner", "team-data")),
                "databaseId"));
        databases.add(output(run(fake, dataDeveloper, "create-database", input("name", "billing-db", "environment", "dev", "tier", "M10", "owner", "team-data")),
                "databaseId"));

        List<String> volumes = new ArrayList<>();
        ObjectNode first = input("vmId", vms.get(0), "tier", "premium");
        first.put("sizeGb", 200);
        volumes.add(output(run(fake, computeDeveloper, "request-storage", first), "volumeId"));
        ObjectNode second = input("vmId", vms.get(1), "tier", "standard");
        second.put("sizeGb", 500);
        volumes.add(output(run(fake, computeDeveloper, "request-storage", second), "volumeId"));

        ObjectNode environment = input("name", "shop", "environment", "prod", "image", image);
        environment.put("vmCount", 2);
        Job environmentJob = run(fake, computeDeveloper, "web-environment", environment);
        String environmentId = fake.registry().list(new com.iaf.console.fake.registry.application.Registry.Filter("environment", null, null, null, null, null),
                DevUsers.byName(DevUsers.DEFAULT)).stream().map(r -> r.id()).findFirst().orElse(null);
        if (environmentJob.status() != JobStatus.SUCCEEDED) {
            log.warn("demo environment job {} ended {} ({})", environmentJob.id(), environmentJob.status().id(), environmentJob.errorClass());
        }

        Job waiting = start(fake, dataDeveloper, "mongodb-upgrade",
                input("clusterId", databases.get(0), "targetVersion", "8.0", "maintenanceWindow", "Sat 02:00-04:00 UTC"));
        settle(fake, waiting, false);

        ObjectNode failure = Json.object();
        failure.put("provider", "vsphere");
        failure.put("action", "vm.waitForPowerState");
        failure.putObject("match").put("id", vms.get(4));
        ObjectNode fail = failure.putObject("fail");
        fail.put("class", "timeout");
        fail.put("message", "cache-01 did not reach poweredOn within 600 seconds");
        fail.put("retryable", true);
        fail.put("times", 1);
        fake.world().mutate(failure);
        Job attention = start(fake, computeOperator, "vm-restart", input("vmId", vms.get(4), "reason", "stuck after a kernel update", "environment", "prod"));
        settle(fake, attention, true);

        log.info("demo world ready: {} resources, {} jobs, {} events", fake.registry().size(), fake.jobs().all().size(), fake.events().lastSeq());
        return new Result(compute, data, CONSOLE_INTERNAL, internalToken, List.copyOf(images), List.copyOf(vms), List.copyOf(databases),
                List.copyOf(volumes), environmentId, waiting.id(), attention.id());
    }

    private static void publishContent(PlatformFake fake) {
        Instant now = fake.clock().now();
        for (ResourceTypeDef type : fake.content().types()) {
            ObjectNode data = Json.object();
            data.put("name", type.name());
            data.put("hash", type.hash());
            data.put("version", 1);
            fake.events().emit("resource-type.changed", EventKeys.content("resource-type.changed", type.name(), type.hash()), now, "resource-type",
                    type.name(), "platform", data, null, null, null);
        }
        for (RegionDef region : fake.content().regions()) {
            ObjectNode data = Json.object();
            data.put("name", region.name());
            data.put("hash", region.hash());
            data.put("status", region.document().path("status").asText("available"));
            fake.events().emit("region.changed", EventKeys.content("region.changed", region.name(), region.hash()), now, "region", region.name(),
                    "platform", data, null, null, null);
        }
        for (ProviderDef provider : fake.content().providers()) {
            ObjectNode data = Json.object();
            data.put("name", provider.name());
            data.put("version", provider.version());
            data.put("by", provider.owner());
            data.put("strategy", "all");
            fake.events().emit("provider.published", EventKeys.content("provider.published", provider.name(), provider.version()), now, "provider",
                    provider.name(), "platform", data, null, null, null);
        }
        for (WorkflowDef workflow : fake.content().workflows()) {
            ObjectNode data = Json.object();
            data.put("name", workflow.name());
            data.put("version", workflow.version());
            data.put("strategy", workflow.document().path("rollout").path("strategy").asText("all"));
            data.put("by", workflow.owner());
            fake.events().emit("workflow.published", EventKeys.content("workflow.published", workflow.name(), workflow.version()), now, "workflow",
                    workflow.name(), "platform", data, null, null, null);
        }
    }

    private static ConsoleAccount consoleAccount(PlatformFake fake, Ids seeded, String accountId, String team) {
        String id = "sa-console-" + accountId;
        String token = seeded.token();
        fake.serviceAccounts().seed(id, "console (" + team + ")", List.of(team), List.of(), new LinkedHashSet<>(Scopes.PRESETS.get("console")), accountId,
                token);
        return new ConsoleAccount(accountId, team, id, token);
    }

    private static Caller delegated(PlatformFake fake, ConsoleAccount account, String user, String name, List<String> groups) {
        Caller service = fake.serviceAccounts().byToken(account.token()).orElseThrow(() -> new IllegalStateException("the demo service account is missing"));
        return service.withDelegation(new Delegation(user, name, user + "@example.com", groups, account.accountId(), REGION, "demo-" + user));
    }

    private static Job run(PlatformFake fake, Caller caller, String workflow, ObjectNode input) {
        Job job = start(fake, caller, workflow, input);
        settle(fake, job, true);
        if (job.status() != JobStatus.SUCCEEDED) {
            log.warn("demo job {} ({}) ended {}: {}", job.id(), workflow, job.status().id(), job.errorClass());
        }
        return job;
    }

    private static Job start(PlatformFake fake, Caller caller, String workflow, ObjectNode input) {
        Jobs.StartRequest request = new Jobs.StartRequest(workflow, null, input, "catalog", null, null);
        return fake.jobs().start(caller, RequestContext.NONE, request, "catalog", Duration.ZERO, false);
    }

    /** Waits until a job ends or pauses; with {@code decide}, pending approvals of the job and its children are decided by the right approver. */
    private static void settle(PlatformFake fake, Job job, boolean decide) {
        Instant deadline = Instant.now().plus(SETTLE_TIMEOUT);
        Caller admin = DevUsers.byName(DevUsers.DEFAULT);
        while (!job.finished() && Instant.now().isBefore(deadline)) {
            JobStatus status = job.status();
            if (status == JobStatus.NEEDS_ATTENTION || (status == JobStatus.WAITING_APPROVAL && !decide)) {
                return;
            }
            if (decide) {
                Set<String> family = family(fake, job.id());
                for (ApprovalRecord approval : fake.jobs().approvals("pending", admin)) {
                    if (family.contains(approval.jobId())) {
                        fake.jobs().decideApproval(approval, true, approver(approval.group()), "approved for the demo");
                    }
                }
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static Set<String> family(PlatformFake fake, String jobId) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(jobId);
        for (Job child : fake.jobs().children(jobId)) {
            ids.addAll(family(fake, child.id()));
        }
        return ids;
    }

    private static Caller approver(String group) {
        for (String name : new String[] {"mia", "raj"}) {
            Caller candidate = DevUsers.byName(name);
            if (candidate.inGroup(group)) {
                return candidate;
            }
        }
        return DevUsers.byName(DevUsers.DEFAULT);
    }

    private static String output(Job job, String field) {
        ObjectNode output = job.output();
        String value = output == null ? null : output.path(field).asText(null);
        if (value == null) {
            throw new IllegalStateException("demo job " + job.id() + " (" + job.workflow() + ") ended " + job.status().id() + " without " + field
                    + (job.errorClass() == null ? "" : ": " + job.errorClass()));
        }
        return value;
    }

    private static ObjectNode input(String... pairs) {
        ObjectNode out = Json.object();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    /** The labels the seed never sets, kept for readers of the code: a seed runs without a console request. */
    static Map<String, String> noLabels() {
        return Map.of();
    }

    static Instant at(FakeClock clock) {
        return clock.now();
    }
}
