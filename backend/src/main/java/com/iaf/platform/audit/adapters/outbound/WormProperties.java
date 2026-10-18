package com.iaf.platform.audit.adapters.outbound;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * {@code platform.audit.worm.*}: where the write-once evidence stores keep their objects. The
 * {@code worm} profile writes read-only files under {@code dir}; the {@code worm-s3} profile writes
 * S3 Object Lock objects to {@code s3.bucket}. How long objects are kept is
 * {@code platform.audit.retention-years}.
 */
@Data
@Configuration
@ConfigurationProperties("platform.audit.worm")
public class WormProperties {

    /** Profile worm: the evidence folder (relative paths resolve against the working directory). */
    private String dir = "data/evidence";

    private S3 s3 = new S3();

    @Data
    public static class S3 {
        /** Profile worm-s3: a bucket created with Object Lock enabled; the store refuses to start otherwise. */
        private String bucket = "";
        /** Key prefix inside the bucket, such as {@code iaf/}; blank for the bucket root. */
        private String prefix = "";
        /** Blank: the SDK's region chain (AWS_REGION, the profile, instance metadata). */
        private String region = "";
        /** Blank: AWS. Set for an S3-compatible store with Object Lock. */
        private String endpoint = "";
        private boolean pathStyle = false;
    }
}
