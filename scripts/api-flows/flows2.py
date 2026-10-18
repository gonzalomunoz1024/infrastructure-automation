"""Jobs, approvals, attention, batches, triggers, resources and releases."""
import os, sys, json, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib
from lib import call, check, issue, report

A = '/api/v1'

# a ready VM from the registry: the seeded ids are derived from the content, not fixed
s, _vms = call('GET', A + '/resources?type=vm&status=ready', expect=200)
_items = _vms if isinstance(_vms, list) else (_vms.get('items') or []) if isinstance(_vms, dict) else []
VM = _items[0]['id'] if _items else 'vm-1042'
VM2 = _items[1]['id'] if len(_items) > 1 else VM
VM3 = _items[2]['id'] if len(_items) > 2 else VM
def wait_status(jid, statuses, timeout=20):
    t0 = time.time()
    while time.time() - t0 < timeout:
        s, j = call('GET', A + f'/jobs/{jid}')
        if isinstance(j, dict) and j.get('status') in statuses:
            return j
        time.sleep(0.5)
    s, j = call('GET', A + f'/jobs/{jid}')
    issue(f'job {jid} did not reach {statuses}: {j.get("status") if isinstance(j, dict) else j}')
    return j

# ---------------- catalog + preview + invoke
s, cat = call('GET', A + '/catalog', expect=200)
s, oa = call('GET', '/openapi.json', expect=200)
s, oa2 = call('GET', A + '/openapi.json', expect=200)
check(isinstance(oa, dict) and oa.get('openapi', '').startswith('3.1'), 'openapi version')
check(oa == oa2, 'two openapi documents differ')
s, pv = call('POST', A + f'/vms/{VM}:restart:preview', {'reason': 'e2e'}, expect=200, note='preview')
check(isinstance(pv, dict) and pv.get('steps') and pv.get('policy'), f'preview shape {str(pv)[:200]}')
s, bad = call('POST', A + f'/vms/{VM}:restart', {'reason': 123}, expect=400, note='bad input type')
check(isinstance(bad, dict) and bad.get('problems'), f'400 without problems: {bad}')
s, _ = call('POST', A + '/databases', {'name': 'x'}, expect=400, note='missing required')
s, _ = call('POST', A + f'/vms/{VM}:restart', {'reason': 'e2e'}, user='nobody', expect=403, note='rbac unknown user restart')
s, inv = call('POST', A + f'/vms/{VM}:restart', {'reason': 'e2e restart'}, expect=202)
jid = inv['jobId']
check(inv.get('links', {}).get('job'), 'invoke has no job link')
j = wait_status(jid, ['waiting-approval'])
s, appr = call('GET', A + '/approvals?status=pending', expect=200)
mine = [a for a in appr if a['jobId'] == jid]
check(len(mine) == 1, f'expected one pending approval for {jid}, got {len(mine)}')
if mine:
    aid = mine[0]['id']
    s, a1 = call('GET', A + f'/approvals/{aid}', expect=200)
    s, _ = call('POST', A + f'/approvals/{aid}:approve', {'comment': 'self'}, user='junior', expect=403, note='junior approves')
    s, _ = call('POST', A + f'/approvals/{aid}:approve', {'comment': 'ok'}, expect=202)
    s, _ = call('POST', A + f'/approvals/{aid}:approve', {'comment': 'again'}, expect=409, note='approve twice')
    j = wait_status(jid, ['succeeded', 'failed', 'needs-attention'])
    print('restart job ->', j.get('status'), [(st['id'], st['status']) for st in j.get('steps', [])])
s, _ = call('GET', A + '/approvals/apr_nope', expect=404)

# rejected approval
s, inv2 = call('POST', A + f'/vms/{VM2}:restart', {'reason': 'reject me'}, expect=202)
j2 = wait_status(inv2['jobId'], ['waiting-approval'])
s, appr = call('GET', A + '/approvals?status=pending', expect=200)
a2 = next((a for a in appr if a['jobId'] == inv2['jobId']), None)
if a2:
    s, _ = call('POST', A + f'/approvals/{a2["id"]}:reject', {'comment': 'no'}, expect=202)
    j2 = wait_status(inv2['jobId'], ['failed', 'cancelled', 'rolled-back'])
    print('rejected job ->', j2.get('status'))

