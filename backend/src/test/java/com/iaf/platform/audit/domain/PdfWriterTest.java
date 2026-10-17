package com.iaf.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class PdfWriterTest {

    static final Instant AT = Instant.parse("2026-10-17T10:00:00Z");

    static String text(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }

    @Test
    void writesAPdf14FileWithAnXrefTableAndAnEofMarker() {
        byte[] pdf = PdfWriter.write("Evidence record job_1", List.of("one", "two"));
        String s = text(pdf);

        assertThat(s).startsWith("%PDF-1.4\n");
        assertThat(s).contains("/Type /Catalog").contains("/Type /Pages").contains("/Type /Page ").contains("/BaseFont /Helvetica");
        assertThat(s).contains("\nxref\n0 6\n").contains("trailer").contains("/Root 1 0 R");
        assertThat(s.trim()).endsWith("%%EOF");
        assertThat(s).contains("(Evidence record job_1) Tj").contains("(one) Tj T*").contains("(two) Tj T*");
    }

    @Test
    void xrefOffsetsPointAtTheObjectsTheyIndex() {
        byte[] pdf = PdfWriter.write("t", List.of("a", "b", "c"));
        String s = text(pdf);

        int startxrefAt = s.lastIndexOf("startxref\n") + "startxref\n".length();
        int xref = Integer.parseInt(s.substring(startxrefAt, s.indexOf('\n', startxrefAt)));
        assertThat(s.startsWith("xref\n", xref)).as("startxref points at the xref table").isTrue();

        String[] lines = s.substring(xref).split("\n");
        int objects = Integer.parseInt(lines[1].split(" ")[1]) - 1;
        assertThat(lines[2]).isEqualTo("0000000000 65535 f ");
        for (int i = 1; i <= objects; i++) {
            String entry = lines[2 + i];
            assertThat(entry).hasSize(19);
            int offset = Integer.parseInt(entry.substring(0, 10));
            assertThat(s.startsWith(i + " 0 obj\n", offset)).as("entry " + i + " at " + offset).isTrue();
        }
    }

    @Test
    void breaksIntoPagesWhenTheLinesDoNotFit() {
        List<String> lines = IntStream.rangeClosed(1, PdfWriter.LINES_PER_PAGE * 2 + 1).mapToObj(i -> "line " + i).toList();

        String s = text(PdfWriter.write("t", lines));

        assertThat(s).contains("/Count 3");
        assertThat(s.split("/Type /Page ", -1)).hasSize(4);
        assertThat(s).contains("(Page 1 of 3) Tj").contains("(Page 3 of 3) Tj");
        assertThat(s).contains("(line " + (PdfWriter.LINES_PER_PAGE * 2 + 1) + ") Tj");
    }

    @Test
    void escapesDelimitersAndKeepsLinesWithinTheWidth() {
        assertThat(PdfWriter.escape("a(b)\\c\tnewé 中")).isEqualTo("a\\(b\\)\\\\c newé ?");
        assertThat(PdfWriter.escape("x".repeat(500))).hasSize(PdfWriter.MAX_CHARS).endsWith("...");
    }

    @Test
    void rendersAnEvidenceRecordWithOneLinePerEntry() {
        ObjectMapper mapper = new ObjectMapper();
        List<EvidenceEntry> entries = new ArrayList<>();
        EvidenceEntry first = EvidenceEntry.draft(AT, EvidenceKind.REQUESTED, "ana",
                mapper.createObjectNode().put("workflow", "vm-restart").put("version", "1.2.0").put("channel", "catalog")).chained(1, HashChain.GENESIS);
        entries.add(first);
        EvidenceRecord record = EvidenceRecord.start("job_1", first)
                .append(EvidenceEntry.draft(AT.plusSeconds(5), EvidenceKind.STEP, null, mapper.createObjectNode().put("step", "restart").put("status", "succeeded")).chained(2, first.hash()))
                .withSeal(new EvidenceRecord.Seal(AT.plusSeconds(60), "c2ln", "kabc"));

        String s = text(EvidencePdf.render(record));

        assertThat(s).startsWith("%PDF-1.4");
        assertThat(s).contains("(Evidence record job_1) Tj");
        assertThat(s).contains("Workflow: vm-restart   Version: 1.2.0");
        assertThat(s).contains("Sealed: 2026-10-17T10:01:00Z by key kabc signature c2ln");
        assertThat(s).contains(String.format(Locale.ROOT, "%-4d %-24s %-10s %-14s ", 1, AT, "requested", "ana"));
        assertThat(s).contains(String.format(Locale.ROOT, "(%-4d %-24s %-10s %-14s %s) Tj", 2, AT.plusSeconds(5), "step", "-",
                "{\"status\":\"succeeded\",\"step\":\"restart\"}"));
    }
}
