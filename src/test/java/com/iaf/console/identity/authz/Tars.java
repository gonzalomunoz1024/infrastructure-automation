package com.iaf.console.identity.authz;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** A minimal reader of the gzipped ustar the bundle is: entry names to contents. */
public final class Tars {

    private Tars() {
    }

    public static Map<String, byte[]> read(byte[] gzipped) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            while (true) {
                byte[] header = in.readNBytes(512);
                if (header.length < 512 || header[0] == 0) {
                    break;
                }
                String name = field(header, 0, 100);
                int size = Integer.parseInt(field(header, 124, 12).trim(), 8);
                byte[] content = in.readNBytes(size);
                int pad = (512 - size % 512) % 512;
                in.readNBytes(pad);
                entries.put(name, content);
            }
        }
        return entries;
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
    }
}