# cancel a waiting job: a stranger to the job and its workflow is refused first
s, inv3 = call('POST', A + f'/vms/{VM3}:restart', {'reason': 'cancel me'}, expect=202)
wait_status(inv3['jobId'], ['waiting-approval'])
s, _ = call('POST', A + f'/jobs/{inv3["jobId"]}:cancel', user='nobody', expect=403, note='stranger cancels')
s, _ = call('POST', A + f'/jobs/{inv3["jobId"]}:cancel', expect=202)
j3 = wait_status(inv3['jobId'], ['cancelled'])
s, _ = call('POST', A + f'/jobs/{inv3["jobId"]}:cancel', expect=409, note='cancel twice')
s, _ = call('POST', A + '/jobs/job_nope:cancel', expect=404)

# steering is for the requester, the workflow's owner team, its rbac roles and approvers, or an admin:
# junior (team-compute, ops) may steer vm-restart jobs but not a team-data upgrade
s, _dbs = call('GET', A + '/resources?type=database&status=ready', expect=200)
_dbitems = _dbs if isinstance(_dbs, list) else (_dbs.get('items') or []) if isinstance(_dbs, dict) else []
if _dbitems:
    db = _dbitems[0]
    target = '7.0' if (db.get('attributes') or {}).get('version') == '8.0' else '8.0'
    s, up = call('POST', A + f'/mongodb-clusters/{db["id"]}:upgrade', {'targetVersion': target}, expect=202, note='upgrade to cancel')
    if isinstance(up, dict) and up.get('jobId'):
        wait_status(up['jobId'], ['waiting-approval', 'needs-attention', 'succeeded', 'failed'])
        s, _ = call('POST', A + f'/jobs/{up["jobId"]}:cancel', user='junior', expect=403, note='junior cancels a data job')
        s, _ = call('POST', A + f'/jobs/{up["jobId"]}:retry', {}, user='junior', expect=403, note='junior retries a data job')
        s, _ = call('POST', A + f'/jobs/{up["jobId"]}:cancel', expect=[202, 409], note='gonzalo cancels it')
else:
    issue('no ready database to try the steering rule on')

# generic POST /jobs
s, gj = call('POST', A + '/jobs', {'workflow': 'request-storage', 'input': {'vmId': VM, 'sizeGb': 50}}, expect=202, note='generic start')
s, _ = call('POST', A + '/jobs', {'workflow': 'nope', 'input': {}}, expect=404, note='generic unknown wf')
s, _ = call('POST', A + '/jobs', {'workflow': 'request-storage', 'input': {'sizeGb': 50}}, expect=400, note='generic bad input')
if isinstance(gj, dict) and gj.get('id'):
    gjob = wait_status(gj['id'], ['succeeded', 'failed', 'needs-attention', 'waiting-approval'])
    print('request-storage ->', gjob.get('status'))

# needs attention seeded job
s, jobs = call('GET', A + '/jobs?status=needs-attention', expect=200)
att = jobs[0] if jobs else None
check(att is not None, 'no needs-attention job seeded')
if att:
    s, ad = call('GET', A + f'/jobs/{att["id"]}', expect=200)
    check(ad.get('attention') and ad['attention'].get('options'), f'attention block missing: {str(ad.get("attention"))[:200]}')
    step = ad['attention']['step']
    s, _ = call('POST', A + f'/jobs/{att["id"]}:retry', {'step': step}, expect=202, note='retry attention')
    aj = wait_status(att['id'], ['succeeded', 'failed', 'needs-attention'])
    print('attention retry ->', aj.get('status'))
    s, rp = call('GET', A + f'/jobs/{att["id"]}/replay', expect=200)
    check(isinstance(rp, dict) and rp.get('steps'), 'replay empty')

