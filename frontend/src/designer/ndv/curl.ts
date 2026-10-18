/**
 * Import cURL for the HTTP step, as n8n's HTTP Request node has it: a cURL command (as a browser's
 * "Copy as cURL" or an API's docs write it) becomes the step's method, URL, headers and body.
 *
 * What signs in never comes along: an Authorization header, `-u user:password`, a user and
 * password written into the URL, cookies, API-key headers and token-like query or body fields are
 * dropped here, before anything reaches the step, the workflow file, the undo history or storage.
 * The result names what it dropped (never its value), so the step can ask for a credential instead.
 */
import type { JsonObject, JsonValue } from '@iaf/workflow-checks'

/** What a dropped secret was, for the note the credential field shows: names only. */
export type CredentialKind = 'basic' | 'bearer' | 'header'

export interface CurlRequest {
  method: string
  url: string
  /** the headers kept, by the name as written; none that signs in */
  headers: Record<string, string>
  /** JSON when the data is JSON, else the text; absent without data */
  body?: JsonValue
  /** what was left out because it signs in, in words ("Authorization header"); never its value */
  dropped: string[]
  /** the kind of credential the command signed in with, when it did */
  credentialKind?: CredentialKind
  /** options that were not imported, files that were not read */
  warnings: string[]
}

export type CurlParse = { ok: true; request: CurlRequest } | { ok: false; error: string }

/* ----------------------------------------------------------------------------
   Words: the shell's quoting
   ------------------------------------------------------------------------- */

const ANSI_C: Record<string, string> = { n: '\n', t: '\t', r: '\r', a: '\x07', b: '\b', e: '\x1b', E: '\x1b', f: '\f', v: '\v', '\\': '\\', "'": "'", '"': '"', '?': '?' }

/**
 * The words of a command line as a POSIX shell splits them: single quotes keep everything, double
 * quotes let `\` escape `"`, `\`, `$` and a backquote, `$'…'` reads C escapes, and a line may go
 * on after a `\` (bash), `^` (cmd) or backquote (PowerShell) at its end. Null when a quote is not closed.
 */
export function shellWords(text: string): string[] | null {
  const words: string[] = []
  let word = ''
  let inWord = false
  let i = 0
  const s = text.replace(/\r\n?/g, '\n')
  const atLineEnd = (j: number) => s[j] === '\n'
  while (i < s.length) {
    const c = s[i]
    if ((c === '\\' || c === '^' || c === '`') && atLineEnd(i + 1)) {
      i += 2
      continue
    }
    if (c === ' ' || c === '\t' || c === '\n') {
      if (inWord) words.push(word)
      word = ''
      inWord = false
      i++
      continue
    }
    inWord = true
    if (c === "'") {
      const end = s.indexOf("'", i + 1)
      if (end < 0) return null
      word += s.slice(i + 1, end)
      i = end + 1
    } else if (c === '$' && s[i + 1] === "'") {
      i += 2
      let closed = false
      while (i < s.length) {
        const d = s[i]
        if (d === "'") {
          closed = true
          i++
          break
        }
        if (d === '\\' && i + 1 < s.length) {
          const e = s[i + 1]
          const hex = e === 'x' ? /^[0-9a-fA-F]{1,2}/.exec(s.slice(i + 2)) : e === 'u' ? /^[0-9a-fA-F]{1,4}/.exec(s.slice(i + 2)) : e === 'U' ? /^[0-9a-fA-F]{1,8}/.exec(s.slice(i + 2)) : null
          if (hex) {
            word += String.fromCodePoint(parseInt(hex[0], 16))
            i += 2 + hex[0].length
          } else {
            word += ANSI_C[e] ?? `\\${e}`
            i += 2
          }
          continue
        }
        word += d
        i++
      }
      if (!closed) return null
    } else if (c === '"') {
      i++
      let closed = false
      while (i < s.length) {
        const d = s[i]
        if (d === '"') {
          closed = true
          i++
          break
        }
        if (d === '\\' && i + 1 < s.length) {
          const e = s[i + 1]
          if (e === '\n') i += 2
          else if (e === '"' || e === '\\' || e === '$' || e === '`') {
            word += e
            i += 2
          } else {
            word += d
            i++
          }
          continue
        }
        word += d
        i++
      }
      if (!closed) return null
    } else if (c === '\\' && i + 1 < s.length) {
      word += s[i + 1]
      i += 2
    } else {
      word += c
      i++
    }
  }
  if (inWord) words.push(word)
  return words
}

