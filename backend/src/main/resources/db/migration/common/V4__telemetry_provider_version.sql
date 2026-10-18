-- The provider version that served each call (CallSample.providerVersion), which a provider
-- release's gates judge per version: the candidate's calls against the current version's. Null
-- when the call did not know it, as for the rows written before this column existed, which the
-- gates count as the current version's when they are older than the release.
ALTER TABLE telemetry_samples ADD COLUMN provider_version VARCHAR(100);
