# Security review: console-web

Branches reviewed: `console-web--phase-5`, plus the phase 9 changes on `console-web--phase-9-home` and `console-web--phase-9-retry`. The review only read the code.

## High

### 1. The console still reads the platform's `/api/v1` directly, with a client-set `X-User`
- **Where:** `console/src/lib/api.ts:157-174,344-356`.
  - The callers are `ResourceDetailPage.tsx:52`, `HomePage.tsx:21`, `RequestsPage.tsx:116`, `resource/tabs.tsx:309`, `PlatformFeed.tsx:37` and `app/console.tsx:94`.
- **Problem:** The resource record, the job list, lineage and the live SSE feed still come from `/api/v1`. No account is in the path, and the user is named by a header the browser chooses. If `/api` reaches the platform in a deployment, any user can read other accounts' records and jobs, and can act as anyone.
- **Fix:**
  - Move these reads behind the BFF (`/console/v1/r/...`, `/console/v1/jobs`, and an event stream scoped to the account), and stop sending `X-User`.
  - Make sure the edge never routes `/api/v1` from browsers.

## Low

### 2. The step-up redirect accepts protocol-relative URLs
- **Where:** `api.ts:88,200`.
- **Problem:** `//evil.example` and `/\evil` both pass `startsWith('/')`, so a crafted 403 body can redirect the person to another site.
- **Fix:**
  - Require a same-origin `/auth/` path.
  - Honour step-up only from BFF responses.

### 3. Copilot fixes and links have no host allow-list
- **Where:** `lib/explain.ts:56-59` and `explain/FailureExplainer.tsx:120-150` (phase-9-retry).
- **Problem:** Through prompt injection, a fix or a link could point somewhere the person does not expect.
- **Fix:**
  - Allow links only to a list of docs hosts.
  - On the server, limit a fix to the failed step's non-sensitive inputs.

### 4. Help links from type descriptors are not checked
- **Where:** `packages/console-kit/src/common.tsx:116-120`.
- **Problem:** A provider's schema can point the "Learn more" link at any URL.
- **Fix:** Accept only `https://`, ideally from an allow-list.

### 5. Recently visited resources stay after sign-out
- **Where:** `lib/recent.ts:16-33` and `lib/bff.ts:200-209`.
- **Problem:** On a shared browser, the next person sees what the last one visited.
- **Fix:** Clear the list on sign-out, or key it by user.

## Checked clean
- XSS: there is no `dangerouslySetInnerHTML` or `eval`.
- Return-to links.
- Tokens: kept in memory only.
- postMessage: no handlers.
- CSV escaping.
- Prefill links.
- Client-side role hints.
- Dependencies.
