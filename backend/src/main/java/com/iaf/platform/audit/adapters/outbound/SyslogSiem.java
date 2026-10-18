package com.iaf.platform.audit.adapters.outbound;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.audit.application.SiemProperties;
import com.iaf.platform.audit.ports.outbound.SiemPort;

import jakarta.annotation.PreDestroy;

/**
 * RFC 5424 syslog (profile {@code siem}, on when {@code platform.siem.syslog.host} is set), over UDP
 * (RFC 5426, one entry per datagram) or TCP with octet-counting framing (RFC 6587, one connection
 * kept open and re-opened after a failure). Each entry is one message:
 *
 * <pre>
 * &lt;PRI&gt;1 TIMESTAMP HOSTNAME APP-NAME PROCID &lt;stream&gt;.&lt;kind&gt; [iaf@32473 stream=.. recordId=.. seq=.. kind=.. actor=.. workflow=.. hash=.. previous=..] BOM{entry as JSON}
 * </pre>
 *
 * PRI is {@code facility * 8 + 6} (informational). A message too large for one datagram keeps its
 * structured data and replaces its JSON with {@code {"truncated":true,...}}; the full entry stays in
 * the evidence store.
 */
@Component
@Profile("siem")
@Primary
@ConditionalOnExpression("!'${platform.siem.syslog.host:}'.isBlank()")
public class SyslogSiem implements SiemPort {

    static final int SEVERITY_INFORMATIONAL = 6;
    static final int MAX_DATAGRAM_BYTES = 60_000;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final Pattern SD_ID = Pattern.compile("[\\x21-\\x7E&&[^=\\]\" ]]{1,32}");

    private final SiemProperties.Syslog config;
    private final ObjectMapper json;
    private final String hostname;
    private final String procId;
    private final boolean tcp;
    private DatagramSocket udpSocket;
    private Socket tcpSocket;
    private OutputStream tcpOut;

    @Autowired
    public SyslogSiem(SiemProperties props, ObjectMapper json) {
        this(props.getSyslog(), json, localHostName());
    }

    SyslogSiem(SiemProperties.Syslog config, ObjectMapper json, String hostname) {
        if (config.getHost() == null || config.getHost().isBlank()) {
            throw new IllegalArgumentException("platform.siem.syslog.host is required for the syslog export");
        }
        String protocol = config.getProtocol() == null ? "udp" : config.getProtocol().trim().toLowerCase(Locale.ROOT);
        if (!protocol.equals("udp") && !protocol.equals("tcp")) {
            throw new IllegalArgumentException("platform.siem.syslog.protocol is udp or tcp, not " + config.getProtocol());
        }
        if (config.getFacility() < 0 || config.getFacility() > 23) {
            throw new IllegalArgumentException("platform.siem.syslog.facility is 0 to 23, not " + config.getFacility());
        }
        if (config.getPort() < 1 || config.getPort() > 65_535) {
            throw new IllegalArgumentException("platform.siem.syslog.port is 1 to 65535, not " + config.getPort());
        }
        if (config.getSdId() == null || !SD_ID.matcher(config.getSdId()).matches()) {
            throw new IllegalArgumentException("platform.siem.syslog.sd-id must be 1 to 32 printable characters without = ] \" or space, like iaf@32473");
        }
        this.config = config;
        this.json = json;
        this.hostname = hostname;
        this.procId = String.valueOf(ProcessHandle.current().pid());
        this.tcp = protocol.equals("tcp");
    }

    @Override
    public String name() {
        return "syslog";
    }

    @Override
    public synchronized void send(List<SiemEvent> batch) throws IOException {
        if (batch.isEmpty()) {
            return;
        }
        if (tcp) {
            sendTcp(batch);
        } else {
            sendUdp(batch);
        }
    }

    @PreDestroy
    public synchronized void close() {
        closeTcp();
        if (udpSocket != null) {
            udpSocket.close();
            udpSocket = null;
        }
    }