# rerun from a step on a succeeded job
s, done = call('GET', A + '/jobs?workflow=vm-restart&status=succeeded&limit=5', expect=200)
if done:
    src = done[0]['id']
    s, sd = call('GET', A + f'/jobs/{src}', expect=200)
    steps = [st['id'] for st in sd['steps']]
    from_step = steps[-1]
    s, rr = call('POST', A + f'/jobs/{src}:rerun', {'fromStep': from_step}, expect=202, note='rerun last step')
    if isinstance(rr, dict) and rr.get('id'):
        rj = wait_status(rr['id'], ['succeeded', 'failed', 'needs-attention', 'waiting-approval'])
        print('rerun ->', rj.get('status'), [(st['id'], st['status']) for st in rj.get('steps', [])])
        check(not any(st['status'] == 'waiting' and st['type'] == 'approval' for st in rj.get('steps', [])), 'rerun asked approval again')
    s, _ = call('POST', A + f'/jobs/{src}:rerun', {'fromStep': 'noSuchStep'}, expect=400, note='rerun unknown step')

# batch
s, bb = call('POST', A + '/vms:patch:batch', {'items': [{'vmId': VM}], 'defaults': {'cve': 'CVE-2026-1234'}}, expect=[202, 404], note='batch wrong path')
pc = next(e for e in cat if e['workflow'] == 'patch-cve')
bpath = pc['batchPath']
s, bad = call('POST', bpath, {'items': [{'vmId': VM}, {'cve': 'x'}], 'defaults': {}}, expect=400, note='batch invalid rows')
check(isinstance(bad, dict) and bad.get('rows'), f'batch 400 without rows: {bad}')
items = [{'vmId': r['id']} for r in _items]  # every ready VM of the registry
s, b = call('POST', bpath, {'items': items, 'defaults': {'cve': 'CVE-2026-4242', 'severity': 'high'}, 'rollout': {'waves': [25, 100], 'maxParallel': 4, 'stopIfFailedPercent': 30}}, expect=202, note='batch of the fleet')
if isinstance(b, dict) and b.get('jobId'):
    bj = wait_status(b['jobId'], ['succeeded', 'failed', 'needs-attention', 'waiting-approval', 'running'], timeout=5)
    time.sleep(3)
    s, bj = call('GET', A + f'/jobs/{b["jobId"]}', expect=200)
    print('batch ->', bj.get('status'), bj.get('progress'), [(w['index'], w['status']) for w in (bj.get('batch') or {}).get('waves', [])])
# stop the batch, then retry its failed, cancelled and skipped rows: the same job runs them in one more wave
if isinstance(b, dict) and b.get('jobId'):
    bid = b['jobId']
    s, _ = call('POST', A + f'/jobs/{bid}:cancel', expect=[202, 409], note='stop the batch')
    bj = wait_status(bid, ['succeeded', 'failed', 'cancelled', 'needs-attention'], timeout=30)
    rows = (bj.get('batch') or {}).get('rows', []) if isinstance(bj, dict) else []
    waves = len((bj.get('batch') or {}).get('waves', [])) if isinstance(bj, dict) else 0
    if any(r.get('status') in ('failed', 'cancelled', 'skipped') for r in rows):
        s, rt = call('POST', A + f'/jobs/{bid}:retry-failed-rows', expect=202, note='retry failed rows')
        check(isinstance(rt, dict) and rt.get('id') == bid, f'retry-failed-rows answered another job: {rt}')
        s, rj = call('GET', A + f'/jobs/{bid}', expect=200)
        check(len((rj.get('batch') or {}).get('waves', [])) == waves + 1, 'retry-failed-rows added no wave')
        # the new wave starts its rows again, maxParallel at a time (a row that reboots waits for vm-restart's own approval)
        t0 = time.time()
        while time.time() - t0 < 30:
            s, rj = call('GET', A + f'/jobs/{bid}')
            last = ((rj.get('batch') or {}).get('waves') or [{}])[-1] if isinstance(rj, dict) else {}
            if last.get('status') != 'pending' and any(r.get('jobId') for r in rj['batch']['rows'] if r.get('wave') == last.get('index')):
                break
            time.sleep(0.5)
        else:
            issue(f'the retried rows of {bid} did not start: {last}')
        print('retried batch ->', rj.get('status'), rj.get('progress'), [(w['index'], w['status']) for w in rj['batch']['waves']])
    else:
        s, _ = call('POST', A + f'/jobs/{bid}:retry-failed-rows', expect=409, note='retry a batch with no failed rows')
