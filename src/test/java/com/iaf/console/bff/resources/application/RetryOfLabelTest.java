package com.iaf.console.bff.resources.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.jobs.application.JobRetries;
import com.iaf.console.platform.client.PlatformHeaders;
import com.iaf.console.platform.client.RequestOptions;

class RetryOfLabelTest {

    @Test
    void aRerunNamesTheFailedJobItRetries() {
        RequestOptions options = RequestOptions.none().withLabel("change", "chg-1");
        RequestOptions rerun = Resources.retryOf(options, "job_1");
        assertEquals(Map.of("change", "chg-1", JobRetries.RETRY_OF_LABEL, "job_1"), rerun.labels());
    }

    @Test
    void aRetryOfARetryNamesTheLatestFailure() {
        RequestOptions options = RequestOptions.none().withLabel(JobRetries.RETRY_OF_LABEL, "job_1");
        assertEquals("job_2", Resources.retryOf(options, "job_2").labels().get(JobRetries.RETRY_OF_LABEL));
    }

    @Test
    void aJobWithAllTheLabelsThePlatformTakesKeepsThem() {
        Map<String, String> full = new LinkedHashMap<>();
        for (int i = 0; i < PlatformHeaders.MAX_LABELS; i++) {
            full.put("label-" + i, "v");
        }
        RequestOptions options = new RequestOptions(null, null, null, full, null, null, null, null);
        assertSame(options, Resources.retryOf(options, "job_1"));
    }
}
