-- Provider versions kept beside the current one (ProviderStorePort.saveVersion): a version
-- published with the canary strategy goes here, where its provider release routes calls to it,
-- until it is promoted and saved as the current version (the row in providers). Every version
-- saved since this database was made is kept, the current one among them, so versions(name)
-- reads the same as in memory: newest first by semantic version. A database written before this
-- table existed lists its current versions alone until the next save. The doc is the stored
-- provider (parsed definition, YAML as written, who changed it); a kept version is never written
-- back to the content repo until it is current.
CREATE TABLE provider_versions (
    name        VARCHAR(200) NOT NULL,
    version     VARCHAR(100) NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE,
    doc         TEXT         NOT NULL,
    PRIMARY KEY (name, version)
);
