import { describe, expect, it } from 'vitest'
import { fromCmd, httpWithFromCurl, isSecretField, isSecretHeader, parseCurl, shellWords, type CurlRequest } from './curl'

/*
 * Import cURL: the commands people paste (a browser's "Copy as cURL", API docs, a terminal) become
 * the HTTP step's method, URL, headers and body. Nothing that signs in survives the import; the
 * fixtures' secrets are obviously fake, and every test checks they are gone from what comes out.
 */

function ok(text: string): CurlRequest {
  const r = parseCurl(text)
  if (!r.ok) throw new Error(r.error)
  return r.request
}

/** Fails when a fake secret is anywhere in what the parser returned. */
function expectNoSecret(r: CurlRequest, ...secrets: string[]) {
  const all = JSON.stringify(r)
  for (const s of secrets) expect(all, `${s} survived`).not.toContain(s)
}

describe('the words of a command', () => {
  it('splits on blanks and honours the quotes a shell does', () => {
    expect(shellWords(`curl -H 'X-A: one two' "https://h/x" plain`)).toEqual(['curl', '-H', 'X-A: one two', 'https://h/x', 'plain'])
    expect(shellWords(`a "say \\"hi\\" \\$HOME \\\\ \\n" b`)).toEqual(['a', 'say "hi" $HOME \\ \\n', 'b'])
    expect(shellWords(`a 'it'\\''s' b`)).toEqual(['a', "it's", 'b'])
    expect(shellWords(`a $'line\\none\\ttab \\x41\\u00e9 \\'q\\''`)).toEqual(['a', "line\none\ttab Aé 'q'"])
    expect(shellWords('a\\ b c')).toEqual(['a b', 'c'])
    expect(shellWords(`a 'open`)).toBeNull()
    expect(shellWords(`a "open`)).toBeNull()
    expect(shellWords(`a $'open`)).toBeNull()
  })

  it('carries a command over lines ended by \\ (bash), ^ (cmd) or a backquote (PowerShell)', () => {
    expect(shellWords('curl \\\n  -X POST \\\r\n  https://h')).toEqual(['curl', '-X', 'POST', 'https://h'])
    expect(shellWords('curl ^\n  -X PUT ^\n  https://h')).toEqual(['curl', '-X', 'PUT', 'https://h'])
    expect(shellWords('curl `\n  https://h')).toEqual(['curl', 'https://h'])
  })

  it("reads a command copied for cmd as cmd does: ^ takes the next character as it is, outside cmd's own quotes", () => {
    expect(fromCmd('curl ^"https://h/x?a=1^&b=^%^20^" ^\n  -H ^"x: ^\\^"q^\\^"^"')).toBe('curl "https://h/x?a=1&b=%20"   -H "x: \\"q\\""')
    // a caret and two line breaks are a line break in the text; inside plain quotes a caret is a caret
    expect(fromCmd('a ^"one^\n\ntwo^" "x^y"')).toBe('a "one\ntwo" "x^y"')
    expect(shellWords('curl ^"https://h/x^" ^\n  -H ^"accept: */*^" --data-raw ^"^{^\\^"a^\\^":1^}^"')).toEqual(['curl', 'https://h/x', '-H', 'accept: */*', '--data-raw', '{"a":1}'])
    // a caret in a bash command is only a caret
    expect(shellWords(`curl 'https://h/x?re=^a' -d '{"re":"^b"}'`)).toEqual(['curl', 'https://h/x?re=^a', '-d', '{"re":"^b"}'])
  })
})

