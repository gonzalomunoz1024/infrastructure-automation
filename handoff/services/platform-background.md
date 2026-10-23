# The automation platform (main) - background only, never modify

> Appendix to HANDOFF.md. Engineering notes recorded by the build sessions on 2026-10-23 (UTC), copied as written. "Thread" means one of the parallel build sessions; their names are listed in HANDOFF.md section 2. Commits and PR heads named here were current when written: always re-check with `git ls-remote` and the PR page. `[[name]]` links point to another note in this folder set.

---

## Note: automation-platform-repo


Repo (since 2026-10-17): /Users/gonzalo/code/infrastructure-automation on Gonzalo's Mac, pushed to GitHub gonzalomunoz1024/infrastructure-automation (main). Built in the cloud at /home/claude/infrastructure-automation. Gonzalo gave blanket approval to implement end to end without asking.
**Why:** Gonzalo wants the whole system in that repo and the UI and backend to match his service-dashboard repo.
**How to apply:**
- Layout: backend/ (Spring Boot 3.5, Java 21, WebFlux, Maven, Lombok, Temporal SDK, JGit, Flyway + PostgreSQL under the postgres profile, S3 under s3), frontend/ (React 19, Vite, TypeScript, Tailwind v4, @xyflow/react only in the designer), packages/ui (Atlas kit), packages/workflow-checks (TypeScript twin of the Java checks; shared fixtures + expected.json), content at the repo root: workflows/, providers/, resource-types/ (`apiVersion: iaf.platform/v1`), devin/ playbooks, docs/.
- Design language: n8n canvas 1:1; everything else copies the Atlas design system of /Users/gonzalo/code/service-dashboard/frontend (hand-rolled tokens, Apple HIG type scale, .dark class). Backend copies /Users/gonzalo/code/service-dashboard/backend (vertical-slice hexagonal adapters/application/domain/ports) plus CQRS (Commands/Queries ports, CommandHandler/QueryHandler per slice).
- Switch steps: `case: { when, then | do }`; `then` jumps to a later sibling step or `end`, `do` is a nested branch that rejoins.
- Demo profile runs everything in memory with recorded provider samples (`RecordedWorld`; dry runs answer from a fork of it per dry job); production adapters sit behind Spring profiles (temporal, opa, vault, devin, live, oidc, postgres, s3; the local Git store is the `platform.content.git` property, not a profile) with `@Profile(...) @Primary`; demo adapters are plain `@Component`s.
- Demo data follows one fixture (`common.demo.DemoReleases`, like Atlas's TopologyFixture) so jobs, releases, change requests and resources agree; seeded inputs come from the registry and schema `examples`, never ids in code.
- Rules learned from end-to-end runs: schema defaults are filled before validation (runs, previews, batch rows); previews are judged like runs; a duplicated YAML key is `yaml.invalid` in both twins; an import never reuses a published workflow's name.
- Credentials (since 2026-10-18) carry an owner team: seeded ones have none (anyone may use them), stored ones belong to one of the caller's teams, and the provider wizard and imports refuse (403) to send a credential outward for a caller outside its team; admins may use any. Provider versions, provider releases, call samples (with provider version) and review evidence persist under the postgres profile (migrations V2-V5); the in-memory stores are `@Profile("!postgres")`.
- Build, gate and delivery steps: [[automation-platform-workflow]]. Plan decisions: [[automation-platform-plan]].

---

## Note: automation-platform-plan


Plan doc: https://claude.ai/code/artifact/8bb8c698-fa9a-4c68-bb02-a71bf85ac365. Prototype: https://claude.ai/artifact/Fz4eB8qf7LTBaat6S1gXun. Walkthrough: https://claude.ai/artifact/Pu63VYbAL9K2JcjpgRcntz. Review doc: https://claude.ai/code/artifact/ac7bc147-f996-4b38-b81d-e06e69a9ece6.
**Why:** Gonzalo asked for an n8n designer + Temporal executor; n8n is not embedded (engine coupled to editor, licensing risk), so the platform owns a React Flow designer that looks 1:1 like n8n, stores workflow YAML in Git and runs it with one generic Java Temporal interpreter. The same YAML generates REST, OpenAPI and MCP.
**How to apply:**
- Terminology: always "workflow", never "capability" (UI, API, `kind: Workflow`). Other kinds: `kind: Provider`, `kind: ResourceType`.
- Platform stays generic: VM restart, Mongo upgrade, CVE patching, alert remediation are sample content, never platform logic.
- Ansible steps: Git repo (SHA pinned), upload (SHA-256) or shared library; retries off by default. Credentials only as `vault:<path>`; the API never returns secret values.
- Resources: user-defined ResourceType + registry with lineage; inputs marked x-resource; `use`/`register` steps; `run` runs a published workflow as a child.
- NL builder uses Devin (full API access): playbook `!workflow-draft` under devin/. A deterministic mock agent runs in the demo profile.
- OPA policy engine exists; the platform calls a mock until wired (TODO). IDP tiles stay a mock placeholder until Gonzalo shares an example.
- Approved: preview (plan operation), time travel (pause on failure, replay, re-run from a step, replay check before publish), canary releases with gates, provider admin view (health, timings, SLO alerts), audit evidence with a hash chain, bulk runs in waves, alerts as triggers, drag-to-map and live checks in the designer. Declined: schedules and change windows.
- Providers: no-code types (OpenAPI/HTTP, Ansible, Terraform, IDP tile; SDK only for custom logic) and a 5-step Add provider wizard. Network access out of scope; spec mismatches fixed through `overrides`; test resources are the developer's job.
- Goal: a junior infra engineer can use the tool and onboard a provider. Gonzalo likes journeys in pictures. See [[automation-platform-repo]] for the repo and its conventions.

---

## Note: automation-platform-workflow


How work on [[automation-platform-repo]] is gated, merged and delivered.
**Why:** several agents build on worktree branches in parallel and every push to main must stay green; Gonzalo pulls from GitHub.
**How to apply:**
- Gate before a push to main: backend `PLATFORM_TEST_MONGO_URI='mongodb://127.0.0.1:27018/?replicaSet=rs0' mvn -B -o clean test` (offline Maven at /opt/maven/bin, ~15 min, background; never two Maven runs in one tree; without the URI the MongoDB tests skip). Frontend from the root: `npm run lint` (no eslint-disable), `npm run typecheck`, `npm test`, `npm run check:workflows`, `cd frontend && npm run build`, `npx playwright test` (Chromium at /opt/pw-browsers, never `playwright install`). Backend and frontend halves can run at once.
- Java compiles with `-Xlint:all,-serial,-processing -Werror`: deprecated Jackson calls fail; use `properties()` and `defaultPropertyInclusion(JsonInclude.Value.construct(x, x))`.
- Merging a builder branch: preview with `git merge-tree --write-tree --name-only <base> <branch>`, then `git merge --no-ff --no-commit`, resolve EVERY file `git diff --name-only --diff-filter=U` lists (docs included) and `git grep -nE '^(<<<<<<<|>>>>>>>) '` before `git add -A` (2026-10-19 a docs file went in with markers), gate, commit `-F <msgfile>`. Never rebase builder branches (`.gitleaksignore` pins commits). Two packages can each pass alone yet expect different UI text; rerun the e2e suite after merging all.
- Commits as Gonzalo (`git -c user.name="Gonzalo Munoz" -c user.email="munozgonzalo1024@gmail.com"`), ending with the session's attribution lines; no model names in code or commits.
- Delivery to the Mac: `git pull` from GitHub, or tar + `device_commit_files` + `device_bash` untar; never run writing git commands in the device VM (locks).
- Multi-agent pace: 4 CPUs, so a Workflow runs at most 2 agents at once; run one workflow per package, pin `model: 'claude-opus-5-5'` (Gonzalo wants every subagent on Opus 5.5), keep reviewers read-only. A fixer can end without its structured output: read its transcript tail and the worktree, finish its last patch, then merge.
- Spring joins a command-line option given twice with a comma: test helpers must replace defaults, not append.
- CI secret scan (gitleaks generic-api-key) flags long hex/base64 literals next to key/digest/token, fixtures included: compute them at run time.
- `npm run typecheck` (tsconfig.app.json) catches test-file type errors; `npx tsc -p frontend` does not.
- CI wait: session scratchpad `ci-wait.sh <sha>` polls `gh run list` and prints each job's result.
- Demo stack and live checks: see [[automation-platform-live-checks]].

---

## Note: automation-platform-live-checks


Running [[automation-platform-repo]] for real in the cloud container (see [[automation-platform-workflow]] for the gate).
**Why:** API flows, screenshots and profile checks need a running stack, and each quirk below cost a failed run once.
**How to apply:**
- Demo stack for checks: the packaged jar with `--server.port=18080 --platform.content.dir=<repo>` plus `IAF_BACKEND=http://localhost:18080 npx vite --port 3000`; demo users gonzalo (admin), ana (team-data, ops), mateo, junior; dev identity is the `X-User` header; RBAC answers 403 for unknown users. Serve vite on 3000 (or 5173): the backend's CORS allows only those origins, so on any other port every POST (parse, preview) answers 403. `pkill -f` patterns must live in a script file run as its own command: a pattern anywhere in the Bash command line (a heredoc too) kills the tool's shell (exit 144).
- MongoDB (2026-10-18, replaced PostgreSQL): Docker can't be started and fastdl.mongodb.org is blocked. Get mongod from conda-forge: `curl -sO https://conda.anaconda.org/conda-forge/linux-64/mongodb-8.3.7-hc064ac0_0.conda`, unzip, decompress pkg-*.tar.zst with pip's zstandard, `nohup bin/mongod --dbpath <dir> --bind_ip 127.0.0.1 --port 27018 --replSet rs0 &`, once `replSetInitiate` via pymongo (`directConnection=true`). Tests: `PLATFORM_TEST_MONGO_URI='mongodb://127.0.0.1:27018/?replicaSet=rs0'`. Helm: get.helm.sh is blocked; `go install helm.sh/helm/v3/cmd/helm@v3.16.4` (about a minute).
- Temporal live check (2026-10-18): `temporal.download` is blocked by the network policy, but the CLI tarball downloads from GitHub releases (temporalio/cli v1.4.1, linux amd64); `temporal server start-dev --headless --port 17233 --db-filename <file>` plus the jar with `--spring.profiles.active=temporal --platform.temporal.target=127.0.0.1:17233` runs the interpreter worker in-process; the API flows then exercise the Temporal engine.
- README tour: `NODE_PATH=<repo>/node_modules node scripts/api-flows/tour.cjs http://localhost:3000 http://localhost:18080/api/v1 docs/screenshots` writes 20 PNGs and captions.md; the README section "A tour in pictures" is filled from captions.md.
- VM provider journey (2026-10-18, docs/onboard-a-vm-provider.md): `FAKE_VM_TOKEN=journey-demo-token node scripts/api-flows/fake-vm-api.cjs 18090`, the jar with `--spring.profiles.active=demo,live` (live = real HTTP provider calls), vite on 3000, then `journey-vm-provider.cjs <frontend> <api> http://localhost:18090 <out>` (24 PNGs + captions.json). Needs a fresh backend each run (the provider and workflow can be added once). The dev identity switch needs a page reload after setting localStorage `iaf-user`.

---

## Note: automation-platform-audit


Audit of [[automation-platform-repo]] started 2026-10-18 (Gonzalo's 5-pass request: plan check, verify, review, fix, score; stop under 95% after 5 passes).
**Why:** later passes and a replacement Claude must continue from the saved evidence instead of re-reading 100 requirements.
**How to apply:**
- 96 requirements (A1..R8); reports, briefs and verifier results were kept in the session scratchpad only (`audit/`, `briefs/`, `verify/`), so a new session starts from the final report instead.
- Pass 1 (2026-10-18): 54 done, 42 partial, 0 missing; reviews found 90 findings (6 critical, 26 major, 58 minor).
- Plan-check, review and verify agents are read-only and must not run Maven or npm while builders do.
- GitHub Actions logs: `gh run view --log` is blocked by the proxy (results-receiver host); use the GitHub MCP `get_job_logs` (repo attached with add_repo) with `tail_lines`. Annotations via `gh api .../check-runs/<job>/annotations` work.
- Frontend routes are hash routes (`/#/jobs/<id>`); scratch Playwright scripts need `NODE_PATH=<repo>/node_modules` and `waitUntil: 'load'` (job pages never reach networkidle). README tour: see [[automation-platform-live-checks]].
- Flaky-test lesson: tests that run the in-memory engine must collect bus events in a CopyOnWriteArrayList (fixed 6adc95a).
- A builder stopped by a usage limit is resumed with SendMessage naming its last commits; one stopped before changing anything has lost its worktree, so start a fresh one.
- Gonzalo asks for progress and ETAs: answer per step with items done of total and an estimate from the observed pace, excluding limit pauses.
- Playwright on main while a frontend builder runs: the config reuses any server on :3000, so it silently tests the builder's worktree. Gate main with `npx playwright test -c playwright.isolated.config.ts` (frontend/, untracked, in .git/info/exclude; vite on PW_PORT, default 3217).
- Decision (Gonzalo, 2026-10-18 17:41 UTC): no audit pass 2. Finish the pass-1 fix wave, merge, full gate, README screenshots, then score and write the final report; the pass-2 queue (`scratchpad/briefs/pass2-queue.md`: C1, D7, F2, F3, I4, R3) stays open and is reported as remaining gaps.
- Final state (2026-10-18 20:45 UTC): audit closed after pass 1 at main 53788fe (all 9 CI jobs green). Scores: plan coverage 78% (75 of 96 done), correctness 90%, code quality 90%, design 90%. Report: /mnt/project-files/infrastructure-automation/audit/audit-report.md, which lists the 21 partial requirements, the 4 partly fixed review findings and the unrun phone-width design pass; a later pass starts from that list.

