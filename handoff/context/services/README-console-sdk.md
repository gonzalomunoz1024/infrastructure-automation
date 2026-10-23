# console-sdk

The customer console's API clients: the `console` CLI, the TypeScript SDK and the Java SDK. They
are generated from the BFF's contract, which this branch pins the way a service does (decision 31.2
of the hub, work packages 7.4 and 9.15). Python and Go SDKs come next, from the same contract.

| Path | What it holds |
| --- | --- |
| `openapi/console-bff.yaml` | The BFF's contract, copied unchanged from the hub |
| `contracts.lock` | The hub commit it was copied from, and its sha256 |
| `packages/sdk` | `@iaf/console-sdk`: types generated from the contract, and a fetch client |
| `packages/cli-core` | `@iaf/console-cli-core`: one command per operation, parsing, help and output; runs in a browser too (the console shell) |
| `packages/cli` | `@iaf/console-cli`: the `console` command, with `login`, `logout`, `whoami` and `configure` |
| `java/` | `com.iaf.console:console-sdk`: generated API classes and the hand-written `ConsoleClient` |
| `scripts/generate.mjs` | Regenerates the TypeScript files from the contract |
| `test/` | The pin's test, the generation check, and the stub BFF the end-to-end tests run against |

Development needs Node 22 and Java 21. The npm packages run on Node 20 or later.

## The pinned contract

`openapi/console-bff.yaml` is the hub's `contracts/openapi/console-bff.yaml` at commit `43483d8`,
the head of `console-root--stack`, which carries every open hub decision. When the hub PR
`console-root--bff-clients` opens (README rows 31 and 32 and the additions to `console-bff.yaml`),
the pin moves to it. `test/contracts-lock.test.mjs` and the Java build's `ContractsLockTest` fail
when the copy drifts from the hash in `contracts.lock`.

The pinned contract does not have everything decision 31 describes yet:

- **The device sign-in** (`POST /auth/device`, `POST /auth/token`, `POST /auth/revoke`) is not in
  it. Both SDKs write these calls by hand, from RFC 8628 and decision 31.1.
- **`console jobs retry`** has no operation yet. It becomes a command once the contract has one.
- **Writes require `X-Csrf-Token`**, which only a browser session sends. A bearer call needs none:
  the TypeScript client sends no CSRF token unless told to, and Java callers pass
  `ConsoleClient.NO_CSRF`, which leaves the header out.

The CLI makes no commands for `/auth/*` (it signs in with `login`) or for `/internal/*` (in-cluster
callers only).

## The CLI

Until it is published, install it from a checkout:

```sh
npm ci
npm run build
npm install --global ./packages/cli     # links the `console` command
```

```sh
console login                       # prints a code and the page to approve it at, and opens it
console configure set endpoint https://console.example.com
console configure set account 111111111111
console whoami
console resources list vm --tag.env prod --all
console jobs get job-1 -o yaml
console commands create-resource vm --body-file vm.json
console cost export-chargeback --month 2026-09 > chargeback.csv
console events stream               # one JSON line per event, until interrupted
console logout                      # revokes the API session
```

Each operation of the contract is a command, grouped by its tag: `console <group> <command>`. Path
parameters are arguments (and flags), query parameters are flags, and a body is `--body <json>` or
`--body-file <path>` (`-` reads standard input). Run `console --help`, `console <group> --help`, or
`console <group> <command> --help` to see them.

- **Output** is `json`, `table` or `yaml`. The format is `-o`, else `CONSOLE_OUTPUT`, else `json`
  when standard output is not a terminal, else the configured one, else `table`.
- **Files**: `config.json` and `credentials.json` live in `~/.config/console/` (else
  `$XDG_CONFIG_HOME/console`, or `$CONSOLE_CONFIG_DIR`). Both have mode 0600 in a 0700 directory,
  and each endpoint keeps its own tokens. Commands that run at the same time share one refresh
  through a lock file, because the refresh token rotates and the BFF ends the session when a used
  one comes back.
- **Environment**: `CONSOLE_ENDPOINT`, `CONSOLE_ACCOUNT`, `CONSOLE_REGION` and `CONSOLE_OUTPUT`
  win over the configuration. The default endpoint is `http://localhost:18081`.
- **Exit codes**: 0 done, 1 the call failed (the BFF's error and its Denial go to standard
  error), 2 the command line is wrong.

## The TypeScript SDK

```ts
import { ConsoleApiError, createConsoleClient, deviceLogin, refreshingCredentials } from '@iaf/console-sdk';

const endpoint = 'https://console.example.com';
const tokens = await deviceLogin({
  endpoint,
  onCode: (code) => console.log(`Open ${code.verificationUriComplete ?? code.verificationUri} and enter ${code.userCode}`),
});
const client = createConsoleClient({
  endpoint,
  // the refresh token rotates: keep each new one
  credentials: refreshingCredentials({ endpoint, tokens, onRefresh: (next) => save(next) }),
});

const job = await client.call('getJob', { path: { jobId: 'job-1' } });
for await (const failed of client.paginate('listJobs', { query: { status: 'failed' } })) {
  console.log(failed.jobId);
}
try {
  await client.call('runVerb', { path: { type: 'vm', wfrn, verb: 'stop' }, body: { input: {} } });
} catch (e) {
  if (e instanceof ConsoleApiError && e.denied) console.log(e.denied.reason, e.requestable);
}
```