describe('parseCurl', () => {
  it('reads a plain GET', () => {
    expect(ok('curl https://api.example.com/things?limit=10')).toEqual({ method: 'GET', url: 'https://api.example.com/things?limit=10', headers: {}, dropped: [], warnings: [] })
  })

  it('reads the method, headers and a JSON body', () => {
    const r = ok(`curl -X PUT 'https://api.example.com/things/7' -H 'Content-Type: application/json' -H 'Accept: application/json' -d '{"name":"web","size":2,"tags":["a"]}'`)
    expect(r.method).toBe('PUT')
    expect(r.url).toBe('https://api.example.com/things/7')
    expect(r.headers).toEqual({ 'Content-Type': 'application/json', Accept: 'application/json' })
    expect(r.body).toEqual({ name: 'web', size: 2, tags: ['a'] })
  })

  it('is a POST with data and no method, and the method as written otherwise', () => {
    expect(ok(`curl https://h.example.com/x -d 'a=1'`).method).toBe('POST')
    expect(ok(`curl -XPATCH https://h.example.com/x -d '{}'`).method).toBe('PATCH')
    expect(ok(`curl --request=delete https://h.example.com/x`).method).toBe('DELETE')
    expect(ok(`curl --request delete https://h.example.com/x`).method).toBe('DELETE')
    expect(ok(`curl -I https://h.example.com/x`).method).toBe('HEAD')
  })

  it('takes the data options curl has', () => {
    // --data-raw and --data-binary as written; -d drops line breaks
    expect(ok(`curl https://h.example.com --data-raw '{"a":"x@y"}'`).body).toEqual({ a: 'x@y' })
    expect(ok(`curl https://h.example.com --data-binary '{"a":1}'`).body).toEqual({ a: 1 })
    expect(ok(`curl https://h.example.com -d $'{"a":\\n1}'`).body).toEqual({ a: 1 })
    // several -d join with &, as form fields, and say so in the content type
    const form = ok(`curl https://h.example.com -d a=1 -d b=two`)
    expect(form.body).toBe('a=1&b=two')
    expect(form.headers).toEqual({ 'Content-Type': 'application/x-www-form-urlencoded' })
    expect(ok(`curl https://h.example.com --data-urlencode 'q=a b&c' --data-urlencode plain`).body).toBe('q=a%20b%26c&plain')
    // --json sets the content type and accept
    const json = ok(`curl --json '{"x":true}' https://h.example.com/y`)
    expect(json).toMatchObject({ method: 'POST', body: { x: true }, headers: { 'Content-Type': 'application/json', Accept: 'application/json' } })
    // -G puts the data in the query of a GET
    const get = ok(`curl -G https://h.example.com/search -d q=vm -d limit=5`)
    expect(get).toMatchObject({ method: 'GET', url: 'https://h.example.com/search?q=vm&limit=5' })
    expect(get.body).toBeUndefined()
  })

  it('reads a file nowhere: data from @file and form uploads are left out with a warning', () => {
    const r = ok(`curl https://h.example.com -d @body.json -F file=@x.tar.gz -T up.bin`)
    expect(r.body).toBeUndefined()
    expect(r.warnings.join(' ')).toContain('@body.json: the file is not read')
    expect(r.warnings.join(' ')).toContain('-F: multipart form fields are not imported')
    expect(r.warnings.join(' ')).toContain('-T: the file is not read')
  })

  it('reads grouped and attached short options, long options with =, and --url', () => {
    const r = ok(`curl -sSLk -XPOST -HX-Trace:abc --url=https://h.example.com/a -d'{"n":1}' --compressed --max-time 5 -o out.json`)
    expect(r).toMatchObject({ method: 'POST', url: 'https://h.example.com/a', headers: { 'X-Trace': 'abc' }, body: { n: 1 } })
    expect(r.warnings).toEqual([])
    // -A and -e become the headers they stand for
    expect(ok(`curl -A 'probe/1.0' -e https://ref.example.com https://h.example.com`).headers).toEqual({ 'User-Agent': 'probe/1.0', Referer: 'https://ref.example.com' })
  })

  it('reads a browser\'s "Copy as cURL" (bash)', () => {
    const r = ok(`curl 'https://portal.example.com/api/v2/vms?page=2' \\
  -H 'accept: application/json, text/plain, */*' \\
  -H 'accept-language: en-GB,en;q=0.9' \\
  -H 'authorization: Bearer example-token-123' \\
  -H 'content-type: application/json' \\
  -H $'cookie: session=example-session; theme=dark' \\
  --data-raw $'{"name":"web-01","note":"it\\'s new"}' \\
  --compressed`)
    expect(r.method).toBe('POST')
    expect(r.url).toBe('https://portal.example.com/api/v2/vms?page=2')
    expect(r.headers).toEqual({ accept: 'application/json, text/plain, */*', 'accept-language': 'en-GB,en;q=0.9', 'content-type': 'application/json' })
    expect(r.body).toEqual({ name: 'web-01', note: "it's new" })
    expect(r.dropped).toEqual(['authorization header', 'cookie header'])
    expect(r.credentialKind).toBe('bearer')
    expectNoSecret(r, 'example-token-123', 'example-session')
  })

  it('reads a command written over several cmd lines', () => {
    const r = ok('curl "https://h.example.com/a" ^\n  -X POST ^\n  -H "Content-Type: application/json" ^\n  -d "{\\"a\\":1}"')
    expect(r).toMatchObject({ method: 'POST', url: 'https://h.example.com/a', body: { a: 1 } })
  })

  it('reads a browser\'s "Copy as cURL (cmd)" and Firefox\'s "Copy as cURL (Windows)"', () => {
    // Chrome, cmd: every argument as ^"…^", lines ended by ^
    const chrome = ok(
      [
        'curl ^"https://portal.example.com/api/v2/vms?page=2^&q=web^%^2001^" ^',
        '  -H ^"accept: application/json, text/plain, */*^" ^',
        '  -H ^"authorization: Bearer example-token-cmd^" ^',
        '  -H ^"content-type: application/json^" ^',
        '  -b ^"session=example-session-cmd; theme=dark^" ^',
        '  --data-raw ^"^{^\\^"name^\\^":^\\^"web-01^\\^",^\\^"note^\\^":^\\^"it\'s ^&new^\\^"^}^" ^',
        '  --compressed',
      ].join('\r\n'),
    )
    expect(chrome).toMatchObject({ method: 'POST', url: 'https://portal.example.com/api/v2/vms?page=2&q=web%2001', body: { name: 'web-01', note: "it's &new" } })
    expect(chrome.headers).toEqual({ accept: 'application/json, text/plain, */*', 'content-type': 'application/json' })
    expect(chrome.dropped).toEqual(['authorization header', 'the cookies (-b)'])
    expect(chrome.credentialKind).toBe('bearer')
    expect(chrome.warnings).toEqual([])
    expectNoSecret(chrome, 'example-token-cmd', 'example-session-cmd', '^')
    // Firefox, Windows: the same quoting on one line
    const firefox = ok('curl ^"https://api.example.com/x^" -X POST -H ^"Accept: */*^" -H ^"Authorization: Basic ZXhhbXBsZS1mZg==^" -H ^"X-Api-Key: example-key-ff^" --data-raw ^"client_id=abc^&client_secret=example-cs-ff^"')
    expect(firefox).toMatchObject({ method: 'POST', url: 'https://api.example.com/x', headers: { Accept: '*/*' }, body: 'client_id=abc', credentialKind: 'basic' })
    expectNoSecret(firefox, 'ZXhhbXBsZS1mZg==', 'example-key-ff', 'example-cs-ff', '^')
  })

  it('puts https:// in front of a URL without a scheme, and drops the fragment', () => {
    const r = ok('curl api.example.com/things#top')
    expect(r.url).toBe('https://api.example.com/things')
    expect(r.warnings).toEqual(['The URL had no scheme: https:// was put in front.'])
    expect(ok('curl http://localhost:8080/x').url).toBe('http://localhost:8080/x')
  })

  it('accepts a command without the word curl, and says what is not one', () => {
    expect(ok(`-X POST https://h.example.com/a`).method).toBe('POST')
    expect(ok(`/usr/bin/curl https://h.example.com/a`).url).toBe('https://h.example.com/a')
    expect(parseCurl('')).toEqual({ ok: false, error: 'Paste a cURL command, like curl https://api.example.com/things' })
    expect(parseCurl('wget https://h.example.com')).toEqual({ ok: false, error: 'That is not a cURL command: it starts with curl.' })
    expect(parseCurl("curl -H 'X: 1")).toEqual({ ok: false, error: 'A quote is not closed: paste the whole command.' })
    expect(parseCurl('curl -X POST')).toEqual({ ok: false, error: 'The command has no URL.' })
    expect(parseCurl('curl https://h.example.com -H')).toEqual({ ok: false, error: '-H needs a value.' })
  })

  it('warns about the options it does not import, and keeps the ones that change nothing quiet', () => {
    const r = ok('curl --frobnicate -Wq --http2 --no-progress-meter https://h.example.com')
    expect(r.warnings).toEqual(['--frobnicate is not imported.', '-W is not imported.'])
  })

  it('joins a repeated header and leaves Content-Length to the client', () => {
    expect(ok(`curl https://h.example.com -H 'X-Tag: a' -H 'x-tag: b' -H 'Content-Length: 12' -H 'X-Empty;'`).headers).toEqual({ 'X-Tag': 'a, b', 'X-Empty': '' })
  })
})

