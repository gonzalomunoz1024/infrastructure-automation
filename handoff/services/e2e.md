# console-e2e (whole-system run)

> Appendix to HANDOFF.md. Engineering notes recorded by the build sessions on 2026-10-23 (UTC), copied as written. "Thread" means one of the parallel build sessions; their names are listed in HANDOFF.md section 2. Commits and PR heads named here were current when written: always re-check with `git ls-remote` and the PR page. `[[name]]` links point to another note in this folder set.

---

## Note: console-e2e


Started 2026-10-23 17:50 UTC at the coordinator's request.
- Owner: thread "Cost, scorecards and end-to-end run" (cmsg_019XuFHwWhre5jyKjDMANK4vKizd9Wc3uV4GwkE2bw9dWs).
- Branch `console-e2e` (orphan), with work on `console-e2e--journey` and a PR into it.
- Contents: one docker compose with Mongo, Kafka, OpenSearch, OPA, Temporal, platform-fake-app, identity, state, BFF, indexer, drift, cost and console-web (live mode).
  - Real profiles and real BFF service tokens, no stand-ins.
  - `services.lock` pins each service branch and commit.
  - `./e2e.sh` is the one command; `.github/workflows/e2e.yml` is the CI job.
- Journey (Playwright): sign in, create VM, search, cost, inject drift on platform-fake and revert it, delete.
- Rule: service code is never changed from e2e. Integration bugs go to the owning service's thread with evidence.
- No Docker in the cloud containers, so the stack only runs in GitHub Actions.

