package com.iaf.platform.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The one SHA-256 helper: tokens, share links, content hashes and spec digests all use it. */
class HashesTest {

    @Test
    void isTheSha256OfTheUtf8BytesInLowercaseHex() {
        assertThat(Hashes.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Hashes.sha256("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(Hashes.sha256("é")).isEqualTo("4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c");
    }
}