describe('what signs in never survives', () => {
  it('drops an Authorization header, bearer or basic', () => {
    const bearer = ok(`curl -H 'Authorization: Bearer example-token' https://h.example.com`)
    expect(bearer.headers).toEqual({})
    expect(bearer.dropped).toEqual(['Authorization header'])
    expect(bearer.credentialKind).toBe('bearer')
    expectNoSecret(bearer, 'example-token')
    const basic = ok(`curl --header "authorization: Basic ZXhhbXBsZTpleGFtcGxl" https://h.example.com`)
    expect(basic.credentialKind).toBe('basic')
    expectNoSecret(basic, 'ZXhhbXBsZTpleGFtcGxl')
    expectNoSecret(ok(`curl -H 'Proxy-Authorization: Basic ZXhhbXBsZQ==' https://h.example.com`), 'ZXhhbXBsZQ==')
  })

  it('drops -u and --user, whole or attached, and a user and password in the URL', () => {
    for (const cmd of [`curl -u ops:example-password https://h.example.com`, `curl -uops:example-password https://h.example.com`, `curl --user=ops:example-password https://h.example.com`, `curl --user 'ops:example-password' https://h.example.com`]) {
      const r = ok(cmd)
      expect(r.dropped).toHaveLength(1)
      expect(r.dropped[0]).toMatch(/^the user and password \((-u|--user)\)$/)
      expect(r.credentialKind).toBe('basic')
      expectNoSecret(r, 'example-password', 'ops:')
    }
    const inUrl = ok(`curl https://ops:example-password@h.example.com/x`)
    expect(inUrl.url).toBe('https://h.example.com/x')
    expect(inUrl.dropped).toEqual(['the user and password in the URL'])
    expectNoSecret(inUrl, 'example-password')
  })

  it('drops cookies, API-key headers and a bearer option', () => {
    const r = ok(`curl -b 'sid=example-cookie' --cookie 'a=example-cookie-2' -H 'X-API-Key: example-key' -H 'PRIVATE-TOKEN: example-pat' -H 'X-Client-Secret: example-cs' --oauth2-bearer example-bearer -H 'X-Request-Id: r1' https://h.example.com`)
    expect(r.headers).toEqual({ 'X-Request-Id': 'r1' })
    expect(r.dropped).toEqual(['the cookies (-b)', 'the cookies (--cookie)', 'X-API-Key header', 'PRIVATE-TOKEN header', 'X-Client-Secret header', 'the bearer token (--oauth2-bearer)'])
    expect(r.credentialKind).toBe('header')
    expectNoSecret(r, 'example-cookie', 'example-key', 'example-pat', 'example-cs', 'example-bearer')
  })

  it('drops token-like fields of the query, a JSON body and a form body', () => {
    const q = ok(`curl 'https://h.example.com/x?api_key=example-key&page=2&access_token=example-at'`)
    expect(q.url).toBe('https://h.example.com/x?page=2')
    expect(q.dropped).toEqual(['api_key in the URL', 'access_token in the URL'])
    expectNoSecret(q, 'example-key', 'example-at')
    const json = ok(`curl https://h.example.com/login -d '{"user":"ops","password":"example-pw","nested":{"clientSecret":"example-cs","keep":1}}'`)
    expect(json.body).toEqual({ user: 'ops', nested: { keep: 1 } })
    expect(json.dropped).toEqual(['password in the body', 'clientSecret in the body'])
    expectNoSecret(json, 'example-pw', 'example-cs')
    const form = ok(`curl https://h.example.com/token -d grant_type=client_credentials -d client_secret=example-cs`)
    expect(form.body).toBe('grant_type=client_credentials')
    expectNoSecret(form, 'example-cs')
    const get = ok(`curl -G https://h.example.com/s -d q=vm -d token=example-t`)
    expect(get.url).toBe('https://h.example.com/s?q=vm')
    expect(get.dropped).toEqual(['token in the query'])
    expectNoSecret(get, 'example-t')
  })

  it('drops the secret fields of a form body whatever their values hold: a space, brackets in a name', () => {
    const oauth = ok(`curl -X POST https://auth.example.com/oauth/token -d client_id=abc -d client_secret=example-cs -d 'scope=read write'`)
    expect(oauth.body).toBe('client_id=abc&scope=read write')
    expect(oauth.headers).toEqual({ 'Content-Type': 'application/x-www-form-urlencoded' })
    expect(oauth.dropped).toEqual(['client_secret in the body'])
    expectNoSecret(oauth, 'example-cs')
    const login = ok(`curl https://h.example.com/login -d 'username=ops&password=example pw'`)
    expect(login.body).toBe('username=ops')
    expectNoSecret(login, 'example pw', 'example%20pw')
    const nested = ok(`curl https://h.example.com/orders -d 'metadata[order]=1' -d api_key=example-key -d 'user[password]=example-up' --data-urlencode 'note=a b'`)
    expect(nested.body).toBe('metadata[order]=1&note=a%20b')
    expect(nested.dropped).toEqual(['api_key in the body', 'user[password] in the body'])
    expectNoSecret(nested, 'example-key', 'example-up')
    // text that is not a form comes through as it was
    expect(ok(`curl https://h.example.com -H 'Content-Type: text/plain' -d 'hello there, world'`).body).toBe('hello there, world')
  })

  it('drops the headers named for a key, and any header whose value is a scheme and its credential', () => {
    const r = ok(
      `curl -H 'X-Auth-Key: example-cf-key' -H 'X-Auth-Email: ops@example.com' -H 'Ocp-Apim-Subscription-Key: example-az' -H 'DD-APPLICATION-KEY: example-dd' -H 'X-Authorization: Bearer example-xa' -H 'X-Custom: token example-gh' -H 'X-Signed: Basic ZXhhbXBsZQ==' -H 'Idempotency-Key: k-1' https://h.example.com`,
    )
    expect(r.headers).toEqual({ 'X-Auth-Email': 'ops@example.com', 'Idempotency-Key': 'k-1' })
    expect(r.dropped).toEqual(['X-Auth-Key header', 'Ocp-Apim-Subscription-Key header', 'DD-APPLICATION-KEY header', 'X-Authorization header', 'X-Custom header', 'X-Signed header'])
    expectNoSecret(r, 'example-cf-key', 'example-az', 'example-dd', 'example-xa', 'example-gh', 'ZXhhbXBsZQ==')
    expect(ok(`curl -H 'X-Custom: Bearer example-b' https://h.example.com`).credentialKind).toBe('bearer')
    // a name a pasted command left a quote or a caret on is still read by its letters
    expect(isSecretHeader('^authorization')).toBe(true)
    expect(isSecretHeader('"Cookie')).toBe(true)
    expect(isSecretHeader('Accept')).toBe(false)
  })

  it('never repeats a -H that is not a header, and drops one that looks like a credential', () => {
    const r = ok(`curl -H 'token=example-t' -H 'Bearer example-nb' -H 'sk-example-raw' https://h.example.com`)
    expect(r.headers).toEqual({})
    expect(r.dropped).toEqual(['a -H value that is not a header', 'a -H value that is not a header'])
    expect(r.credentialKind).toBe('header')
    expect(r.warnings).toEqual(['-H: a value that is not a header (Name: value) was left out.'])
    expectNoSecret(r, 'example-t', 'example-nb', 'sk-example-raw')
  })

  it('neither keeps nor repeats the value of a proxy, a client certificate or its password', () => {
    const r = ok(`curl -x http://ops:example-proxy-pw@proxy:3128 -E cert.pem:example-cert-pw --pass example-key-pw https://h.example.com`)
    expect(r.warnings).toEqual(['-x is not imported.', '-E is not imported.', '--pass is not imported.'])
    expectNoSecret(r, 'example-proxy-pw', 'example-cert-pw', 'example-key-pw')
  })
})

