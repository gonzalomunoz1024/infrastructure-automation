package com.iaf.platform.audit.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** An evidence record as a PDF: a header block, then one line per entry in chain order. */
public final class EvidencePdf {

    private EvidencePdf() {
    }

    public static byte[] render(EvidenceRecord record) {
        List<String> lines = new ArrayList<>();
        lines.add("Workflow: " + or(record.workflow()) + "   Version: " + or(record.version()));
        lines.add("Requester: " + or(record.requester()) + "   Channel: " + or(record.channel()) + "   Status: " + record.status());
        lines.add("Requested at: " + or(record.at() == null ? null : record.at().toString()) + "   Entries: " + record.entries().size());
        lines.add("Latest hash: " + or(record.latestHash()));
        lines.add(record.sealed() == null ? "Sealed: no"
                : "Sealed: " + record.sealed().at() + " by key " + record.sealed().keyId() + " signature " + record.sealed().signature());
        lines.add("Retention until: " + or(record.retention() == null || record.retention().until() == null ? null : record.retention().until().toString()));
        if (record.change() != null) {
            lines.add("Change: " + or(record.change().id()) + " " + or(record.change().url()));
        }
        lines.add("");
        lines.add(String.format(Locale.ROOT, "%-4s %-24s %-10s %-14s %s", "#", "at", "kind", "actor", "data"));
        for (EvidenceEntry e : record.entries()) {
            lines.add(String.format(Locale.ROOT, "%-4d %-24s %-10s %-14s %s", e.seq(), e.at(), e.kind().json(), or(e.actor()),
                    e.data() == null ? "" : HashChain.canonical(e.data())));
        }
        return PdfWriter.write("Evidence record " + record.jobId(), lines);
    }

    private static String or(String s) {
        return s == null ? "-" : s;
    }
}
