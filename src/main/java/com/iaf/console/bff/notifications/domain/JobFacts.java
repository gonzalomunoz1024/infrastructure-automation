package com.iaf.console.bff.notifications.domain;

/**
 * What a job's {@code job.started} said that its later events do not repeat: its title, its
 * workflow and, for a row of a batch, its parent. Rows of a batch notify nobody; their batch does.
 */
public record JobFacts(String title, String workflow, String parentId) {

    public boolean topLevel() {
        return parentId == null || parentId.isBlank();
    }
}
