package com.iaf.platform.runs.adapters.outbound;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * {@code platform.change.*}: the change records jobs open when their workflow says
 * {@code policy.change.required}. Every change carries a link back to the job's evidence
 * ({@code evidence-url}, {@code {jobId}} replaced); {@code servicenow} is the live adapter's
 * instance (profiles {@code servicenow} and {@code live}). Secrets are references only.
 */
@Data
@Configuration
@ConfigurationProperties("platform.change")
public class ChangeProperties {

    /** The job's evidence page, {@code {jobId}} replaced: the link each change carries. */
    private String evidenceUrl = "http://localhost:3000/audit/{jobId}";

    private ServiceNow servicenow = new ServiceNow();

    /** The evidence link for one job. */
    public String evidenceUrlOf(String jobId) {
        if (evidenceUrl == null || evidenceUrl.isBlank() || jobId == null) {
            return null;
        }
        return evidenceUrl.replace("{jobId}", jobId);
    }

    @Data
    public static class ServiceNow {
        /** The instance, like https://firm.service-now.com. */
        private String instance = "";
        /** A vault: reference: {@code user:password} for basic auth, the token for bearer. */
        private String credential = "";
        /** basic or bearer */
        private String auth = "basic";
        /** The change type: normal, standard or emergency. */
        private String type = "normal";
        private String assignmentGroup = "";
        private String category = "";
        /** The state a new change is created in; blank = the instance's default (-1 is Implement). */
        private String openState = "";
        /** The state a change is closed with (3 is Closed). */
        private String closeState = "3";
        /** The field that carries the evidence link: work_notes, or a custom field such as u_evidence_url. */
        private String evidenceField = "work_notes";
        private Duration timeout = Duration.ofSeconds(10);
    }
}