/* ----------------------------------------------------------------------------
   Secrets
   ------------------------------------------------------------------------- */

/** Header names that carry a credential. */
const SECRET_HEADER = /^(authorization|proxy-authorization|cookie|x-api-key|api-key|apikey|x-auth-token|x-access-token|private-token|x-csrf-token|x-xsrf-token)$|token|secret|passw|api[-_]?key|session/i

/** Query and body field names that carry a credential. */
const SECRET_FIELD = /token|secret|passw|^pwd$|api[-_]?key|apikey|signature|^sig$|^key$|^auth$|^authorization$|session|credential/i

export function isSecretHeader(name: string): boolean {
  return SECRET_HEADER.test(name.trim())
}

export function isSecretField(name: string): boolean {
  return SECRET_FIELD.test(name.trim())
}

/** The value without the fields that carry a credential, at any depth, and the names it dropped. */
function withoutSecretFields(v: JsonValue, dropped: Set<string>): JsonValue {
  if (Array.isArray(v)) return v.map((x) => withoutSecretFields(x, dropped))
  if (v && typeof v === 'object') {
    const out: JsonObject = {}
    for (const [k, x] of Object.entries(v)) {
      if (isSecretField(k)) dropped.add(k)
      else if (x !== undefined) out[k] = withoutSecretFields(x, dropped)
    }
    return out
  }
  return v
}

const decode = (s: string): string => {
  try {
    return decodeURIComponent(s.replace(/\+/g, ' '))
  } catch {
    return s
  }
}

/** form-encoded text (`a=1&b=2`) without the fields that carry a credential. */
function withoutSecretPairs(text: string, dropped: Set<string>): string {
  return text
    .split('&')
    .filter((pair) => {
      const name = decode(pair.split('=')[0] ?? '')
      if (name && isSecretField(name)) {
        dropped.add(name)
        return false
      }
      return true
    })
    .join('&')
}

const FORM_RE = /^[^\s={}[\]"]+=[^\s]*(&[^\s={}[\]"]+=[^\s]*)*$/

/* ----------------------------------------------------------------------------
   The URL
   ------------------------------------------------------------------------- */

const SCHEME_RE = /^[a-z][a-z0-9+.-]*:\/\//i

