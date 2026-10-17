package com.iaf.platform.audit.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A tiny PDF 1.4 writer: Helvetica, a title and one line of text per entry, page breaks, a
 * correct xref table. Enough for an evidence export that any viewer opens; no library.
 */
public final class PdfWriter {

    /** A4 in points. */
    static final int PAGE_WIDTH = 595;
    static final int PAGE_HEIGHT = 842;
    static final int MARGIN = 50;
    static final int TITLE_SIZE = 14;
    static final int BODY_SIZE = 9;
    static final int LEADING = 12;
    /** The title takes the first two line heights of every page. */
    static final int LINES_PER_PAGE = (PAGE_HEIGHT - 2 * MARGIN) / LEADING - 2;
    /** Helvetica at 9pt fits about this many characters between the margins. */
    static final int MAX_CHARS = 120;

    private PdfWriter() {
    }

    public static byte[] write(String title, List<String> lines) {
        List<List<String>> pages = paginate(lines);
        List<byte[]> objects = new ArrayList<>();
        objects.add(latin1("<< /Type /Catalog /Pages 2 0 R >>"));
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            kids.append(i == 0 ? "" : " ").append(4 + 2 * i).append(" 0 R");
        }
        objects.add(latin1("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>"));
        objects.add(latin1("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        for (int i = 0; i < pages.size(); i++) {
            int contents = 5 + 2 * i;
            objects.add(latin1("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + PAGE_WIDTH + " " + PAGE_HEIGHT + "]"
                    + " /Resources << /Font << /F1 3 0 R >> >> /Contents " + contents + " 0 R >>"));
            byte[] stream = latin1(content(title, pages.get(i), i + 1, pages.size()));
            ByteArrayOutputStream obj = new ByteArrayOutputStream();
            obj.writeBytes(latin1("<< /Length " + stream.length + " >>\nstream\n"));
            obj.writeBytes(stream);
            obj.writeBytes(latin1("\nendstream"));
            objects.add(obj.toByteArray());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(latin1("%PDF-1.4\n%âãÏÓ\n"));
        long[] offsets = new long[objects.size() + 1];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = out.size();
            out.writeBytes(latin1((i + 1) + " 0 obj\n"));
            out.writeBytes(objects.get(i));
            out.writeBytes(latin1("\nendobj\n"));
        }
        long xref = out.size();
        StringBuilder table = new StringBuilder();
        table.append("xref\n0 ").append(objects.size() + 1).append('\n');
        table.append("0000000000 65535 f \n");
        for (int i = 1; i <= objects.size(); i++) {
            table.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i]));
        }
        table.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\n");
        table.append("startxref\n").append(xref).append("\n%%EOF\n");
        out.writeBytes(latin1(table.toString()));
        return out.toByteArray();
    }

    static List<List<String>> paginate(List<String> lines) {
        List<List<String>> pages = new ArrayList<>();
        List<String> all = lines == null ? List.of() : lines;
        for (int i = 0; i < all.size(); i += LINES_PER_PAGE) {
            pages.add(all.subList(i, Math.min(all.size(), i + LINES_PER_PAGE)));
        }
        if (pages.isEmpty()) {
            pages.add(List.of());
        }
        return pages;
    }

    private static String content(String title, List<String> lines, int page, int pages) {
        int top = PAGE_HEIGHT - MARGIN;
        StringBuilder sb = new StringBuilder();
        sb.append("BT\n/F1 ").append(TITLE_SIZE).append(" Tf\n").append(MARGIN).append(' ').append(top).append(" Td\n(")
                .append(escape(title)).append(") Tj\nET\n");
        sb.append("BT\n/F1 ").append(BODY_SIZE).append(" Tf\n").append(LEADING).append(" TL\n")
                .append(MARGIN).append(' ').append(top - 2 * LEADING).append(" Td\n");
        for (String line : lines) {
            sb.append('(').append(escape(line)).append(") Tj T*\n");
        }
        sb.append("ET\n");
        sb.append("BT\n/F1 ").append(BODY_SIZE).append(" Tf\n").append(MARGIN).append(' ').append(MARGIN - LEADING).append(" Td\n(")
                .append("Page ").append(page).append(" of ").append(pages).append(") Tj\nET\n");
        return sb.toString();
    }

    /** One PDF string: WinAnsi-safe characters, escaped delimiters, cut to the line width. */
    static String escape(String text) {
        String t = text == null ? "" : text;
        if (t.length() > MAX_CHARS) {
            t = t.substring(0, MAX_CHARS - 3) + "...";
        }
        StringBuilder sb = new StringBuilder(t.length() + 8);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' || c == '(' || c == ')') {
                sb.append('\\').append(c);
            } else if (c < 32 || c > 255) {
                sb.append(c == '\t' ? ' ' : '?');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
