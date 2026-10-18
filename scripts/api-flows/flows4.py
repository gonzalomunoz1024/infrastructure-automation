"""Dependencies, previews with a dry run, create forms, replay and the publish checks."""
import os, sys, json, time, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib
from lib import call, check, issue, report
A = '/api/v1'

def wait_job(jid, want=('succeeded', 'failed', 'needs-attention', 'cancelled'), timeout=120):
    t0 = time.time()
    while time.time() - t0 < timeout:
        s, j = call('GET', A + f'/jobs/{jid}', expect=200)
        if isinstance(j, dict) and j.get('status') in want:
            return j
        time.sleep(1)
    issue(f'job {jid} did not settle in {timeout}s')
    return j

# ---------------- dependencies
s, dep = call('GET', A + '/registry/dependencies', expect=200)
check(isinstance(dep, dict) and {'resourceTypes', 'workflows', 'cycles'} <= set(dep), 'dependencies shape')
byw = {w['workflow']: w for w in dep.get('workflows', [])}
check('create-vm' in byw and any(r['workflow'] == 'build-vm-image' and r['via'] == 'orCreateWith' for r in byw['create-vm']['runs']), 'create-vm builds images on demand')
check('patch-cve' in byw and any(r['workflow'] == 'vm-restart' and r['range'] == '^1' for r in byw['patch-cve']['runs']), 'patch-cve runs vm-restart ^1')
vm_type = next((t for t in dep.get('resourceTypes', []) if t['type'] == 'vm'), None)
check(vm_type and any(p['workflow'] == 'create-vm' for p in vm_type['producers']), 'create-vm produces vms')
check(vm_type and any(c['workflow'] == 'vm-restart' for c in vm_type['consumers']), 'vm-restart consumes vms')
check(dep.get('cycles') == [], f'cycles: {dep.get("cycles")}')

# ---------------- previews: a dry run marks the untaken branch
s, dbs = call('GET', A + '/resources?type=database', expect=200)
dbs = dbs if isinstance(dbs, list) else dbs.get('items', [])
old = next((d for d in dbs if (d.get('attributes') or {}).get('version') == '7.0'), None)
new = next((d for d in dbs if (d.get('attributes') or {}).get('version') == '8.0'), None)
check(old and new, 'demo databases on 7.0 and 8.0')
for db, label in ((old, 'upgrade needed'), (new, 'already there')):
    if not db:
        continue
    s, pv = call('POST', A + f'/mongodb-clusters/{db["id"]}:upgrade:preview', {'clusterId': db['id'], 'targetVersion': '8.0'}, user='gonzalo', expect=200, note=label)
    if isinstance(pv, dict):
        steps = pv.get('steps', [])
        skipped = [st['id'] for st in steps if st.get('skipped')]
        taken = [st['id'] for st in steps if not st.get('skipped')]
        print(f'preview {label}: taken={taken} skipped={skipped}')
        check(skipped, f'{label}: no step skipped')
        check(all(st.get('effect') == 'none' for st in steps if st.get('skipped')), f'{label}: skipped step with an effect')
        check(all('dry run' in (st.get('detail') or '') for st in steps if st.get('skipped')), f'{label}: skipped step without the dry-run note')
        check(pv.get('version') and pv.get('workflow') == 'mongodb-upgrade', 'preview names the workflow version')
if old and new:
    # targetVersion is required but defaults to 8.0: a caller may leave it out
    s, p1 = call('POST', A + f'/mongodb-clusters/{old["id"]}:upgrade:preview', {'clusterId': old['id']}, user='gonzalo', expect=200, note='defaulted targetVersion')
    s, p2 = call('POST', A + f'/mongodb-clusters/{new["id"]}:upgrade:preview', {'clusterId': new['id']}, user='gonzalo', expect=200, note='defaulted targetVersion')
    sk1 = {st['id'] for st in p1.get('steps', []) if st.get('skipped')}
    sk2 = {st['id'] for st in p2.get('steps', []) if st.get('skipped')}
    # demo limitation: the mongodb provider's cluster.get sample answers 7.0 whatever the registry says, so both take the upgrade path
    check(sk1 and sk2 and 'restore' in sk1, f'the catch branch is skipped on the happy path: {sk1} {sk2}')