describe('what does not sign in stays', () => {
  it('keeps the fields that only look like a secret: a token count, a page token, a tag\'s key, private', () => {
    const r = ok(
      `curl 'https://h.example.com/v1/items?pageToken=abc&page_token=def&pageSize=10&key=example-gk' -d '{"model":"m","max_tokens":1024,"token_type":"bearer","private":true,"tags":[{"key":"env","value":"prod"}],"idempotency_key":"i-1","access_token":"example-at"}'`,
    )
    expect(r.url).toBe('https://h.example.com/v1/items?pageToken=abc&page_token=def&pageSize=10')
    expect(r.body).toEqual({ model: 'm', max_tokens: 1024, token_type: 'bearer', private: true, tags: [{ key: 'env', value: 'prod' }], idempotency_key: 'i-1' })
    expect(r.dropped).toEqual(['key in the URL', 'access_token in the body'])
    expectNoSecret(r, 'example-gk', 'example-at')
  })

  it('judges a field by the words of its name', () => {
    for (const name of ['password', 'newPassword', 'pwd', 'pass', 'client_secret', 'clientSecret', 'oauth_token_secret', 'token', 'access_token', 'accessToken', 'id_token', 'X-Amz-Security-Token', 'X-Amz-Signature', 'X-Amz-Credential', 'sig', 'auth', 'api_key', 'apiKey', 'apikey', 'x-api-key', 'private_key', 'aws_secret_access_key', 'session', 'session_id', 'JSESSIONID', 'PHPSESSID', 'user[password]', 'credentials'])
      expect(isSecretField(name), name).toBe(true)
    for (const name of ['max_tokens', 'maxTokens', 'pageToken', 'next_token', 'nextPageToken', 'token_type', 'tokens', 'private', 'idempotency_key', 'partition_key', 'session_timeout', 'client_id', 'username', 'grant_type', 'keys', 'scope'])
      expect(isSecretField(name), name).toBe(false)
    // `key` alone is a key at the top of a query or body, a key of the data deeper in
    expect(isSecretField('key')).toBe(true)
    expect(isSecretField('key', false)).toBe(false)
  })
})

describe('the HTTP step after an import', () => {
  it("takes the request's method, URL, headers and body, and keeps its credential", () => {
    const r = ok(`curl -X POST https://h.example.com/things -H 'Authorization: Bearer example-token' -H 'X-Trace: 1' -d '{"a":1}'`)
    const current = { method: 'GET', url: 'https://old.example.com', headers: { Old: 'x' }, body: 'old', credential: 'vault:example/api-token' }
    const next = httpWithFromCurl(r, current)
    expect(next).toEqual({ method: 'POST', url: 'https://h.example.com/things', headers: { 'X-Trace': '1' }, body: { a: 1 }, credential: 'vault:example/api-token' })
    expect(JSON.stringify(next)).not.toContain('example-token')
    // no headers and no body: the step has none either
    expect(httpWithFromCurl(ok('curl https://h.example.com'), current)).toEqual({ method: 'GET', url: 'https://h.example.com', credential: 'vault:example/api-token' })
  })
})
