package com.iaf.console.fake.audit.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Writes a small PDF of text lines in Courier, one page every 60 lines, with a valid cross-reference table. */
public final class PdfWriter {

    private static final int LINES_PER_PAGE = 60;

    private PdfWriter() {
    }

    public static byte[] of(List<String> lines) {
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < Math.max(1, lines.size()); i += LINES_PER_PAGE) {
            pages.add(lines.subList(i, Math.min(lines.size(), i + LINES_PER_PAGE)));
        }
        List<byte[]> objects = new ArrayList<>();
        // 1 catalog, 2 pages, 3 font, then a page and a content stream per page
        int firstPage = 4;
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < pages.size(); p++) {
            kids.append(firstPage + p * 2).append(" 0 R ");
        }
        objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
        objects.add(bytes("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pages.size() + " >>"));
        objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>"));
        for (int p = 0; p < pages.size(); p++) {
            int pageObject = firstPage + p * 2;
            int contentObject = pageObject + 1;
            objects.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents "
                    + contentObject + " 0 R >>"));
            StringBuilder content = new StringBuilder("BT /F1 9 Tf 12 TL 36 760 Td\n");
            for (String line : pages.get(p)) {
                content.append('(').append(escape(line)).append(") Tj T*\n");
            }
            content.append("ET");
            byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);
            ByteArrayOutputStream object = new ByteArrayOutputStream();
            object.writeBytes(bytes("<< /Length " + stream.length + " >>\nstream\n"));
            object.writeBytes(stream);
            object.writeBytes(bytes("\nendstream"));
            objects.add(object.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(bytes("%PDF-1.4\n"));
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            out.writeBytes(bytes((i + 1) + " 0 obj\n"));
            out.writeBytes(objects.get(i));
            out.writeBytes(bytes("\nendobj\n"));
        }
        int xref = out.size();
        StringBuilder table = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            table.append(String.format("%010d 00000 n \n", offset));
        }
        table.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        out.writeBytes(bytes(table.toString()));
        return out.toByteArray();
    }

    private static String escape(String line) {
        StringBuilder out = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 32 || c > 126) {
                out.append('?');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }
}