# a create form shows the build as its own steps, and is priced
s, pv = call('POST', A + '/vms:preview', {'name': 'web-77', 'image': {'create': {'os': 'rhel-9', 'hardening': 'cis-l2'}}, 'size': 'medium', 'owner': 'team-compute'}, user='gonzalo', expect=200, note='create form')
if isinstance(pv, dict):
    ids = [st['id'] for st in pv.get('steps', [])]
    print('create-vm preview steps:', ids)
    print('cost:', pv.get('cost'))
    check(any(i.startswith('image.create') for i in ids), 'the image build shows as its own steps')
    check(any(st.get('effect') == 'create' for st in pv.get('steps', [])), 'a create step')
    cost = pv.get('cost') or {}
    check(cost.get('monthly') or cost.get('detail'), f'cost missing: {cost}')
    check('price list' in (cost.get('detail') or '') or 'plan' in (cost.get('detail') or '') or 'sample' in (cost.get('detail') or ''), f'cost names its source: {cost.get("detail")}')
s, pv = call('POST', A + '/vms:preview', {'name': 'web-78', 'image': 'image-demo01', 'owner': 'team-compute'}, user='gonzalo', expect=200, note='existing image')
if isinstance(pv, dict):
    check(not any(st['id'].startswith('image.create') for st in pv.get('steps', [])), 'no build when the image exists')
s, pv = call('POST', A + '/vms:preview', {'name': 'web-79', 'image': 'image-nope', 'owner': 'team-compute'}, user='gonzalo', expect=400, note='unknown image id')

# ---------------- orCreateWith at run time
s, bad = call('POST', A + '/vms', {'name': 'web-80', 'image': {'create': {'os': 'bogus'}}, 'owner': 'team-compute'}, user='gonzalo', expect=400, note='create form refused by the producer')
if isinstance(bad, dict):
    paths = [p.get('path') or p.get('field') or '' for p in bad.get('problems', [])]
    check(any('image.create' in p for p in paths), f'problem path names the create form: {bad}')
s, acc = call('POST', A + '/vms', {'name': 'web-81', 'image': {'create': {'os': 'rhel-9', 'name': 'rhel-golden-e2e'}}, 'size': 'small', 'owner': 'team-compute'}, user='gonzalo', expect=202, note='create form accepted')
if isinstance(acc, dict) and acc.get('jobId'):
    j = wait_job(acc['jobId'])
    print('create-vm with create form ->', j.get('status'), [ (st['id'], st.get('status')) for st in j.get('steps', [])][:8])
    check(j.get('status') == 'succeeded', f'create-vm job {j.get("status")}: {str(j)[:300]}')
    build = next((st for st in j.get('steps', []) if st['id'].startswith('image.create')), None)
    check(build, 'the job shows the image build step')
    if build:
        child = build.get('childJobId') or (build.get('output') or {}).get('jobId') or build.get('jobId')
        print('build step:', {k: build[k] for k in build if k in ('id', 'status', 'childJobId', 'output', 'detail')})
        check(child, 'the build step links its child job')
        if child:
            s, cj = call('GET', A + f'/jobs/{child}', expect=200, note='child job')
            check(isinstance(cj, dict) and cj.get('workflow') == 'build-vm-image' and cj.get('status') == 'succeeded', f'child job: {str(cj)[:200]}')
    # the registered image is used by the vm
    s, imgs = call('GET', A + '/resources?type=image', expect=200)
    imgs = imgs if isinstance(imgs, list) else imgs.get('items', [])
    check(any('rhel-golden-e2e' in json.dumps(i) for i in imgs), 'the built image is registered')
    s, vms = call('GET', A + '/resources?type=vm', expect=200)
    vms = vms if isinstance(vms, list) else vms.get('items', [])
    vm81 = next((v for v in vms if (v.get('attributes') or {}).get('name') == 'web-81' or v.get('name') == 'web-81'), None)
    check(vm81, 'web-81 registered')
    if vm81:
        s, lin = call('GET', A + f'/resources/{vm81["id"]}', expect=200)
        print('web-81 lineage:', {k: lin.get(k) for k in ('builtFrom', 'dependsOn', 'lineage', 'parents') if k in lin})