`call`, `send`, `pages`, `paginate` and `events` take an operation id. The id types the path, the
query and the body, and the answer comes typed too. `request(method, path)` covers anything else.
The console shell runs the CLI's command core in the page, with the page's own session:

```ts
import { createConsoleClient } from '@iaf/console-sdk';
import { execute } from '@iaf/console-cli-core';

const client = createConsoleClient({ endpoint: location.origin, headers: { 'X-Csrf-Token': csrfToken } });
const { exitCode, stdout, stderr } = await execute(['jobs', 'list', '-o', 'table'], client);
```

## The Java SDK

`cd java && ./gradlew build` generates the API classes and models from `../openapi/console-bff.yaml`
(the OpenAPI Generator, library `native`: Java 21's `HttpClient`), then compiles and tests them. It
writes `build/libs/console-sdk-<version>.jar`. The jar needs Jackson and Apache httpmime at run
time.

```java
ConsoleAuth auth = ConsoleAuth.builder("https://console.example.com").build();
TokenSet tokens = auth.deviceLogin(code ->
        System.out.println("Open " + code.verificationUriComplete() + " and enter " + code.userCode()));
RefreshingCredentials credentials = RefreshingCredentials.builder()
        .auth(auth)
        .tokens(tokens)
        .onRefresh(next -> save(next)) // the refresh token rotates: keep each new one
        .build();

try (ConsoleClient console = ConsoleClient.builder()
        .endpoint("https://console.example.com")
        .credentials(credentials)
        .build()) {
    JobsApi jobs = console.api(JobsApi.class);
    JobDetail job = console.call(() -> jobs.getJob("job-1"));
    console.items(after -> jobs.listJobs(null, "failed", null, null, 200, after), ListJobs200Response::getItems)
            .forEach(failed -> System.out.println(failed.getJobId()));
    CommandsApi commands = console.api(CommandsApi.class);
    try {
        console.call(() -> commands.runVerb(ConsoleClient.NO_CSRF, "vm", wfrn, "stop", new CommandInput(), null));
    } catch (ConsoleApiException e) {
        if (e.denied() != null) System.out.println(e.denied().getReason());
    }
}
```

Every generated API class sends through the client's `HttpClient`, so the behaviour below applies to
them all. `console.call(...)` and `console.run(...)` turn what they throw into a typed
`ConsoleApiException`. The generator cannot read an event stream or a CSV answer, so use
`console.events(query)` for `/console/v1/events` and `console.request(method, path, query, body,
headers)` for `/console/v1/cost/chargeback.csv` and other calls by path. A price list's `monthly`
is a `JsonNode`: either a number or a map of numbers.

## What both SDKs do on every call

- **Bearer auth.** The access token goes on every call. After a 401 with
  `WWW-Authenticate: Bearer error="invalid_token"`, the client refreshes once and sends the call
  again. A second 401 is the caller's to handle.
- **Refresh.** The token is also refreshed shortly before it expires. Calls that need a refresh at
  the same time share one, and each rotated refresh token goes to `onRefresh` (or the token store)
  before it is used.
- **Idempotency keys.** Every POST, PUT, PATCH and DELETE carries an `Idempotency-Key`, a random
  UUID unless the caller gives one. Every attempt of a call carries the same key.
- **Retries.** A 429 or 503 is retried up to 3 times, after `Retry-After` (seconds or a date; 1, 2
  and 4 seconds when there is none). It is not retried when the wait asked for is longer than 60
  seconds. Only idempotent calls are retried: GET, HEAD, OPTIONS, PUT and DELETE, and the writes
  whose operation takes an `Idempotency-Key`.
- **Typed errors.** A refusal throws `ConsoleApiError` (TypeScript) or `ConsoleApiException`
  (Java), with the status and the BFF's `error`, `problems`, `denied` (the Denial: layer, action,
  reason, role, policy and statement, whether access may be requested), `detail` (a step-up) and
  `correlationId`. The device sign-in throws `OAuthError` or `OAuthException`.
- **Paging.** Lists answer `{ items, next }`. The client sends `next` back as `after` until it is
  null, and stops if a cursor comes back twice.

## Regenerating

- **TypeScript**: `npm run generate` rewrites `packages/sdk/src/generated/` and
  `packages/cli-core/src/generated/` from the contract, and the generated files are committed.
  `npm run generate:check` and `test/generated.test.mjs` fail when they are stale.
- **Java**: `./gradlew build` regenerates on every build (`openApiGenerate` and
  `writeContractInfo`), so there is nothing to commit.
- **Moving the pin**: copy the hub's `contracts/openapi/console-bff.yaml` at the new commit over
  `openapi/console-bff.yaml`, and update `contracts.lock` (branch, commit, sha256). Then run
  `npm run generate`, `npm test` and `./gradlew build`.

## Tests

- `npm test`: unit tests of the client (refresh, retries, keys, errors, paging), of the device
  sign-in's polling against an RFC 8628 stub, and of the CLI's parsing, help and formatting. It also
  runs the built CLI end to end against a stub BFF (`test/support/stub-bff.mjs`). No test starts a
  real BFF.
- `cd java && ./gradlew build`: JUnit tests against a stub BFF on `com.sun.net.httpserver`.
- CI (`.github/workflows/ci.yml`) runs both on every push and pull request to `console-sdk`.
