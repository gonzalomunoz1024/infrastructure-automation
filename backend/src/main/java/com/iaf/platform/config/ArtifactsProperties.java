package com.iaf.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/** {@code platform.artifacts}: the content-addressed store of uploads, plans and other run artifacts. */
@Data
@Configuration
@ConfigurationProperties(prefix = "platform.artifacts")
public class ArtifactsProperties {

    /** Root of the filesystem store, the default adapter. */
    private String dir = System.getProperty("java.io.tmpdir") + "/iaf-platform/artifacts";
    /** The bucket the {@code s3} profile writes to. */
    private S3 s3 = new S3();

    @Data
    public static class S3 {
        private String bucket = "";
        /** Key prefix inside the bucket; objects live under {@code <prefix>sha256/<aa>/<digest>}. */
        private String prefix = "artifacts/";
        /** Blank for AWS; the URL of MinIO, Ceph or any other S3-compatible store otherwise. */
        private String endpoint = "";
        private String region = "us-east-1";
        /** Path-style addressing ({@code <endpoint>/<bucket>/<key>}), which most S3-compatible stores need. */
        private boolean pathStyle = true;
        /** A {@code vault:<path>} holding {@code <access key id>:<secret access key>}; blank uses the AWS default credentials chain. */
        private String credential = "";
    }
}
