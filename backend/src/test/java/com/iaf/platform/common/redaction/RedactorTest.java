package com.iaf.platform.common.redaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class RedactorTest {

    final ObjectMapper json = new ObjectMapper();

    @Test
    void secretLookingFieldsAreMaskedWhateverTheirValue() throws Exception {
        JsonNode in = json.readTree("""
                {"user":"ana","password":"hunter2","apiKey":"k-123","Authorization":"Bearer abc","sessionId":42,"retries":3}""");

        JsonNode out = Redactor.redact(in, null);

        assertThat(out.get("user").asText()).isEqualTo("ana");
        assertThat(out.get("password").asText()).isEqualTo("***");
        assertThat(out.get("apiKey").asText()).isEqualTo("***");
        assertThat(out.get("Authorization").asText()).isEqualTo("***");
        assertThat(out.get("sessionId").asText()).isEqualTo("***");
        assertThat(out.get("retries").asInt()).isEqualTo(3);
    }

    @Test
    void vaultReferencesStayVisibleEvenUnderASecretLookingName() throws Exception {
        JsonNode in = json.readTree("""
                {"credential":"vault:ssh/ops-automation","token":["vault:a/b","raw-token"]}""");

        JsonNode out = Redactor.redact(in, null);

        assertThat(out.get("credential").asText()).isEqualTo("vault:ssh/ops-automation");
        assertThat(out.get("token").get(0).asText()).isEqualTo("vault:a/b");
        assertThat(out.get("token").get(1).asText()).isEqualTo("***");
    }

    @Test
    void nestedObjectsAreWalkedAndOnlyTheirSecretsMasked() throws Exception {
        JsonNode in = json.readTree("""
                {"credentials":{"username":"svc","password":"p"},"items":[{"secret":"s","id":"vm-1"}]}""");

        JsonNode out = Redactor.redact(in, null);

        assertThat(out.at("/credentials/username").asText()).isEqualTo("svc");
        assertThat(out.at("/credentials/password").asText()).isEqualTo("***");
        assertThat(out.at("/items/0/secret").asText()).isEqualTo("***");
        assertThat(out.at("/items/0/id").asText()).isEqualTo("vm-1");
    }

    @Test
    void aKnownSecretIsMaskedWhereverItAppears() throws Exception {
        Set<String> known = Set.of("s3cr3t-value", "svc-pass");
        JsonNode in = json.readTree("""
                {"echo":"s3cr3t-value","header":"Bearer s3cr3t-value","url":"https://x/api?key=s3cr3t-value&page=2",
                 "pair":"svc:svc-pass","other":"nothing secret here","list":["s3cr3t-value"]}""");

        JsonNode out = Redactor.redact(in, known::contains);

        assertThat(out.get("echo").asText()).isEqualTo("***");
        assertThat(out.get("header").asText()).isEqualTo("Bearer ***");
        assertThat(out.get("url").asText()).isEqualTo("https://x/api?key=***&page=2");
        assertThat(out.get("pair").asText()).isEqualTo("svc:***");
        assertThat(out.get("other").asText()).isEqualTo("nothing secret here");
        assertThat(out.get("list").get(0).asText()).isEqualTo("***");
    }

    @Test
    void tokenShapedWordsAndPrivateKeysAreMaskedEvenWhenNothingIsKnown() throws Exception {
        JsonNode in = json.readTree("""
                {"jwt":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbmEifQ.c2lnbmF0dXJlLXBhcnQ","note":"use ghp_abcdefghijklmnopqrstuvwxyz0123 for github",
                 "aws":"AKIAIOSFODNN7EXAMPLE","key":"-----BEGIN RSA PRIVATE KEY-----\\nMIIE...\\n-----END RSA PRIVATE KEY-----",
                 "api-key":"k-1","plain":"eyJ is how a jwt starts","id":"vm-1"}""");

        JsonNode out = Redactor.redact(in, null);

        assertThat(out.get("jwt").asText()).isEqualTo("***");
        assertThat(out.get("note").asText()).isEqualTo("use *** for github");
        assertThat(out.get("aws").asText()).isEqualTo("***");
        assertThat(out.get("key").asText()).isEqualTo("***");
        assertThat(out.get("api-key").asText()).as("api-key is a secret-looking name too").isEqualTo("***");
        assertThat(out.get("plain").asText()).isEqualTo("eyJ is how a jwt starts");
        assertThat(out.get("id").asText()).isEqualTo("vm-1");
        assertThat(Redactor.tokenShape("xoxb-123456789012-abc")).isTrue();
        assertThat(Redactor.tokenShape("hvs.CAESIJ1234567890abcdefghijklmnop")).isTrue();
        assertThat(Redactor.tokenShape("vm-1")).isFalse();
    }

    @Test
    void anotherMaskCanBeAsked() throws Exception {
        JsonNode in = json.readTree("""
                {"apiToken":"s3cr3t","echo":"Bearer s3cr3t","credential":"vault:fleet/token","name":"web"}""");

        JsonNode out = Redactor.redact(in, Set.of("s3cr3t")::contains, "vault:redacted");

        assertThat(out.get("apiToken").asText()).isEqualTo("vault:redacted");
        assertThat(out.get("echo").asText()).isEqualTo("Bearer vault:redacted");
        assertThat(out.get("credential").asText()).isEqualTo("vault:fleet/token");
        assertThat(out.get("name").asText()).isEqualTo("web");
        assertThat(Redactor.redact("HTTP 401 for s3cr3t", Set.of("s3cr3t")::contains, "vault:redacted")).isEqualTo("HTTP 401 for vault:redacted");
    }

    @Test
    void theTreePassedInIsNeverChanged() throws Exception {
        JsonNode in = json.readTree("""
                {"password":"p","nested":{"token":"t"},"echo":"known"}""");
        String before = in.toString();

        Redactor.redact(in, Set.of("known")::contains);

        assertThat(in.toString()).isEqualTo(before);
    }

    @Test
    void nullStaysNull() {
        assertThat(Redactor.redact((JsonNode) null, null)).isNull();
        assertThat(Redactor.redact((String) null, s -> true)).isNull();
    }
}
