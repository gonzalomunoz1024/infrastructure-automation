"""Providers, the wizard imports, the assistant and the actuator."""
import os, sys, json, time, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib
from lib import call, check, issue, report
A = '/api/v1'

# ---------------- providers
s, ps = call('GET', A + '/providers', expect=200)
check(len(ps) == 8, f'providers: {len(ps)}')
s, pd = call('GET', A + '/providers/vsphere', expect=200)
for k in ('yaml', 'actions', 'daily', 'perAction', 'health', 'changeRequests'):
    check(k in pd, f'provider detail missing {k}')
check(len(pd.get('daily', [])) == 14, f'daily points: {len(pd.get("daily", []))}')
s, _ = call('GET', A + '/providers/nope', expect=404)
s, al = call('GET', A + '/providers/backup/alerts', expect=200)
spec = {
  'openapi': '3.0.3', 'info': {'title': 'Fleet API', 'version': '2.1.0'},
  'servers': [{'url': 'https://fleet.example.com'}],
  'components': {'securitySchemes': {'bearer': {'type': 'http', 'scheme': 'bearer'}}},
  'security': [{'bearer': []}],
  'paths': {
    '/vms/{id}': {'get': {'operationId': 'getVm', 'summary': 'Get a VM', 'parameters': [{'name': 'id', 'in': 'path', 'required': True, 'schema': {'type': 'string'}}],
                          'responses': {'200': {'description': 'ok', 'content': {'application/json': {'schema': {'type': 'object', 'properties': {'id': {'type': 'string'}, 'state': {'type': 'string'}}}}}}}},
                  'delete': {'operationId': 'deleteVm', 'parameters': [{'name': 'id', 'in': 'path', 'required': True, 'schema': {'type': 'string'}}], 'responses': {'204': {'description': 'gone'}}}},
    '/vms': {'post': {'operationId': 'createVm', 'requestBody': {'content': {'application/json': {'schema': {'type': 'object', 'required': ['name'], 'properties': {'name': {'type': 'string'}}}}}},
                      'responses': {'201': {'description': 'made', 'content': {'application/json': {'schema': {'type': 'object', 'properties': {'id': {'type': 'string'}}}}}}}}}
  }}
s, imp = call('POST', A + '/providers:import-spec', {'type': 'openapi', 'content': json.dumps(spec)}, expect=200)
check(isinstance(imp, dict) and len(imp.get('operations', [])) == 3, f'import ops: {str(imp)[:300]}')
if isinstance(imp, dict):
    print('import ->', imp.get('title'), imp.get('auth'), [(o['operationId'], o['effect'], o['retry']) for o in imp.get('operations', [])])
s, _ = call('POST', A + '/providers:import-spec', {'type': 'openapi', 'content': 'not: [a spec'}, expect=400, note='bad spec')
s, _ = call('POST', A + '/providers:import-spec', {'type': 'openapi'}, expect=400, note='no spec')
t0 = time.time()
s, tc = call('POST', A + '/providers:test-connection', {'endpoints': {'dev': 'https://fleet-dev.invalid', 'test': 'http://127.0.0.1:9'}, 'auth': {'type': 'bearer', 'credential': 'vault:fleet/token'}}, expect=200, timeout=60, note='test-connection')
print('test-connection ->', round(time.time() - t0, 1), 's', str(tc)[:300])
s, tc2 = call('POST', A + '/providers:test-connection', {'endpoints': {'dev': 'http://169.254.169.254/latest/meta-data/'}, 'auth': {'type': 'none'}}, expect=[200, 400], timeout=60, note='ssrf metadata')
print('metadata probe ->', str(tc2)[:300])
s, tc3 = call('POST', A + '/providers:test-connection', {'endpoints': {'dev': 'file:///etc/passwd'}, 'auth': {'type': 'none'}}, expect=[200, 400], timeout=60, note='file url')
print('file url ->', str(tc3)[:300])
yaml_p = """apiVersion: iaf.platform/v1
kind: Provider
metadata:
  name: fleet
  title: Fleet API
  owner: team-compute
  version: 0.1.0
spec:
  type: openapi
  spec: { url: https://fleet.example.com/openapi.json, sha256: 2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae }
  endpoints: { dev: https://fleet-dev.example.com }
  auth: { type: bearer, credential: vault:fleet/token }
  actions:
    - id: vm.get
      title: Get VM
      operation: getVm
      effect: read
      retry: safe
      input: { type: object, required: [id], properties: { id: { type: string } } }
      output: { type: object, properties: { id: { type: string }, state: { type: string } } }
      sample: { id: "{{ $input.id }}", state: running }
  errors:
    - { match: { status: 503 }, class: unavailable, retry: true }
  slo: { successRate: 99.0, p95Ms: 800 }
  alerts: { channel: "#compute-alerts" }
"""
s, pv = call('POST', A + '/providers:validate', {'yaml': yaml_p}, expect=200)
print('provider validate ->', [(c['id'], c['status']) for c in pv.get('checks', [])], [p['code'] for p in pv.get('problems', [])])
s, tr = call('POST', A + '/providers:try', {'yaml': yaml_p, 'action': 'vm.get', 'environment': 'dev', 'input': {'id': 'vm-1'}}, expect=200, timeout=60)
print('try ->', str(tr)[:300])
s, pub = call('POST', A + '/providers:publish', {'yaml': yaml_p}, expect=202)
print('publish ->', s, str(pub)[:300])
if isinstance(pub, dict) and pub.get('id'):
    s, cr = call('GET', A + f'/change-requests/{pub["id"]}', expect=200)
    print('provider CR checks ->', [(c['id'], c['status']) for c in cr.get('checks', [])])
    s, _ = call('POST', A + f'/change-requests/{pub["id"]}:merge', expect=202)
    time.sleep(0.5)
    s, fleet = call('GET', A + '/providers/fleet', expect=200)
