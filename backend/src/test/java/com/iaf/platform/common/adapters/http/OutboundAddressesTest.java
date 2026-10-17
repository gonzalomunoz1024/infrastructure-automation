package com.iaf.platform.common.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class OutboundAddressesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",
            "http://169.254.170.2/v2/credentials",
            "http://[fe80::1]/",
            "http://[::ffff:169.254.169.254]/",
            "http://0.0.0.0:8080/",
            "http://100.100.100.200/latest/meta-data/",
            "http://[fd00:ec2::254]/",
            "http://metadata.google.internal/computeMetadata/v1/",
            "http://METADATA.Google.Internal./computeMetadata/v1/",
            "http://metadata/computeMetadata/v1/" })
    void refusesMetadataAndLinkLocalTargets(String url) {
        assertThatThrownBy(() -> OutboundAddresses.requireAllowed(URI.create(url)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providers cannot call it");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080/api",
            "http://localhost:8080/api",
            "https://10.20.0.5/sdk",
            "https://192.168.1.10/",
            "https://vcenter.example.com/rest",
            "https://[2001:db8::1]/" })
    void allowsLoopbackPrivateAndPublicTargets(String url) {
        URI uri = URI.create(url);
        assertThat(OutboundAddresses.requireAllowed(uri)).isSameAs(uri);
    }

    @Test
    void theResolverRefusesANameWithAMetadataAddressAmongItsAnswers() throws UnknownHostException {
        assertThat(OutboundAddresses.GuardedNameResolver.allowed("localhost")).isNotEmpty();
        assertThatThrownBy(() -> OutboundAddresses.GuardedNameResolver.allowed("169.254.169.254"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("169.254.169.254");
        assertThatThrownBy(() -> OutboundAddresses.GuardedNameResolver.allowed("metadata.google.internal"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void blockedCoversLinkLocalAndTheUnspecifiedAddress() throws UnknownHostException {
        assertThat(OutboundAddresses.blocked(InetAddress.getByName("169.254.1.1"))).isTrue();
        assertThat(OutboundAddresses.blocked(InetAddress.getByName("fe80::abcd"))).isTrue();
        assertThat(OutboundAddresses.blocked(InetAddress.getByName("0.0.0.0"))).isTrue();
        assertThat(OutboundAddresses.blocked(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(OutboundAddresses.blocked(InetAddress.getByName("10.0.0.1"))).isFalse();
    }
}