/** The URL without a user and password before its host, a fragment, or a query field that carries a credential. */
function cleanUrl(raw: string, dropped: string[], warnings: string[]): { url: string; userinfo: boolean } {
  let url = raw.trim()
  if (!SCHEME_RE.test(url)) {
    url = `https://${url}`
    warnings.push('The URL had no scheme: https:// was put in front.')
  }
  let userinfo = false
  url = url.replace(/^([a-z][a-z0-9+.-]*:\/\/)([^/?#]*)@/i, (_, scheme: string) => {
    userinfo = true
    return scheme
  })
  if (userinfo) dropped.push('the user and password in the URL')
  const hash = url.indexOf('#')
  if (hash >= 0) url = url.slice(0, hash)
  const q = url.indexOf('?')
  if (q >= 0) {
    const names = new Set<string>()
    const query = withoutSecretPairs(url.slice(q + 1), names)
    url = query ? `${url.slice(0, q)}?${query}` : url.slice(0, q)
    for (const n of names) dropped.push(`${n} in the URL`)
  }
  return { url, userinfo }
}

/* ----------------------------------------------------------------------------
   Options
   ------------------------------------------------------------------------- */

type Opt =
  | 'request'
  | 'header'
  | 'data'
  | 'data-raw'
  | 'data-urlencode'
  | 'json'
  | 'user'
  | 'user-agent'
  | 'referer'
  | 'cookie'
  | 'oauth2-bearer'
  | 'url'
  | 'form'
  | 'upload'
  | 'head'
  | 'get'
  | 'skip-value'
  | 'secret-value'
  | 'flag'

const LONG: Record<string, Opt> = {
  request: 'request',
  header: 'header',
  data: 'data',
  'data-ascii': 'data',
  'data-binary': 'data-raw',
  'data-raw': 'data-raw',
  'data-urlencode': 'data-urlencode',
  json: 'json',
  user: 'user',
  'user-agent': 'user-agent',
  referer: 'referer',
  cookie: 'cookie',
  'oauth2-bearer': 'oauth2-bearer',
  url: 'url',
  form: 'form',
  'form-string': 'form',
  'upload-file': 'upload',
  head: 'head',
  get: 'get',
  // options with a value the request does not need
  output: 'skip-value',
  'max-time': 'skip-value',
  'connect-timeout': 'skip-value',
  'write-out': 'skip-value',
  retry: 'skip-value',
  'retry-delay': 'skip-value',
  'retry-max-time': 'skip-value',
  'max-redirs': 'skip-value',
  'limit-rate': 'skip-value',
  'cookie-jar': 'skip-value',
  resolve: 'skip-value',
  'connect-to': 'skip-value',
  cacert: 'skip-value',
  capath: 'skip-value',
  'cert-type': 'skip-value',
  'key-type': 'skip-value',
  range: 'skip-value',
  interface: 'skip-value',
  'dns-servers': 'skip-value',
  proto: 'skip-value',
  'proto-redir': 'skip-value',
  'expect100-timeout': 'skip-value',
  'keepalive-time': 'skip-value',
  'local-port': 'skip-value',
  'max-filesize': 'skip-value',
  'unix-socket': 'skip-value',
  'request-target': 'skip-value',
  noproxy: 'skip-value',
  trace: 'skip-value',
  'trace-ascii': 'skip-value',
  'tls-max': 'skip-value',
  ciphers: 'skip-value',
  config: 'skip-value',
  // options whose value may itself be a secret: neither kept nor shown
  proxy: 'secret-value',
  'proxy-user': 'secret-value',
  cert: 'secret-value',
  key: 'secret-value',
  pass: 'secret-value',
  'aws-sigv4': 'secret-value',
  tlsuser: 'secret-value',
  tlspassword: 'secret-value',
  'proxy-header': 'secret-value',
}

const SHORT: Record<string, Opt> = {
  X: 'request',
  H: 'header',
  d: 'data',
  u: 'user',
  A: 'user-agent',
  e: 'referer',
  b: 'cookie',
  F: 'form',
  T: 'upload',
  I: 'head',
  G: 'get',
  o: 'skip-value',
  m: 'skip-value',
  w: 'skip-value',
  c: 'skip-value',
  r: 'skip-value',
  K: 'skip-value',
  y: 'skip-value',
  Y: 'skip-value',
  z: 'skip-value',
  C: 'skip-value',
  P: 'skip-value',
  Q: 'skip-value',
  x: 'secret-value',
  U: 'secret-value',
  E: 'secret-value',
}

/** Options that take a value. */
const TAKES_VALUE = new Set<Opt>(['request', 'header', 'data', 'data-raw', 'data-urlencode', 'json', 'user', 'user-agent', 'referer', 'cookie', 'oauth2-bearer', 'url', 'form', 'upload', 'skip-value', 'secret-value'])

/** Flags that change nothing the step keeps (silent, follow redirects, insecure, compressed…). */
const QUIET_FLAGS = /^(s|S|L|k|v|i|f|g|N|q|n|j|O|J|R|Z|l|a|B|M|V|h|#|0|1|2|3|4|6)$/
const QUIET_LONG =
  /^(silent|show-error|location|location-trusted|insecure|verbose|include|fail|fail-with-body|fail-early|compressed|compressed-ssh|globoff|no-buffer|http0\.9|http1\.0|http1\.1|http2|http2-prior-knowledge|http3|http3-only|progress-bar|disable|no-keepalive|raw|tr-encoding|netrc|netrc-optional|digest|basic|ntlm|anyauth|negotiate|ipv4|ipv6|tlsv1|tlsv1\.[0-3]|sslv2|sslv3|ssl|ssl-reqd|path-as-is|no-progress-meter|junk-session-cookies|remote-name|remote-name-all|remote-header-name|remote-time|create-dirs|parallel|styled-output|no-styled-output|list-only|append|use-ascii|no-alpn|no-npn|no-sessionid|tcp-nodelay|tcp-fastopen|post301|post302|post303|ssl-no-revoke|ssl-revoke-best-effort|ssl-allow-beast|proxy-insecure|suppress-connect-headers|xattr|metalink|clobber|no-clobber)$/

/** One data option's value, as curl sends it. */
function dataPart(opt: Opt, value: string, warnings: string[]): string | null {
  if (opt === 'data-urlencode') {
    const at = /^([^=@]*)@/.exec(value)
    if (at) {
      warnings.push(`--data-urlencode ${at[0]}…: the file is not read; put its content in the body.`)
      return null
    }
    const eq = value.indexOf('=')
    if (eq < 0) return encodeURIComponent(value)
    const name = value.slice(0, eq)
    return name ? `${name}=${encodeURIComponent(value.slice(eq + 1))}` : encodeURIComponent(value.slice(eq + 1))
  }
  if (opt !== 'data-raw' && value.startsWith('@')) {
    warnings.push(`${value.split(/\s/)[0]}: the file is not read; paste the body in the step.`)
    return null
  }
  // -d and --data-ascii send the data without its line breaks, like curl
  return opt === 'data' ? value.replace(/[\r\n]/g, '') : value
}

const lower = (h: Record<string, string>) => Object.fromEntries(Object.entries(h).map(([k, v]) => [k.toLowerCase(), v]))

/* ----------------------------------------------------------------------------
   The command
   ------------------------------------------------------------------------- */

/** A cURL command as an HTTP request, without anything that signs in. */
export function parseCurl(text: string): CurlParse {
  const words = shellWords(text.trim())
  if (words === null) return { ok: false, error: 'A quote is not closed: paste the whole command.' }
  const args = [...words]
  if (args.length === 0) return { ok: false, error: 'Paste a cURL command, like curl https://api.example.com/things' }
  if (/(^|[\\/])curl(\.exe)?$/i.test(args[0])) args.shift()
  else if (!args[0].startsWith('-') && !SCHEME_RE.test(args[0])) return { ok: false, error: 'That is not a cURL command: it starts with curl.' }

  const dropped: string[] = []
  const warnings: string[] = []
  const headers: [string, string][] = []
  const data: string[] = []
  const positionals: string[] = []
  // what the options set: a record, since the option handler below writes it
  const seen: { method: string | null; head: boolean; get: boolean; json: boolean; url: string | null; credentialKind: CredentialKind | null } = { method: null, head: false, get: false, json: false, url: null, credentialKind: null }
  const kind = (k: CredentialKind) => {
    seen.credentialKind ??= k
  }

  const apply = (opt: Opt, value: string, written: string) => {
    switch (opt) {
      case 'request':
        seen.method = value.trim().toUpperCase()
        break
      case 'header': {
        const colon = value.indexOf(':')
        const semi = value.indexOf(';')
        // "Name;" sends the header empty; "Name:" alone removes one curl would send
        if (colon < 0 && semi > 0 && !value.slice(semi + 1).trim()) {
          headers.push([value.slice(0, semi).trim(), ''])
          break
        }
        if (colon <= 0) {
          warnings.push(`${written} ${value.split(/\s/)[0]}: not a header (Name: value).`)
          break
        }
        const name = value.slice(0, colon).trim()
        const v = value.slice(colon + 1).trim()
        if (!v) break
        if (isSecretHeader(name)) {
          dropped.push(`${name} header`)
          if (/^authorization$/i.test(name) || /^proxy-authorization$/i.test(name)) kind(/^basic\s/i.test(v) ? 'basic' : 'bearer')
          else kind('header')
          break
        }
        headers.push([name, v])
        break
      }
      case 'data':
      case 'data-raw':
      case 'data-urlencode':
      case 'json': {
        if (opt === 'json') seen.json = true
        const part = dataPart(opt, value, warnings)
        if (part !== null) data.push(part)
        break
      }
      case 'user':
        dropped.push(`the user and password (${written})`)
        kind('basic')
        break
      case 'oauth2-bearer':
        dropped.push('the bearer token (--oauth2-bearer)')
        kind('bearer')
        break
      case 'cookie':
        dropped.push(`the cookies (${written})`)
        kind('header')
        break
      case 'user-agent':
        headers.push(['User-Agent', value])
        break
      case 'referer':
        headers.push(['Referer', value])
        break
      case 'url':
        seen.url ??= value
        break
      case 'form':
        warnings.push(`${written}: multipart form fields are not imported; give the step a JSON body.`)
        break
      case 'upload':
        warnings.push(`${written}: the file is not read; give the step a body.`)
        break
      case 'secret-value':
        warnings.push(`${written} is not imported.`)
        break
      case 'skip-value':
      case 'head':
      case 'get':
      case 'flag':
        break
    }
  }

  for (let i = 0; i < args.length; i++) {
    const a = args[i]
    if (a === '--') {
      positionals.push(...args.slice(i + 1))
      break
    }
    if (a.startsWith('--')) {
      const eq = a.indexOf('=')
      const name = (eq > 0 ? a.slice(2, eq) : a.slice(2)).toLowerCase()
      const opt = LONG[name]
      if (!opt) {
        if (!QUIET_LONG.test(name) && !name.startsWith('no-')) warnings.push(`--${name} is not imported.`)
        continue
      }
      if (opt === 'head') seen.head = true
      if (opt === 'get') seen.get = true
      if (!TAKES_VALUE.has(opt)) continue
      const value = eq > 0 ? a.slice(eq + 1) : args[++i]
      if (value === undefined) return { ok: false, error: `--${name} needs a value.` }
      apply(opt, value, `--${name}`)
      continue
    }
    if (a.startsWith('-') && a.length > 1) {
      // short options: flags may be grouped (-sSL), a value may follow at once (-XPOST, -d@file)
      for (let j = 1; j < a.length; j++) {
        const ch = a[j]
        const opt = SHORT[ch]
        if (!opt) {
          if (!QUIET_FLAGS.test(ch)) warnings.push(`-${ch} is not imported.`)
          continue
        }
        if (opt === 'head') seen.head = true
        if (opt === 'get') seen.get = true
        if (!TAKES_VALUE.has(opt)) continue
        const rest = a.slice(j + 1)
        const value = rest !== '' ? rest : args[++i]
        if (value === undefined) return { ok: false, error: `-${ch} needs a value.` }
        apply(opt, value, `-${ch}`)
        break
      }
      continue
    }
    positionals.push(a)
  }

  const target = seen.url ?? positionals.find((p) => SCHEME_RE.test(p) || /^(localhost|[\w-]+(\.[\w-]+)+)(:\d+)?([/?#]|$)/i.test(p)) ?? positionals[0]
  if (!target) return { ok: false, error: 'The command has no URL.' }
  const cleaned = cleanUrl(target, dropped, warnings)
  if (cleaned.userinfo) kind('basic')
  let finalUrl = cleaned.url

  // the data: a body, or with -G the query of a GET
  const bodyText = data.length > 0 ? data.join('&') : null
  const secretFields = new Set<string>()
  const get = seen.get
  let body: JsonValue | undefined
  if (bodyText !== null && get) {
    const query = withoutSecretPairs(bodyText, secretFields)
    if (query) finalUrl += (finalUrl.includes('?') ? '&' : '?') + query
  } else if (bodyText !== null) {
    let parsed: JsonValue | undefined
    try {
      parsed = JSON.parse(bodyText) as JsonValue
    } catch {
      parsed = undefined
    }
    if (parsed !== undefined && typeof parsed === 'object' && parsed !== null) body = withoutSecretFields(parsed, secretFields)
    else if (FORM_RE.test(bodyText)) body = withoutSecretPairs(bodyText, secretFields)
    else body = bodyText
  }
  for (const f of secretFields) dropped.push(`${f} in the ${get ? 'query' : 'body'}`)
  if (secretFields.size > 0) kind('header')

  const have = lower(Object.fromEntries(headers))
  if (seen.json) {
    if (!have['content-type']) headers.push(['Content-Type', 'application/json'])
    if (!have.accept) headers.push(['Accept', 'application/json'])
  } else if (typeof body === 'string' && FORM_RE.test(body) && !have['content-type']) {
    // what curl sends -d data as
    headers.push(['Content-Type', 'application/x-www-form-urlencoded'])
  }

  // headers by name: a repeated one keeps its values, joined; Content-Length is curl's to work out
  const out: Record<string, string> = {}
  for (const [name, value] of headers) {
    if (/^content-length$/i.test(name)) continue
    const existing = Object.keys(out).find((k) => k.toLowerCase() === name.toLowerCase())
    if (existing) out[existing] = out[existing] && value ? `${out[existing]}, ${value}` : out[existing] || value
    else out[name] = value
  }

  const method = seen.method || (seen.head ? 'HEAD' : bodyText !== null && !get ? 'POST' : 'GET')
  return {
    ok: true,
    request: {
      method,
      url: finalUrl,
      headers: out,
      ...(body !== undefined && body !== '' ? { body } : {}),
      dropped,
      ...(seen.credentialKind ? { credentialKind: seen.credentialKind } : {}),
      warnings,
    },
  }
}

/**
 * The HTTP step's `with` after Import cURL: the request's method, URL, headers and body in place of
 * the step's, its credential and any other field kept as they were.
 */
export function httpWithFromCurl(request: CurlRequest, current: JsonObject): JsonObject {
  const rest: JsonObject = { ...current }
  delete rest.method
  delete rest.url
  delete rest.headers
  delete rest.body
  return {
    method: request.method,
    url: request.url,
    ...(Object.keys(request.headers).length > 0 ? { headers: { ...request.headers } } : {}),
    ...(request.body !== undefined ? { body: request.body } : {}),
    ...rest,
  }
}
