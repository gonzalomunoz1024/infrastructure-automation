package com.iaf.platform.audit.domain;

/** A sealed record is final: every store refuses an entry for it with this, which is a refusal, not a failure of the store. */
public class SealedRecordException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public SealedRecordException(String jobId, EvidenceKind kind) {
        super("evidence record " + jobId + " is sealed; a late " + (kind == null ? "" : kind.json() + " ") + "entry is not accepted");
    }
}
