"""Workflows, drafts, validation, imports, dry runs and the designer-facing endpoints."""
import os, sys, json, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib
from lib import call, check, issue, report

A = '/api/v1'

# a ready VM from the registry: the seeded ids are derived from the content, not fixed
s, _vms = call('GET', A + '/resources?type=vm&status=ready', expect=200)
_items = _vms if isinstance(_vms, list) else (_vms.get('items') or []) if isinstance(_vms, dict) else []
VM = _items[0]['id'] if _items else 'vm-1042'
# ---------------- identity
s, me = call('GET', A + '/me', expect=200)
s, ana = call('GET', A + '/me', user='ana', expect=200)
check(isinstance(ana, dict) and ana.get('id') == 'ana' and not ana.get('admin'), f'ana identity wrong: {ana}')
s, unknown = call('GET', A + '/me', user='nobody-here', expect=[200, 401, 403], note='unknown user')
print('unknown user ->', s, str(unknown)[:200])
s, sas = call('GET', A + '/service-accounts', expect=200)
s, sa = call('POST', A + '/service-accounts', {'name': 'ci-bot', 'roles': ['ops'], 'teams': ['team-compute']}, expect=201)
token = sa.get('token') if isinstance(sa, dict) else None
check(bool(token), f'service account token missing: {sa}')
s, _ = call('POST', A + '/service-accounts', {'name': 'x', 'roles': ['ops']}, user='ana', expect=403, note='non-admin creates SA')
s, _ = call('GET', A + '/service-accounts', user='ana', expect=403, note='non-admin lists SAs')
# without teams an account acts for its creator's teams, and the response says so
s, sa_default = call('POST', A + '/service-accounts', {'name': 'nightly-bot', 'roles': ['ops']}, expect=201)
check(isinstance(sa_default, dict) and sa_default.get('teams') == (me or {}).get('teams')
      and 'No teams were given' in str(sa_default.get('note')), f'SA without teams: {sa_default}')
s, sas2 = call('GET', A + '/service-accounts', expect=200)
check(all('token' not in a for a in (sas2 or [])), 'service account list leaks tokens')
if token:
    s, me_sa = call('GET', A + '/me', headers={'Authorization': 'Bearer ' + token}, expect=200, note='bearer')
    check(isinstance(me_sa, dict) and me_sa.get('kind') in ('service', 'service-account', 'service_account') , f'bearer identity: {me_sa}')
    s, _ = call('GET', A + '/me', headers={'Authorization': 'Bearer nope'}, expect=401, note='bad bearer')
s, _ = call('POST', A + '/service-accounts', {'roles': ['ops']}, expect=400, note='SA without name')

# ---------------- workflows read
s, wfs = call('GET', A + '/workflows', expect=200)
check(isinstance(wfs, list) and len(wfs) >= 9, f'workflows list: {str(wfs)[:200]}')
s, wf = call('GET', A + '/workflows/vm-restart', expect=200)
for k in ('yaml', 'definition', 'versions', 'problems', 'changeRequests', 'publishedVersion'):
    check(isinstance(wf, dict) and k in wf, f'workflow detail missing {k}')
s, _ = call('GET', A + '/workflows/nope-nope', expect=404)
s, _ = call('GET', A + '/workflows?status=published&q=vm', expect=200)
s, vers = call('GET', A + '/workflows/vm-restart/versions', expect=200)
if isinstance(vers, list) and vers:
    v0 = vers[0]['version']
    s, vd = call('GET', A + f'/workflows/vm-restart/versions/{v0}', expect=200)
    check(isinstance(vd, dict) and 'yaml' in vd and 'definition' in vd, 'version detail shape')
s, _ = call('GET', A + '/workflows/vm-restart/versions/9.9.9', expect=404)
s, tpl = call('GET', A + '/templates', expect=200)
check(isinstance(tpl, list) and len(tpl) > 0, 'no templates')

# render/parse round trip
s, parsed = call('POST', A + '/workflows:parse', {'yaml': wf['yaml']}, expect=200)
s, rendered = call('POST', A + '/workflows:render', {'definition': parsed['definition']}, expect=200)
check(rendered.get('yaml') == wf['yaml'], 'render(parse(yaml)) != yaml for vm-restart')
s, bad = call('POST', A + '/workflows:parse', {'yaml': 'workflow: [unclosed'}, expect=[200, 400], note='broken yaml')
print('parse broken ->', s, str(bad)[:200])
s, val = call('POST', A + '/workflows:validate', {'yaml': wf['yaml']}, expect=200)
check(val.get('valid') is True, f'vm-restart not valid: {val}')
s, val2 = call('POST', A + '/workflows:validate', {'yaml': 'apiVersion: iaf.platform/v1\nkind: Workflow\nmetadata: {name: x}\n'}, expect=200)
check(val2.get('valid') is False and val2.get('problems'), 'empty workflow should be invalid')
s, val3 = call('POST', A + '/workflows:validate', {}, expect=200, note='validate without yaml')
check(isinstance(val3, dict) and val3.get('valid') is False and any(p.get('code') == 'yaml.invalid' for p in val3.get('problems', [])), f'validate without yaml: {val3}')

