package com.iaf.platform.common.expressions;

/**
 * A data reference found in a template, used by the checks: {@code $json.vm.id} is
 * {@code (JSON, null, "vm.id")}; {@code $('Check VM exists').json.vm.id} is
 * {@code (STEP, "Check VM exists", "vm.id")}; {@code $input.vmId} is {@code (INPUT, null, "vmId")}.
 */
public record Reference(Kind kind, String step, String path) {

    public enum Kind {
        JSON, STEP, INPUT, ALIAS, VARS, JOB, ENV, ERROR
    }
}
