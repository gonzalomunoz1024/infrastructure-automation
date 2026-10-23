# Context pack: everything about the product, in one folder

`HANDOFF.md` says what is left to do. This folder explains what is being built and why, so an agent with no access to the build sessions, the owner's chat, the claude.ai documents or the diagram pages can understand the whole system. Nothing here is a summary that replaces a source: the sources themselves are copied in, and the files written for this pack say where each fact comes from.

Read in this order (about 3 hours for everything, 45 minutes for the files marked ★):

| # | File | What it gives you |
|---|---|---|
| 1 ★ | `vision.md` | The two products, the two personas, the ultimate goal, and why each big choice was made |
| 2 ★ | `glossary.md` | Every term you will meet: wfrn, hub, steward, turn, gate, WP, P-xx, decision numbers, F-numbers, platform-fake and the rest |
| 3 ★ | `platform-overview.md` | The automation platform on `main`, explained: designer, workflows as YAML in Git, the Temporal interpreter, providers and the wizard, Ansible, chaining, approved features, the Devin builder, terminology |
| 4 | `platform/main-*.md` | Verbatim copies of `main`'s own docs (at c9529d5): README, architecture, config as code, API, design language, getting started, contributing, both walkthroughs (onboard a VM provider, create a VM in the designer) and both Devin playbooks. Screenshots referenced there live on `main` under `docs/screenshots/` |
| 5 ★ | `journeys-platform.md` | Eight platform journeys as numbered steps, in plain words and technically |
| 6 ★ | `journeys-console.md` | Seven console journeys as numbered steps, in plain words and technically |
| 7 | `console-plan.md` | The full customer console plan document as Markdown (revision 72): summary, platform baseline, personas and IAM, wfrn, the dynamic console, each backend service, scale/regions/observability/security, repositories, Beyond the AWS console, every work package 0.1 to 9.22 with dependencies, lanes and ETAs, the phase gates, risks and the ten decisions |
| 8 ★ | `decisions.md` | An index of hub decisions 1 to 35 and platform changes P-01 to P-23, each with its one-line answer, why it was taken, and where the full text is |
| 9 | `hub/platform-changes-README.md` | The complete platform-changes plan, P-01 to P-23, with rationale, shapes, tests, risks, PR order and section 11 (building against the target API) |
| 10 | `hub/contracts-README.md`, `hub/contracts-wfrn-SPEC.md`, `hub/rule-packs-README.md`, `hub/blueprints-README.md` | The hub's contract rules, the wfrn specification, how compliance rules and blueprints are written |
| 11 | `../HUB-README-STACK-SNAPSHOT.md` | The hub README with the full text of decisions 1 to 35 |
| 12 ★ | `services.md` | One page per service: what it owns, its design, its data and events, and where the details are |
| 13 | `services/README-<branch>.md` | Verbatim READMEs from each service's newest branch (libs, BFF phase 9, identity phase 7, state blueprints, drift finder-and-monitoring, search load, cost phase 7, web phase 7, e2e journey, SDK). These hold each service's own decision tables, run commands and API lists |
| 14 ★ | `design-language.md` | Atlas, the Apple/Jony Ive restraint, the n8n canvas, and the AWS console layout the console copies |
| 15 | `../services/*.md` | The build sessions' engineering notes per service (gotchas, rules, why) |

If two sources disagree, the newer one wins: service READMEs on the newest branch, then the hub stack (`console-root--stack`), then the plan, then the engineering notes.
