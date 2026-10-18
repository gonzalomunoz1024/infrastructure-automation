"""Shared helpers of the API flows: one HTTP call, a check, the report."""
import json, os, time, urllib.request, urllib.error, sys

# The backend to exercise; the demo stack of scripts/dev.sh answers on 8080.
BASE = os.environ.get('IAF_BASE', 'http://localhost:8080')
LOG = []
ISSUES = []

def call(method, path, body=None, user=None, headers=None, expect=None, raw=False, timeout=30, note=''):
    url = BASE + path
    data = None
    h = {'Accept': 'application/json'}
    if body is not None:
        if isinstance(body, (bytes, bytearray)):
            data = bytes(body)
        else:
            data = json.dumps(body).encode()
            h['Content-Type'] = 'application/json'
    if user:
        h['X-User'] = user
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    t0 = time.time()
    status, payload, ctype = None, None, ''
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status = r.status
            ctype = r.headers.get('Content-Type', '')
            payload = r.read()
    except urllib.error.HTTPError as e:
        status = e.code
        ctype = e.headers.get('Content-Type', '') if e.headers else ''
        payload = e.read()
    except Exception as e:
        status = 'ERR'
        payload = str(e).encode()
    ms = int((time.time() - t0) * 1000)
    parsed = payload
    if not raw and payload is not None and 'json' in (ctype or '') and payload:
        try:
            parsed = json.loads(payload)
        except Exception:
            parsed = payload
    ok = True
    if expect is not None:
        exp = expect if isinstance(expect, (list, tuple, set)) else [expect]
        ok = status in exp
    if status == 500 or status == 'ERR':
        ok = False
    line = f"{'OK ' if ok else 'BAD'} {status} {ms:>5}ms {method} {path} {note}"
    LOG.append(line)
    if not ok:
        snippet = payload[:400] if isinstance(payload, (bytes, bytearray)) else str(payload)[:400]
        ISSUES.append(f"{line}\n      expected {expect}; body: {snippet!r}")
    if ms > 5000:
        ISSUES.append(f"SLOW {ms}ms {method} {path}")
    return status, parsed

def issue(msg):
    ISSUES.append('CHECK ' + msg)

def check(cond, msg):
    if not cond:
        issue(msg)
    return cond

def report():
    print('\n'.join(LOG))
    print('\n==== ISSUES (%d) ====' % len(ISSUES))
    for i in ISSUES:
        print(i)
    sys.exit(1 if ISSUES else 0)
