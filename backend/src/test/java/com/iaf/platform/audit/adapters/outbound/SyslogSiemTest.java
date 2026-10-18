package com.iaf.platform.audit.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.iaf.platform.audit.application.SiemProperties;
import com.iaf.platform.audit.ports.outbound.SiemPort.SiemEvent;

class SyslogSiemTest {

    static final Instant AT = Instant.parse("2026-10-17T10:00:00.123Z");
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final String BOM = "﻿";

    static SiemProperties.Syslog config(int port, String protocol) {
        SiemProperties.Syslog c = new SiemProperties.Syslog();
        c.setHost("127.0.0.1");
        c.setPort(port);
        c.setProtocol(protocol);
        c.setTimeout(Duration.ofSeconds(5));
        return c;
    }

    static SiemEvent event(int seq, String actor, String text) {
        return new SiemEvent("job", "job_1", "vm-create", seq, AT, "step", actor, JSON.createObjectNode().put("note", text), "h" + seq, "h" + (seq - 1));
    }

    static String pid() {
        return String.valueOf(ProcessHandle.current().pid());
    }

    @Test
    void sendsOneRfc5424DatagramPerEntryOverUdp() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            SyslogSiem syslog = new SyslogSiem(config(server.getLocalPort(), "udp"), JSON, "host-1");

            syslog.send(List.of(event(3, "ana", "created")));
            byte[] buffer = new byte[65_535];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            server.receive(packet);
            syslog.close();

            String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
            assertThat(message).startsWith("<110>1 2026-10-17T10:00:00.123000Z host-1 iaf-platform " + pid() + " job.step "
                    + "[iaf@32473 stream=\"job\" recordId=\"job_1\" seq=\"3\" kind=\"step\" actor=\"ana\" workflow=\"vm-create\" hash=\"h3\" previous=\"h2\"] "
                    + BOM + "{");
            JsonNode body = JSON.readTree(message.substring(message.indexOf(BOM) + 1));
            assertThat(body.get("data").get("note").asText()).isEqualTo("created");
            assertThat(body.get("recordId").asText()).isEqualTo("job_1");
        }
    }

    @Test
    void framesEveryEntryWithItsOctetCountOverTcp() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<List<String>> frames = CompletableFuture.supplyAsync(() -> readFrames(server, 2));
            SyslogSiem syslog = new SyslogSiem(config(server.getLocalPort(), "TCP"), JSON, "host-1");

            syslog.send(List.of(event(1, "ana", "first"), event(2, "ana", "second ü")));

            List<String> received = frames.get(10, TimeUnit.SECONDS);
            syslog.close();
            assertThat(received).hasSize(2);
            assertThat(received.get(0)).contains(" seq=\"1\" ").endsWith("}");
            assertThat(received.get(1)).contains(" seq=\"2\" ").contains("second ü");
        }
    }

    @Test
    void escapesQuotesBackslashesAndBracketsInStructuredData() throws Exception {
        SyslogSiem syslog = new SyslogSiem(config(514, "udp"), JSON, "host 1");

        String message = new String(syslog.message(event(1, "a\"b]c\\d", "x"), Integer.MAX_VALUE), StandardCharsets.UTF_8);

        assertThat(message).contains(" host_1 ");
        assertThat(message).contains("actor=\"a\\\"b\\]c\\\\d\"");
    }

    @Test
    void anEntryTooLargeForADatagramKeepsItsStructuredDataAndDropsItsBody() throws Exception {
        SyslogSiem syslog = new SyslogSiem(config(514, "udp"), JSON, "host-1");

        String message = new String(syslog.message(event(4, "ana", "x".repeat(70_000)), SyslogSiem.MAX_DATAGRAM_BYTES), StandardCharsets.UTF_8);

        assertThat(message.getBytes(StandardCharsets.UTF_8).length).isLessThan(SyslogSiem.MAX_DATAGRAM_BYTES);
        assertThat(message).contains("hash=\"h4\"");
        JsonNode body = JSON.readTree(message.substring(message.indexOf(BOM) + 1));
        assertThat(body.get("truncated").asBoolean()).isTrue();
        assertThat(body.get("seq").asInt()).isEqualTo(4);
    }

    @Test
    void refusesAConfigurationItCannotSpeak() {
        SiemProperties.Syslog http = config(514, "http");
        SiemProperties.Syslog facility = config(514, "udp");
        facility.setFacility(24);
        SiemProperties.Syslog sdId = config(514, "udp");
        sdId.setSdId("iaf audit");

        assertThatThrownBy(() -> new SyslogSiem(http, JSON, "h")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("udp or tcp");
        assertThatThrownBy(() -> new SyslogSiem(facility, JSON, "h")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("facility");
        assertThatThrownBy(() -> new SyslogSiem(sdId, JSON, "h")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sd-id");
    }

    @Test
    void aDroppedTcpConnectionFailsTheSendAndTheNextSendReconnects() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            SyslogSiem syslog = new SyslogSiem(config(server.getLocalPort(), "tcp"), JSON, "host-1");
            CompletableFuture<List<String>> first = CompletableFuture.supplyAsync(() -> readFrames(server, 1));
            syslog.send(List.of(event(1, "ana", "one")));
            assertThat(first.get(10, TimeUnit.SECONDS)).hasSize(1);

            CompletableFuture<List<String>> second = CompletableFuture.supplyAsync(() -> readFrames(server, 1));
            boolean failedOnce = false;
            for (int i = 0; i < 50 && !second.isDone(); i++) {
                try {
                    syslog.send(List.of(event(2, "ana", "two")));
                } catch (IOException e) {
                    failedOnce = true;
                }
                Thread.sleep(20);
            }
            syslog.close();
            assertThat(failedOnce).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).singleElement().satisfies(m -> assertThat(m).contains(" seq=\"2\" "));
        }
    }

    /** Accepts one connection and reads {@code count} octet-counted frames, then closes it. */
    static List<String> readFrames(ServerSocket server, int count) {
        List<String> frames = new ArrayList<>();
        try (Socket socket = server.accept(); InputStream in = socket.getInputStream()) {
            socket.setSoTimeout(10_000);
            for (int i = 0; i < count; i++) {
                ByteArrayOutputStream length = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != ' ') {
                    if (b < 0) {
                        return frames;
                    }
                    length.write(b);
                }
                byte[] frame = in.readNBytes(Integer.parseInt(length.toString(StandardCharsets.US_ASCII)));
                frames.add(new String(frame, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return frames;
    }
}
