-- Audit evidence is append-only, and the database says so too, not only JdbcEvidenceStore: an
-- entry is never updated or deleted, a sealed record takes no more entries, a header is never
-- deleted, and a sealed header never changes again.

CREATE FUNCTION evidence_entries_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF EXISTS (SELECT 1 FROM evidence_records r WHERE r.job_id = NEW.job_id AND r.sealed_at IS NOT NULL) THEN
            RAISE EXCEPTION 'evidence record % is sealed; no more entries are accepted', NEW.job_id
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'evidence entries are append-only: % is not allowed', TG_OP
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER evidence_entries_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON evidence_entries
    FOR EACH ROW EXECUTE FUNCTION evidence_entries_append_only();

CREATE TRIGGER evidence_entries_no_truncate
    BEFORE TRUNCATE ON evidence_entries
    FOR EACH STATEMENT EXECUTE FUNCTION evidence_entries_append_only();

CREATE FUNCTION evidence_records_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'evidence records are never deleted: % is not allowed', TG_OP
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.sealed_at IS NOT NULL THEN
        RAISE EXCEPTION 'evidence record % is sealed and cannot change', OLD.job_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.job_id <> OLD.job_id OR NEW.entry_count < OLD.entry_count
            OR NEW.requested_ns IS DISTINCT FROM OLD.requested_ns THEN
        RAISE EXCEPTION 'evidence record % can only grow', OLD.job_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER evidence_records_guard
    BEFORE UPDATE OR DELETE ON evidence_records
    FOR EACH ROW EXECUTE FUNCTION evidence_records_guard();

CREATE TRIGGER evidence_records_no_truncate
    BEFORE TRUNCATE ON evidence_records
    FOR EACH STATEMENT EXECUTE FUNCTION evidence_records_guard();
