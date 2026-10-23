# Security review: console-search-indexer

The branch reviewed is `console-search-indexer--transfers`. The review only read the code.

## Medium

### 1. The shared `console` audience is accepted
- **Where:**
  - `application.yaml:66`
  - `boot/SearchProperties.java:144`
  - `auth/adapters/web/JwtVerifier.java:123-126`
- **Problem:** A BFF service token minted for identity has no `acct`, and it can be replayed here. Here it counts as internal, which reaches search, suggest, counts and interpret on any account, and `/v1/index:rebuild`.
- **Fix:**
  - Accept only `console-search-indexer`.
  - Remove `console` from the defaults, the yaml and the helm values.

### 2. Any token without `acct` reads any tenant
- **Where:** `auth/domain/ConsolePrincipal.java:27,40` and `SearchController.java:106-113`.
- **Problem:** `svc` is never checked, and counts across up to 200 accounts per call are open to any such caller.
- **Fix:**
  - Count a caller as internal only when its `svc` is on an allow-list, for example console-bff for everything and the drift and scorecard services for counts only.
  - Reject a token that has neither `acct` nor `svc`.

## Low

### 3. The actuator is open on the API port
- **Where:** `ConsoleAuthWebFilter.java:51`.
- **Problem:** NetworkPolicy is off by default, so anyone who can reach the pod can read the actuator.
- **Fix:**
  - Move the actuator to its own management port.
  - Turn the NetworkPolicy on by default.

### 4. Rebuild and poll trust the account id in the state service's JSON
- **Where:** `Documents.java:45` and `IndexingService.java:108-117`.
- **Problem:** The wfrn's account and the scope's account are not compared with the record's account id.
- **Fix:** Skip and count any record unless the wfrn's account, the JSON `accountId` and `scope.accountId()` all agree.

### 5. Transfer tombstones never expire
- **Where:** `ResourceEvents.java:86,140-145`.
- **Problem:** After a transfer, the old account keeps searching the resource's last name, tags and fields indefinitely.
- **Fix:** Strip the fields and tags from the tombstone, or give it an expiry.

## Checked clean
- **DSL and query injection:** every query is structured and carries a mandatory accountId term filter and routing.
- **Interpret:** it cannot widen the account.
- **Another account:** a person who names one gets a 404.
- **Kafka:** checks the event's account against the wfrn.
- **JWT:** everything apart from the audience is checked.
- **Service tokens:** handled correctly.
- **Sensitive fields:** an allow-list keeps them out of the index.
- **SSRF:** outbound URLs come only from config.
