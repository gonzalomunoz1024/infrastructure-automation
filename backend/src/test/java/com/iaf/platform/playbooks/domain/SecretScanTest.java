package com.iaf.platform.playbooks.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.playbooks.domain.SecretScan.Finding;

class SecretScanTest {

    /** Fixtures are assembled at run time, so the repository's own secret scan never matches them. */
    static final String PRIVATE_KEY = "PRIVATE" + " KEY";
    static final String KEY_MATERIAL = "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW";

    @Test
    void aPrivateKeyBlockIsAFindingThatNeverShowsTheKey() {
        String text = "---\n-----BEGIN OPENSSH " + PRIVATE_KEY + "-----\n" + KEY_MATERIAL + "\n-----END OPENSSH " + PRIVATE_KEY + "-----\n";
        List<Finding> f = SecretScan.scan("files/id_ed25519", text);
        assertThat(f).containsExactly(new Finding("files/id_ed25519", 2, "a private key block"));
        assertThat(f.get(0).toString()).isEqualTo("files/id_ed25519:2 a private key block").doesNotContain(KEY_MATERIAL);
        assertThat(SecretScan.scan("k.pem", "-----BEGIN RSA " + PRIVATE_KEY + "-----\n")).hasSize(1);
        assertThat(SecretScan.scan("k.asc", "-----BEGIN PGP " + PRIVATE_KEY + " BLOCK-----\n")).hasSize(1);
        assertThat(SecretScan.scan("k.pem", "-----BEGIN CERTIFICATE-----\n")).as("a certificate is public").isEmpty();
    }

    @Test
    void aSecretKeyWithALiteralValueIsAFindingAReferenceOrExpressionIsNot() {
        String text = String.join("\n",
                "db_user: app",                                              // 1
                "db_password: hunter2-s3cret",                               // 2 literal
                "token: vault:ops/token",                                    // 3 reference
                "api_key: \"{{ lookup('env', 'API_KEY') }}\"",               // 4 expression
                "Authorization: \"Bearer {{ token }}\"",                     // 5 expression inside
                "password: !vault |",                                        // 6 ansible-vault
                "  $ANSIBLE_VAULT;1.1;AES256",                               // 7
                "secret: \"\"",                                              // 8 empty
                "password: ${DB_PASSWORD}",                                  // 9 shell style
                "  - password: 's3cret' # the old one",                      // 10 literal, quoted, commented
                "client_secret = abc123",                                    // 11 ini style
                "aws_secret_access_key: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", // 12 literal
                "password: <your password>",                                 // 13 placeholder
                "password: CHANGEME",                                        // 14 placeholder
                "password_file: /run/secrets/db",                            // 15 a path, but the key says password
                "token_lifetime: 3600",                                      // 16 token in the name, a number
                "credentials: { user: app, password: hunter2 }",             // 17 a flow mapping
                "");
        List<Finding> f = SecretScan.scan("group_vars/all.yml", text);
        assertThat(f).extracting(Finding::line).as("a path after a *_file key and a number are not secrets").containsExactly(2, 10, 11, 12, 17);
        assertThat(f).extracting(Finding::what).containsExactly(
                "db_password has a literal value", "password has a literal value", "client_secret has a literal value",
                "aws_secret_access_key has a literal value", "password has a literal value");
        String all = String.join(" ", f.stream().map(Finding::toString).toList());
        assertThat(all).doesNotContain("hunter2", "s3cret", "abc123", "wJalrXUtnFEMI");
    }

    @Test
    void aLongHighEntropyValueAfterAWeakKeyIsAFinding() {
        String text = String.join("\n",
                "signing_key: " + "8f3a9c2d1e4b7a6f" + "5c0d9e8b7a6f5c4d3e2b1a0f", // 1 hex, long and varied
                "key: web",                                               // 2 short
                "auth: basic",                                            // 3 short
                "pass: yes",                                              // 4 short
                "ssh_key_file: ~/.ssh/id_ed25519",                        // 5 a path: low entropy
                "key_name: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",             // 6 long but no entropy
                "");
        List<Finding> f = SecretScan.scan("vars.yml", text);
        assertThat(f).containsExactly(new Finding("vars.yml", 1, "signing_key has a long high-entropy value"));
        assertThat(SecretScan.entropy("aaaa")).isZero();
        assertThat(SecretScan.entropy("abcd")).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void tokenShapesAreFoundAnywhereInALine() {
        String text = String.join("\n",
                "- name: Upload",
                "  shell: aws s3 cp x s3://b --profile AKIAIOSFODNN7EXAMPLE",
                "  environment:",
                "    GH: ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij",
                "    SLACK: xoxb-1234567890-abcdefghijkl",
                "    NOT_ONE: ghp_short",
                "");
        List<Finding> f = SecretScan.scan("site.yml", text);
        assertThat(f).extracting(Finding::line).containsExactly(2, 4, 5);
        assertThat(f).extracting(Finding::what).containsExactly("what looks like an AWS access key id", "what looks like a GitHub token", "what looks like a Slack token");
        assertThat(String.join(" ", f.stream().map(Finding::toString).toList())).doesNotContain("AKIAIOSFODNN7EXAMPLE", "ghp_", "xoxb");
    }

    @Test
    void binaryContentIsNotScanned() {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 'p', 'a', 's', 's', 'w', 'o', 'r', 'd', ':', ' ', 'x'};
        assertThat(SecretScan.isText(png)).isFalse();
        assertThat(SecretScan.scan("logo.png", png)).isEmpty();
        assertThat(SecretScan.isText(new byte[] {(byte) 0xff, (byte) 0xfe, 'a'})).as("not UTF-8").isFalse();
        assertThat(SecretScan.isText(new byte[0])).isFalse();
        assertThat(SecretScan.isText(null)).isFalse();
        assertThat(SecretScan.scan("vars.yml", "password: x\n".getBytes(StandardCharsets.UTF_8))).hasSize(1);
    }

    @Test
    void theRefusalNamesFilesAndLinesAndCapsTheList() {
        List<Finding> few = List.of(new Finding("site.yml", 3, "password has a literal value"), new Finding("files/key", 1, "a private key block"));
        assertThat(SecretScan.refusal("web.zip", few))
                .isEqualTo("web.zip holds what looks like a secret: site.yml:3 password has a literal value; files/key:1 a private key block. "
                        + "Keep secrets out of playbooks: reference them as vault:<name> or an expression");
        List<Finding> many = java.util.stream.IntStream.rangeClosed(1, 14).mapToObj(i -> new Finding("v.yml", i, "token has a literal value")).toList();
        assertThat(SecretScan.refusal("web.zip", many)).contains("v.yml:10 token has a literal value; and 4 more.").doesNotContain("v.yml:11");
    }
}
