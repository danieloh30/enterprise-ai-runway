#!/usr/bin/env python3
"""Optional end-to-end checks against a running stack (dev or packaged); standard library only."""
import concurrent.futures, json, os, time, urllib.request, urllib.error
BASE=os.environ.get('DEMO_URL','http://localhost:8090')
KEY=os.environ['DEMO_API_KEY']
def request(path,body=None,auth=True,method=None):
 headers={'Content-Type':'application/json'}
 if auth: headers['Authorization']='Bearer '+KEY
 req=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
 try:
  with urllib.request.urlopen(req,timeout=25) as r: return r.status,json.loads(r.read() or '{}')
 except urllib.error.HTTPError as e:
  raw=e.read()
  try: data=json.loads(raw)
  except ValueError: data={'raw':raw.decode()}
  return e.code,data

def finish(run_id):
 deadline=time.monotonic()+170
 while time.monotonic()<deadline:
  code,run=request('/api/runs/'+run_id);assert code==200,run
  if run['status']!='RUNNING': return run
  time.sleep(.5)
 raise AssertionError('Run failed to reach a terminal state')
assert request('/api/incidents',auth=False)[0]==401
assert request('/api/runs',{'incidentId':'INC-2042','mode':'invalid','prompt':''})[0]==400
for probe,expected in [('unauthorized',401),('forbidden-tool',403),('invalid-arguments',400)]:
 code,result=request('/api/probes/'+probe,{},method='POST');assert code==200 and result['status']==expected,result
print('PASS: API authentication, input validation, gateway 401/403/400 probes')
code,bp=request('/api/blueprint',{'prompt':'Build a safe investigation agent'});assert code==200 and 'templates' in bp['generator']
assert KEY not in json.dumps(bp)
mode=os.environ.get('SMOKE_MODE','rehearsal')
code,result=request('/api/runs',{'incidentId':'INC-2042','mode':mode,'prompt':'Investigate this incident using all three read tools. Recommend a safe follow-up.'})
assert code==202,result
run_id=result['id']
run=finish(run_id);assert run['status']=='AWAITING_APPROVAL',run
assert len([e for e in run['events'] if e['kind']=='TOOL_RESULT'])==3,run
assert run['report'].strip(),run
if mode=='live':
 assert all(any(e['actor']==actor and e['kind']=='COMPLETED' for e in run['events']) for actor in ['investigator','reviewer']),run
 assert 'REHEARSAL' not in run['report'],run
print('PASS: '+mode+' investigation traversed gateway, MCP and PostgreSQL')
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
 replies=list(pool.map(lambda _:request('/api/runs/'+run_id+'/approve',{},method='POST'),range(2)))
assert any(code==200 for code,_ in replies),replies
assert all(code in (200,409) for code,_ in replies),replies
code,first=request('/api/runs/'+run_id+'/approve',{},method='POST');assert code==200,first
code,tasks=request('/api/followups');assert len([t for t in tasks if t['run_id']==run_id])==1,tasks
assert first['run_id']==run_id
print('PASS: concurrent approval and retry create exactly one follow-up')
code,result=request('/api/runs',{'incidentId':'INC-2043','mode':'rehearsal','prompt':'Check inventory'})
assert code==202,result
rejected=finish(result['id']);assert rejected['status']=='AWAITING_APPROVAL',rejected
assert request('/api/runs/'+result['id']+'/reject',{},method='POST')[0]==200
assert request('/api/runs/'+result['id']+'/approve',{},method='POST')[0]==409
print('PASS: rejected runs cannot create follow-ups')
code,audit=request('/api/audit');assert code==200
assert any(a['decision']=='DENY' and a['status']==403 for a in audit)
assert any(a['tool']=='create_followup' and a['decision']=='ALLOW' for a in audit)
print('PASS: policy decisions persist in the audit log')
print('All demo-flow smoke checks passed.')