    /** The RFC 5424 message for one entry, in UTF-8. */
    byte[] message(SiemEvent e, int limit) throws IOException {
        String head = "<" + (config.getFacility() * 8 + SEVERITY_INFORMATIONAL) + ">1 "
                + (e.at() == null ? "-" : TIMESTAMP.format(e.at())) + " "
                + field(hostname, 255) + " "
                + field(config.getAppName(), 48) + " "
                + field(procId, 128) + " "
                + field(e.stream() + "." + e.kind(), 32) + " "
                + structuredData(e) + " ";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] body = json.writeValueAsBytes(e);
        if (headBytes.length + BOM.length + body.length > limit) {
            body = json.writeValueAsBytes(json.createObjectNode().put("truncated", true).put("bytes", body.length)
                    .put("recordId", e.recordId()).put("seq", e.seq()).put("hash", e.hash()));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(headBytes.length + BOM.length + body.length);
        out.write(headBytes);
        out.write(BOM);
        out.write(body);
        return out.toByteArray();
    }

    private String structuredData(SiemEvent e) {
        StringBuilder sd = new StringBuilder("[").append(config.getSdId());
        param(sd, "stream", e.stream());
        param(sd, "recordId", e.recordId());
        param(sd, "seq", String.valueOf(e.seq()));
        param(sd, "kind", e.kind());
        param(sd, "actor", e.actor());
        param(sd, "workflow", e.workflow());
        param(sd, "hash", e.hash());
        param(sd, "previous", e.previous());
        return sd.append(']').toString();
    }

    /** {@code name="value"} with {@code "}, {@code \} and {@code ]} escaped as RFC 5424 asks. */
    private static void param(StringBuilder sd, String name, String value) {
        if (value == null) {
            return;
        }
        sd.append(' ').append(name).append("=\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\' || c == ']') {
                sd.append('\\');
            }
            sd.append(c);
        }
        sd.append('"');
    }

    /** A header field: printable US-ASCII without spaces, at most {@code max} long; {@code -} when empty. */
    static String field(String value, int max) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < max; i++) {
            char c = value.charAt(i);
            out.append(c > 32 && c < 127 ? c : '_');
        }
        return out.toString();
    }

    private void sendUdp(List<SiemEvent> batch) throws IOException {
        if (udpSocket == null) {
            udpSocket = new DatagramSocket();
        }
        InetSocketAddress to = new InetSocketAddress(config.getHost().trim(), config.getPort());
        for (SiemEvent e : batch) {
            byte[] m = message(e, MAX_DATAGRAM_BYTES);
            udpSocket.send(new DatagramPacket(m, m.length, to));
        }
    }

    private void sendTcp(List<SiemEvent> batch) throws IOException {
        try {
            OutputStream out = tcpOut();
            for (SiemEvent e : batch) {
                byte[] m = message(e, Integer.MAX_VALUE);
                out.write((m.length + " ").getBytes(StandardCharsets.US_ASCII));
                out.write(m);
            }
            out.flush();
        } catch (IOException e) {
            closeTcp();
            throw e;
        }
    }

    private OutputStream tcpOut() throws IOException {
        if (tcpSocket == null || tcpSocket.isClosed()) {
            Socket socket = new Socket();
            int millis = (int) Math.min(Integer.MAX_VALUE, timeout().toMillis());
            socket.connect(new InetSocketAddress(config.getHost().trim(), config.getPort()), millis);
            socket.setSoTimeout(millis);
            socket.setKeepAlive(true);
            tcpSocket = socket;
            tcpOut = new BufferedOutputStream(socket.getOutputStream());
        }
        return tcpOut;
    }

    private void closeTcp() {
        if (tcpSocket != null) {
            try {
                tcpSocket.close();
            } catch (IOException ignored) {
                // closing a broken connection
            }
        }
        tcpSocket = null;
        tcpOut = null;
    }

    private Duration timeout() {
        return config.getTimeout() == null ? Duration.ofSeconds(5) : config.getTimeout();
    }

    private static String localHostName() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "-";
        }
    }
}
