-- Durable storage for the postgres profile: one table per store that the demo keeps in memory.
--
-- Each row keeps its domain object as JSON text in doc, exactly as the in-memory store holds it
-- (instants to the nanosecond, JSON values with their types); the columns beside it are what
-- queries filter and sort on. A *_ns column is the same instant as epoch nanoseconds, for exact
-- ordering and ranges (TIMESTAMP WITH TIME ZONE keeps microseconds). The SQL is portable between
-- PostgreSQL 16 and H2 in PostgreSQL mode, so the adapter tests run these same migrations; what
-- only PostgreSQL can say (the append-only triggers) is in db/migration/postgresql.

-- every write to a small, read-mostly store bumps its row, so an instance can keep a copy and
-- reload it only when another write happened
CREATE TABLE store_revisions (
    name      VARCHAR(100) PRIMARY KEY,
    revision  BIGINT       NOT NULL
);
INSERT INTO store_revisions (name, revision) VALUES ('workflows', 0);
INSERT INTO store_revisions (name, revision) VALUES ('providers', 0);
INSERT INTO store_revisions (name, revision) VALUES ('resource_types', 0);

-- one-off writes this database already has, such as the demo history
CREATE TABLE store_seeds (
    name       VARCHAR(100) PRIMARY KEY,
    seeded_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- workflows: every version of every workflow (WorkflowStorePort); doc is the WorkflowRecord
CREATE TABLE workflow_versions (
    name        VARCHAR(200) NOT NULL,
    version     VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE,
    doc         TEXT         NOT NULL,
    PRIMARY KEY (name, version)
);

-- change requests on the content repo (ChangeRequestPort)
CREATE TABLE change_requests (
    id          VARCHAR(100) PRIMARY KEY,
    name        VARCHAR(200),
    status      VARCHAR(20),
    created_at  TIMESTAMP WITH TIME ZONE,
    created_ns  BIGINT,
    doc         TEXT         NOT NULL
);

-- providers (ProviderStorePort); doc is the stored provider: definition, file, who changed it
CREATE TABLE providers (
    name        VARCHAR(200) PRIMARY KEY,
    updated_at  TIMESTAMP WITH TIME ZONE,
    doc         TEXT         NOT NULL
);

-- canary releases (ReleaseStore)
CREATE TABLE releases (
    id          VARCHAR(100) PRIMARY KEY,
    workflow    VARCHAR(200) NOT NULL,
    status      VARCHAR(20),
    started_at  TIMESTAMP WITH TIME ZONE,
    started_ns  BIGINT,
    doc         TEXT         NOT NULL
);
CREATE INDEX releases_workflow ON releases (workflow);

-- the resource registry and its types (ResourceStorePort)
CREATE TABLE resource_types (
    name  VARCHAR(200) PRIMARY KEY,
    doc   TEXT         NOT NULL
);

CREATE TABLE resources (
    id          VARCHAR(200) PRIMARY KEY,
    type        VARCHAR(200) NOT NULL,
    status      VARCHAR(40),
    created_at  TIMESTAMP WITH TIME ZONE,
    doc         TEXT         NOT NULL
);
CREATE INDEX resources_type ON resources (type);

-- audit evidence (EvidenceStore): a header per job and its chained entries. Entries are only
-- ever inserted; a header changes when an entry is appended and once when it is sealed. The
-- header's doc is the EvidenceRecord without its entries.
CREATE TABLE evidence_records (
    job_id        VARCHAR(200) PRIMARY KEY,
    workflow      VARCHAR(200),
    requester     VARCHAR(200),
    requested_at  TIMESTAMP WITH TIME ZONE,
    requested_ns  BIGINT,
    entry_count   INTEGER      NOT NULL,
    latest_hash   VARCHAR(100) NOT NULL,
    sealed_at     TIMESTAMP WITH TIME ZONE,
    doc           TEXT         NOT NULL
);
CREATE INDEX evidence_records_requested ON evidence_records (requested_ns);

CREATE TABLE evidence_entries (
    job_id  VARCHAR(200) NOT NULL,
    seq     INTEGER      NOT NULL,
    at      TIMESTAMP WITH TIME ZONE,
    kind    VARCHAR(20)  NOT NULL,
    hash    VARCHAR(100) NOT NULL,
    doc     TEXT         NOT NULL,
    PRIMARY KEY (job_id, seq)
);

-- read-only links to one record: the token's SHA-256, never the token itself
CREATE TABLE evidence_share_links (
    token_hash  VARCHAR(64)  PRIMARY KEY,
    job_id      VARCHAR(200) NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE,
    doc         TEXT         NOT NULL
);

-- service accounts (ServiceAccountStore): the token's hash only
CREATE TABLE service_accounts (
    id          VARCHAR(100) PRIMARY KEY,
    token_hash  VARCHAR(64),
    created_at  TIMESTAMP WITH TIME ZONE,
    created_ns  BIGINT,
    doc         TEXT         NOT NULL
);
CREATE UNIQUE INDEX service_accounts_token ON service_accounts (token_hash);

-- natural-language builder sessions (SessionStore)
CREATE TABLE assistant_sessions (
    id          VARCHAR(100) PRIMARY KEY,
    requester   VARCHAR(200),
    status      VARCHAR(20),
    updated_at  TIMESTAMP WITH TIME ZONE,
    doc         TEXT         NOT NULL
);

-- provider-call samples (TelemetryPort), kept for platform.persistence.telemetry-retention
CREATE TABLE telemetry_samples (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    at           TIMESTAMP WITH TIME ZONE NOT NULL,
    at_ns        BIGINT       NOT NULL,
    provider     VARCHAR(200),
    action       VARCHAR(200),
    environment  VARCHAR(100),
    ok           BOOLEAN      NOT NULL,
    duration_ms  BIGINT       NOT NULL,
    error_class  VARCHAR(200),
    job_id       VARCHAR(100),
    workflow     VARCHAR(200),
    version      VARCHAR(100)
);
CREATE INDEX telemetry_samples_at ON telemetry_samples (at_ns);
CREATE INDEX telemetry_samples_provider ON telemetry_samples (provider, at_ns);

-- the runs index (ProjectionStore): jobs with their steps and approvals, each job's event history.
-- A job's doc leaves out its definition snapshot, which is stored once per distinct definition.
CREATE TABLE runs_definitions (
    hash  VARCHAR(64) PRIMARY KEY,
    doc   TEXT        NOT NULL
);

CREATE TABLE runs_jobs (
    id               VARCHAR(100) PRIMARY KEY,
    workflow         VARCHAR(200),
    version          VARCHAR(100),
    status           VARCHAR(40),
    kind             VARCHAR(20),
    parent_id        VARCHAR(100),
    row_index        INTEGER,
    started_at       TIMESTAMP WITH TIME ZONE,
    started_ns       BIGINT,
    definition_hash  VARCHAR(64),
    doc              TEXT         NOT NULL
);
CREATE INDEX runs_jobs_started ON runs_jobs (started_ns);
CREATE INDEX runs_jobs_workflow ON runs_jobs (workflow, started_ns);
CREATE INDEX runs_jobs_parent ON runs_jobs (parent_id);

CREATE TABLE runs_approvals (
    id            VARCHAR(100) PRIMARY KEY,
    job_id        VARCHAR(100) NOT NULL,
    status        VARCHAR(20),
    requested_at  TIMESTAMP WITH TIME ZONE,
    requested_ns  BIGINT,
    doc           TEXT         NOT NULL
);
CREATE INDEX runs_approvals_requested ON runs_approvals (requested_ns);

CREATE TABLE runs_events (
    job_id  VARCHAR(100) NOT NULL,
    seq     BIGINT       NOT NULL,
    at      TIMESTAMP WITH TIME ZONE,
    type    VARCHAR(20),
    doc     TEXT         NOT NULL,
    PRIMARY KEY (job_id, seq)
);
