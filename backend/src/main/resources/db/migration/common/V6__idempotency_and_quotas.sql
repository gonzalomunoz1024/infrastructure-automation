-- The gateway's guards and the inbox, shared by every API replica with the postgres profile
-- (the demo keeps them in memory, per instance).

-- Idempotency keys of the requests that start jobs (IdempotencyStore): one row per caller and
-- key with the fingerprint of the request the key was first used with, when it was claimed and
-- until when it answers; status, headers (JSON) and body are the first answer once that request
-- succeeded, NULL while it is still in flight. A claim inserts the row or yields to the live
-- one, a release deletes an unanswered row, a sweep deletes the rows past their expiry.
CREATE TABLE idempotency_keys (
    caller       VARCHAR(200) NOT NULL,
    request_key  VARCHAR(255) NOT NULL,
    fingerprint  VARCHAR(200) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE,
    created_ns   BIGINT       NOT NULL,
    expires_ns   BIGINT       NOT NULL,
    status       INTEGER,
    headers      TEXT,
    body         TEXT,
    PRIMARY KEY (caller, request_key)
);
CREATE INDEX idempotency_keys_expiry ON idempotency_keys (expires_ns);

-- The per-team job quotas (QuotaStore): one token bucket per team (or per caller in no team),
-- its tokens and when they were last refilled. A take locks the row, refills it to now and takes
-- one token, so every replica draws from the same bucket. A bucket idle for a day is full again,
-- so a sweep deletes such rows.
CREATE TABLE quota_buckets (
    bucket   VARCHAR(200)     NOT NULL PRIMARY KEY,
    tokens   DOUBLE PRECISION NOT NULL,
    last_ns  BIGINT           NOT NULL
);