# imports: a workflow drafted from something a registered provider describes (a tile, a playbook, a module, an action)
for src, ref in (('idp', 'block-storage-request'), ('ansible', 'ops-playbooks/vm.restart'), ('terraform', 'terraform-modules'), ('openapi', 'vsphere')):
    s, imp = call('POST', A + '/workflows:import', {'source': src, 'ref': ref}, expect=200, note=src)
    check(isinstance(imp, dict) and imp.get('yaml'), f'import {src} returned no yaml: {str(imp)[:200]}')
    if isinstance(imp, dict) and imp.get('yaml'):
        s, v = call('POST', A + '/workflows:validate', {'yaml': imp['yaml']}, expect=200, note=f'imported {src} validates')
        check(isinstance(v, dict) and not any(p.get('level') == 'error' for p in v.get('problems', [])), f'imported {src} draft has errors: {[p for p in v.get("problems", []) if p.get("level") == "error"][:3]}')
# unknown sources are refused with the known ones named
for src, ref in (('idp', 'tile-vm-request'), ('ansible', 'github.com/acme/ops-playbooks'), ('terraform', 'registry/acme/vm/vsphere'), ('openapi', 'https://example.com/openapi.json')):
    s, imp = call('POST', A + '/workflows:import', {'source': src, 'ref': ref}, expect=400, note=f'{src} unknown ref')
    check(isinstance(imp, dict) and 'known' in (imp.get('error') or ''), f'unknown {src} ref should name the known ones: {imp}')
s, _ = call('POST', A + '/workflows:import', {'source': 'cobol', 'ref': 'x'}, expect=400, note='bad import source')

# dry run
s, dr = call('POST', A + '/workflows:dry-run', {'yaml': wf['yaml'], 'input': {'vmId': VM, 'reason': 'e2e'}}, expect=200)
check(isinstance(dr, dict) and 'passed' in dr and dr.get('steps'), f'dry run shape {str(dr)[:300]}')
print('dry run passed:', dr.get('passed'), 'path', dr.get('path'))
s, dr2 = call('POST', A + '/workflows:dry-run', {'yaml': wf['yaml'], 'input': {}}, expect=[200, 400], note='dry run missing input')
print('dry run without input ->', s, str(dr2)[:200])

# ---------------- create, publish, merge, invoke a new workflow
yaml_new = wf['yaml'].replace('name: vm-restart', 'name: vm-reboot-e2e').replace('verb: restart', 'verb: reboot').replace(':restart', ':reboot')
s, created = call('PUT', A + '/workflows/vm-reboot-e2e/draft', {'yaml': yaml_new}, expect=200)
print('draft ->', str(created)[:300])
s, _ = call('POST', A + '/workflows', {'yaml': yaml_new}, expect=409, note='create existing')
s, pub = call('POST', A + '/workflows/vm-reboot-e2e:publish', {'strategy': 'all', 'notes': 'e2e'}, expect=202)
crid = pub.get('id') if isinstance(pub, dict) else None
s, cr = call('GET', A + f'/change-requests/{crid}', expect=200)
check(isinstance(cr, dict) and cr.get('diff') is not None and cr.get('checks'), f'CR shape {str(cr)[:300]}')
print('checks:', [(c['id'], c['status']) for c in cr.get('checks', [])])
s, m = call('POST', A + f'/change-requests/{crid}:merge', expect=202)
time.sleep(0.5)
s, wf2 = call('GET', A + '/workflows/vm-reboot-e2e', expect=200)
check(wf2.get('status') == 'published', f'not published after merge: {wf2.get("status")}')
s, cat = call('GET', A + '/catalog', expect=200)
entry = next((e for e in cat if e['workflow'] == 'vm-reboot-e2e'), None)
check(entry is not None, 'new workflow missing from catalog')
if entry:
    path = entry['path'].replace('{vmId}', VM)
    s, inv = call(entry['method'], path, {'reason': 'e2e'}, expect=202, note='invoke new route')
    jid = inv.get('jobId') if isinstance(inv, dict) else None
    time.sleep(1.5)
    s, job = call('GET', A + f'/jobs/{jid}', expect=200)
    print('new job status', job.get('status'), [ (st['id'], st['status']) for st in job.get('steps', [])][:10])
s, _ = call('DELETE', A + '/workflows/vm-reboot-e2e', expect=[409, 400], note='delete published')
# a draft that is deleted
s, _ = call('PUT', A + '/workflows/scratch-e2e/draft', {'yaml': yaml_new.replace('vm-reboot-e2e', 'scratch-e2e').replace('verb: reboot', 'verb: scratch').replace(':reboot', ':scratch')}, expect=200)
s, _ = call('DELETE', A + '/workflows/scratch-e2e', expect=204)
s, _ = call('GET', A + '/workflows/scratch-e2e', expect=404)
# rbac: ana (team-data) edits a team-compute workflow
s, _ = call('PUT', A + '/workflows/vm-restart/draft', {'yaml': wf['yaml']}, user='ana', expect=403, note='ana edits compute wf')
s, _ = call('POST', A + '/workflows/vm-restart:publish', {}, user='junior', expect=[403, 409, 400], note='junior publishes')
# path traversal in names
s, _ = call('PUT', A + '/workflows/..%2F..%2Fetc/draft', {'yaml': yaml_new}, expect=[400, 404], note='traversal name')
s, _ = call('PUT', A + '/workflows/Bad_Name/draft', {'yaml': yaml_new.replace('vm-reboot-e2e', 'Bad_Name')}, expect=400, note='bad name')
# name mismatch between path and yaml
s, mm = call('PUT', A + '/workflows/other-name/draft', {'yaml': yaml_new}, expect=[400, 409], note='path/yaml name mismatch')
report()
