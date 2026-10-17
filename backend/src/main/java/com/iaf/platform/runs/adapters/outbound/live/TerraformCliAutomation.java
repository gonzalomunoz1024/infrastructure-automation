package com.iaf.platform.runs.adapters.outbound.live;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Mono;

/**
 * Production {@code call: terraform}: {@code terraform init/plan/apply/destroy} on the module
 * {@code module.source@version} with {@code vars}, one state per job (or per the
 * {@code workspace} the step names) in the configured backend, {@code -json} output parsed into
 * {@code { add, change, destroy, outputs }}. Plan answers the preview.
 *
 * <p>TODO: wire the CLI (binary path, backend config, provider credentials from
 * {@code CredentialsPort}) and run it on {@code Schedulers.boundedElastic()} with the step's
 * timeout; until then the live profile fails terraform steps loudly.</p>
 */
@Component
@Profile("live")
@Order(0)
public class TerraformCliAutomation implements AutomationRunnerPort {

    @Override
    public boolean supports(StepType type) {
        return type == StepType.TERRAFORM;
    }

    @Override
    public Mono<JsonNode> run(RunRequest request) {
        return Mono.error(new UnsupportedOperationException(
                "the terraform CLI is not wired yet (profile live): module "
                        + (request.with() == null ? "?" : request.with().path("module").toString())
                        + " cannot be applied. Configure the binary and the state backend, then implement TerraformCliAutomation."));
    }
}
