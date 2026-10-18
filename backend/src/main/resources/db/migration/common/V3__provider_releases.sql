-- Provider releases (ReleaseStore): a release rolls out a workflow version or a provider version,
-- so a row names one of the two. workflow was NOT NULL, which refused every provider release;
-- provider is the column its queries filter on (listProvider, activeProvider), as workflow is for
-- workflow releases. Rows written before this migration are all workflow releases, so their
-- provider stays null, which is what their doc says too.
ALTER TABLE releases ALTER COLUMN workflow DROP NOT NULL;
ALTER TABLE releases ADD COLUMN provider VARCHAR(200);
CREATE INDEX releases_provider ON releases (provider);