s, _ = call('POST', bpath, {'items': []}, expect=400, note='empty batch')
s, _ = call('POST', bpath, {'items': [{'vmId': f'vm-{i}'} for i in range(5000)], 'defaults': {'cve': 'CVE-1'}}, expect=400, note='batch over max')

# SSE
import urllib.request
req = urllib.request.Request(lib.BASE + A + f'/jobs/{jid}/events', headers={'Accept': 'text/event-stream'})
try:
    with urllib.request.urlopen(req, timeout=3) as r:
        chunk = r.read(600).decode(errors='replace')
        check('data:' in chunk, 'SSE produced no data lines')
except Exception as e:
    if 'timed out' not in str(e):
        issue(f'SSE error {e}')

# MCP
s, init = call('POST', A + '/mcp', {'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {'protocolVersion': '2025-06-18', 'capabilities': {}, 'clientInfo': {'name': 'e2e', 'version': '1'}}}, expect=200)
s, tl = call('POST', A + '/mcp', {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/list'}, expect=200)
names = [t['name'] for t in tl['result']['tools']]
check('vm_restart' in names and 'get_job_status' in names and 'list_vms' in names, f'mcp tools: {names}')
s, tc = call('POST', A + '/mcp', {'jsonrpc': '2.0', 'id': 3, 'method': 'tools/call', 'params': {'name': 'vm_restart', 'arguments': {'vmId': VM, 'reason': 'mcp'}}}, expect=200)
print('mcp call ->', str(tc)[:300])
s, tc2 = call('POST', A + '/mcp', {'jsonrpc': '2.0', 'id': 4, 'method': 'tools/call', 'params': {'name': 'vm_restart', 'arguments': {}}}, expect=200, note='mcp bad args')
check('error' in tc2 or (tc2.get('result') or {}).get('isError'), f'mcp bad args not an error: {str(tc2)[:200]}')
s, tc3 = call('POST', A + '/mcp', {'jsonrpc': '2.0', 'id': 5, 'method': 'tools/call', 'params': {'name': 'list_vms', 'arguments': {}}}, expect=200)
s, tc4 = call('POST', A + '/mcp', {'jsonrpc': '2.0', 'id': 6, 'method': 'nope'}, expect=200, note='mcp unknown method')
check(tc4.get('error', {}).get('code') == -32601, f'unknown method code {tc4}')
s, tc5 = call('POST', A + '/mcp', b'{not json', expect=[200, 400], headers={'Content-Type': 'application/json'}, note='mcp parse error')
print('mcp parse error ->', s, str(tc5)[:200])
s, tc6 = call('POST', A + '/mcp', {'jsonrpc': '2.0', 'method': 'notifications/initialized'}, expect=[200, 202, 204], note='mcp notification')
s, tc7 = call('POST', A + '/mcp', [{'jsonrpc': '2.0', 'id': 7, 'method': 'ping'}, {'jsonrpc': '2.0', 'id': 8, 'method': 'tools/list'}], expect=200, note='mcp batch')
s, tc8 = call('POST', A + '/mcp', {'jsonrpc': '2.0', 'id': 9, 'method': 'tools/call', 'params': {'name': 'vm_restart', 'arguments': {'vmId': VM}}}, user='nobody', expect=200, note='mcp rbac unknown user')
check('error' in tc8 or (tc8.get('result') or {}).get('isError'), f'mcp rbac not enforced: {str(tc8)[:200]}')

# triggers
s, ta = call('POST', A + '/triggers/alerts', {'source': 'grafana', 'labels': {'alertname': 'VMUnresponsive', 'vm_id': VM, 'severity': 'critical'}, 'annotations': {'summary': 'VM stopped answering'}}, expect=202)
print('alert trigger ->', str(ta)[:300])
s, te = call('POST', A + '/triggers/events', {'topic': 'cve.published', 'payload': {'cve': 'CVE-2026-9999'}}, expect=202)
print('event trigger ->', str(te)[:300])
s, _ = call('POST', A + '/triggers/alerts', {}, expect=[202, 400], note='empty alert')
report()