# ---------------- replay against the last successful run
s, jobs = call('GET', A + '/jobs?workflow=vm-restart&limit=50', expect=200)
items = jobs if isinstance(jobs, list) else jobs.get('items', [])
done = [j for j in items if j.get('status') == 'succeeded' and j.get('kind') != 'batch']
check(len(done) >= 2, f'succeeded vm-restart jobs: {len(done)}')
if len(done) >= 2:
    s, rp = call('GET', A + f'/jobs/{done[0]["id"]}/replay', expect=200)
    check(isinstance(rp, dict) and rp.get('comparedTo') and rp.get('steps'), f'replay: {str(rp)[:200]}')
    if isinstance(rp, dict):
        check(rp.get('comparedTo') != done[0]['id'], 'compared with another job')
        print('replay diff fields:', [(st['id'], [d['field'] for d in st.get('diff', [])]) for st in rp.get('steps', []) if st.get('diff')][:4])
    s, rp2 = call('GET', A + f'/jobs/{done[0]["id"]}/replay?compareTo={done[1]["id"]}', expect=200)
    check(isinstance(rp2, dict) and rp2.get('comparedTo') == done[1]['id'], 'explicit compareTo honoured')
    s, _ = call('GET', A + f'/jobs/{done[0]["id"]}/replay?compareTo=nope', expect=[400, 404], note='unknown compareTo')

# ---------------- validate reports the publish checks
s, wf = call('GET', A + '/workflows/vm-restart', expect=200)
y = wf.get('yaml') if isinstance(wf, dict) else None
check(y, 'vm-restart yaml')
if y:
    # the served yaml is block style: required:\n      - vmId
    broken = re.sub(r'(\n    required:\n      - vmId\n)', r'\1      - ticket\n', y, count=1)
    broken = re.sub(r'(\n    properties:\n)', r'\1      ticket:\n        type: string\n        title: Ticket\n', broken, count=1)
    check(broken != y, 'could inject a new required input')
    s, v = call('POST', A + '/workflows:validate', {'yaml': broken}, user='gonzalo', expect=200, note='breaking change, same major')
    if isinstance(v, dict):
        codes = [p.get('code') for p in v.get('problems', [])]
        print('validate codes:', codes)
        contracts = [p for p in v.get('problems', []) if p.get('code') == 'publish.contracts']
        check(contracts, f'no publish.contracts problem: {codes}')
        check(any('patch-cve' in json.dumps(p) for p in contracts), f'contracts problem does not name patch-cve: {contracts}')
    # an unknown output field in an expression gets a did-you-mean fix
    typo = y.replace('$vm.powerState', '$vm.powerStat', 1) if '$vm.powerState' in y else y.replace('$json.powerState', '$json.powerStat', 1)
    check(typo != y, 'could inject an expression typo')
    s, v = call('POST', A + '/workflows:validate', {'yaml': typo}, user='gonzalo', expect=200, note='unknown field')
    if isinstance(v, dict):
        probs = v.get('problems', [])
        unknown = [p for p in probs if 'unknown' in (p.get('code') or '').lower() or 'field' in (p.get('code') or '').lower()]
        print('typo codes:', [p.get('code') for p in probs])
        check(unknown, f'no unknown-field problem: {[p.get("code") for p in probs]}')
        check(any('powerState' in json.dumps(p) for p in unknown), f'no did-you-mean fix: {unknown}')
    # a step-key typo is a yaml error that names the step
    s, v = call('POST', A + '/workflows:validate', {'yaml': y.replace('        timeout: 30m', '        timout: 30m', 1)}, user='gonzalo', expect=200, note='step key typo')
    check(isinstance(v, dict) and v.get('valid') is False and any('timout' in (p.get('message') or '') for p in v.get('problems', [])), f'step typo: {str(v)[:200]}')
    s, v = call('POST', A + '/workflows:validate', {'yaml': y}, user='gonzalo', expect=200, note='the published file itself')
    check(isinstance(v, dict) and v.get('valid') is True, f'published vm-restart not valid: {str(v)[:300]}')

# ---------------- OpenAPI and MCP show both forms of an orCreateWith input
s, oa = call('GET', A + '/openapi.json', expect=200)
if isinstance(oa, dict):
    body = oa.get('paths', {}).get('/api/v1/vms', {}).get('post', {}).get('requestBody', {})
    check('anyOf' in json.dumps(body) and 'create' in json.dumps(body), 'openapi shows the create form')
s, tools = call('POST', A + '/mcp', {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list'}, user='gonzalo', expect=200)
if isinstance(tools, dict):
    t = next((t for t in tools.get('result', {}).get('tools', []) if 'create' in t['name'] and 'vm' in t['name'] and 'image' not in t['name']), None)
    check(t and 'anyOf' in json.dumps(t.get('inputSchema')), f'mcp create-vm tool shows the create form: {t and t["name"]}')
report()