s, _ = call('POST', A + '/providers:publish', {'yaml': yaml_p.replace('owner: team-compute', 'owner: team-data')}, user='mateo', expect=403, note='mateo publishes for team-data')
s, ov = call('POST', A + '/providers/vsphere/overrides', {'action': 'vm.get', 'response': {'id': 'vm-1', 'name': 'web-1', 'powerState': 'poweredOn', 'cpu': 4, 'memoryGb': 16, 'environment': 'dev', 'tags': ['a']}}, expect=202)
s, slo = call('PUT', A + '/providers/vsphere/slo', {'successRate': 99.0, 'p95Ms': 900, 'channel': '#compute-alerts'}, expect=202)
s, _ = call('PUT', A + '/providers/vsphere/slo', {'successRate': 140, 'p95Ms': -1}, expect=400, note='bad slo')
s, _ = call('PUT', A + '/providers/vsphere/slo', {'successRate': 99.0, 'p95Ms': 900}, user='ana', expect=403, note='ana slo vsphere')
s, dr = call('POST', A + '/providers:draft', {'description': 'Our DNS service at dns.example.com has a REST API to create and delete A records'}, expect=200)
check(isinstance(dr, dict) and dr.get('yaml'), f'provider draft: {str(dr)[:200]}')
s, reg = call('GET', A + '/registry/providers', expect=200)
s, reg2 = call('GET', A + '/registry/credentials', expect=200)
check(all(set(c.keys()) <= {'name', 'type'} for c in reg2), f'registry credentials leak: {reg2[:2]}')
for p in ('step-types', 'workflows?published=true', 'workflows/search?q=vm', 'resource-types', 'playbooks'):
    s, _ = call('GET', A + '/registry/' + p, expect=200)

# ---------------- releases
s, rels = call('GET', A + '/releases', expect=200)
print('releases ->', [(r['workflow'], r['status'], r['stagePercent']) for r in rels])
canary = next((r for r in rels if r['status'] == 'canary'), None)
if canary:
    rid = canary['id']
    s, _ = call('POST', A + f'/releases/{rid}:evaluate', expect=202)
    s, _ = call('POST', A + f'/releases/{rid}:pause', expect=202)
    s, _ = call('POST', A + f'/releases/{rid}:pause', expect=409, note='pause twice')
    s, _ = call('POST', A + f'/releases/{rid}:resume', expect=202)
    s, _ = call('POST', A + f'/releases/{rid}:promote', user='ana', expect=403, note='ana promotes compute')
    s, _ = call('POST', A + f'/releases/{rid}:promote', expect=202)
    s, r2 = call('GET', A + f'/releases/{rid}', expect=200)
    print('after promote ->', r2['status'], r2['stagePercent'], [h['event'] for h in r2['history']][-4:])
s, _ = call('GET', A + '/releases/rel_nope', expect=404)
s, _ = call('POST', A + '/releases/rel_nope:promote', expect=404)

# ---------------- audit
s, rows = call('GET', A + '/audit', expect=200)
check(len(rows) > 50, f'audit rows {len(rows)}')
jid = rows[0]['jobId']
s, rec = call('GET', A + f'/audit/{jid}', expect=200)
s, rec2 = call('GET', A + f'/jobs/{jid}/evidence', expect=200)
check(rec == rec2, 'evidence alias differs')
s, ver = call('GET', A + f'/audit/{jid}:verify', expect=200)
check(ver.get('valid') is True, f'verify: {ver}')
s, sealed = call('POST', A + '/audit:seal', expect=200)
s, ver2 = call('GET', A + f'/audit/{jid}:verify', expect=200)
print('verify after seal ->', ver2)
s, js = call('GET', A + f'/audit/{jid}/export?format=json', expect=200, raw=True)
s, pdf = call('GET', A + f'/audit/{jid}/export?format=pdf', expect=200, raw=True)
check(isinstance(pdf, (bytes, bytearray)) and pdf[:5] == b'%PDF-', 'pdf export is not a PDF')
s, _ = call('GET', A + f'/audit/{jid}/export?format=docx', expect=400, note='bad format')
s, zipb = call('POST', A + '/audit:export-pack', {'filter': {'workflow': 'vm-restart'}}, expect=200, raw=True)
check(isinstance(zipb, (bytes, bytearray)) and zipb[:2] == b'PK', 'pack is not a zip')
s, sh = call('POST', A + f'/audit/{jid}:share', expect=[200, 202])
tok = sh['url'].rsplit('/', 1)[-1] if isinstance(sh, dict) and sh.get('url') else None
print('share ->', sh)
if tok:
    s, shared = call('GET', A + f'/audit/shared/{tok}', user='nobody-here', expect=200)
    s, _ = call('GET', A + '/audit/shared/forged-token', expect=[403, 404], note='forged token')
s, _ = call('GET', A + '/audit/job_nope', expect=404)
s, _ = call('GET', A + '/audit', user='nobody-here', expect=[200, 403], note='audit as nobody')

# ---------------- resources
s, types = call('GET', A + '/resource-types', expect=200)
s, t1 = call('GET', A + '/resource-types/image', expect=200)
s, _ = call('GET', A + '/resource-types/nope', expect=404)
s, res = call('GET', A + '/resources', expect=200)
print('resources ->', len(res), sorted(set(r['type'] for r in res)))
img = next((r for r in res if r['type'] == 'image'), None)
if img:
    s, lin = call('GET', A + f'/resources/{img["id"]}/lineage', expect=200)
    print('lineage ->', len(lin.get('builtFrom', [])), len(lin.get('dependents', [])))
    if lin.get('dependents'):
        s, rt = call('POST', A + f'/resources/{img["id"]}:retire', expect=409, note='retire with dependents')
s, _ = call('GET', A + '/resources/res_nope', expect=404)
s, imgs = call('GET', A + '/images', expect=200)
if imgs:
    s, _ = call('GET', A + f'/images/{imgs[0]["id"]}', expect=200)
s, _ = call('GET', A + '/images/nope', expect=404)

# ---------------- credentials
s, creds = call('GET', A + '/credentials', expect=200)
check(all('value' not in c for c in creds), 'credentials list leaks values')
s, _ = call('POST', A + '/credentials', {'name': 'e2e/token', 'type': 'token', 'value': 'super-secret-e2e'}, expect=202)
s, creds2 = call('GET', A + '/credentials', expect=200)
check('super-secret-e2e' not in json.dumps(creds2), 'secret value returned by the list')
s, _ = call('POST', A + '/credentials', {'name': 'x', 'type': 'token', 'value': 'v'}, user='nobody-here', expect=403, note='nobody stores credential')
s, _ = call('POST', A + '/credentials', {'name': '', 'type': 'token', 'value': 'v'}, expect=400, note='empty cred name')

# ---------------- assistant
s, sess = call('POST', A + '/assistant/sessions', {'mode': 'create'}, expect=[200, 201, 202])
sid = sess.get('sessionId') if isinstance(sess, dict) else None
if sid:
    req = urllib.request.Request(lib.BASE + A + f'/assistant/sessions/{sid}/messages', data=json.dumps({'text': 'Restart a VM after checking no backup is running, with an approval from compute-approvers'}).encode(), headers={'Content-Type': 'application/json', 'Accept': 'text/event-stream'}, method='POST')
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=60) as r:
        body = r.read().decode()
    types_ = [l[6:].strip() for l in body.splitlines() if l.startswith('event:')]
    print('assistant SSE ->', round(time.time() - t0, 1), 's events', types_[:12])
    check('done' in types_ or '"done"' in body, 'assistant stream had no done event')
    s, sd = call('GET', A + f'/assistant/sessions/{sid}', expect=200)
    check(isinstance(sd, dict) and sd.get('draft'), 'assistant session has no draft')
s, d1 = call('POST', A + '/assistant/drafts', {'description': 'Create a database and register it'}, expect=200)
check(isinstance(d1, dict) and d1.get('workflowYaml'), f'assistant draft: {str(d1)[:200]}')
s, _ = call('POST', A + '/assistant/drafts', {}, expect=400, note='draft without description')
s, _ = call('GET', A + '/assistant/sessions/nope', expect=404)

# ---------------- misc
s, _ = call('GET', '/actuator/health', expect=200)
s, env = call('GET', '/actuator/env', expect=[404, 401, 403], note='actuator env exposed?')
s, _ = call('GET', '/actuator/heapdump', expect=[404, 401, 403], note='heapdump exposed?')
s, _ = call('GET', '/actuator/prometheus', headers={'Accept': '*/*'}, expect=[200, 404])
s, opt = call('OPTIONS', A + '/workflows', headers={'Origin': 'https://evil.example.com', 'Access-Control-Request-Method': 'POST'}, expect=[403, 200], note='CORS evil origin')
print('CORS evil ->', s)
report()
