#!/usr/bin/env python3
"""Local OrbStack only: replace one disposable API pod and measure HTTP continuity."""
import json
import subprocess
import time
import urllib.error

command = ['kubectl', '--context', 'orbstack', '-n', 'saas-isolation-dev']
pods = json.loads(subprocess.check_output(command + ['get', 'pods', '-l', 'app=saas', '-o', 'json']))['items']
assert len(pods) >= 2 and all(
    any(c['type'] == 'Ready' and c['status'] == 'True' for c in p['status'].get('conditions', [])) for p in pods)
pod = pods[0]['metadata']['name']
body = urllib.parse.urlencode(
    dict(client_id='saas-ui', grant_type='password', username='alice', password='demo-password')).encode()
with urllib.request.urlopen(urllib.request.Request('http://localhost:8188/realms/saas/protocol/openid-connect/token',
                                                   data=body)) as response: token = json.load(response)['access_token']
headers = {'Authorization': 'Bearer ' + token}
with urllib.request.urlopen(urllib.request.Request('http://localhost:30088/api/v1/me/tenants',
                                                   headers=headers)) as response: tenants = json.load(response)
headers['X-Tenant-ID'] = next(t['id'] for t in tenants if t['state'] == 'ACTIVE')
subprocess.run(command + ['delete', 'pod', pod, '--wait=false'], check=True, stdout=subprocess.DEVNULL)
errors = 0;
success = 0;
failures = []
for index in range(30):
    try:
        with urllib.request.urlopen(urllib.request.Request('http://localhost:30088/api/v1/projects', headers=headers),
                                    timeout=3) as response:
            assert response.status == 200;
            json.load(response);
            success += 1
    except Exception as error:
        errors += 1;
        failures.append({'request_index': index, 'type': type(error).__name__, 'status': getattr(error, 'code', None)})
    time.sleep(.1)
subprocess.run(command + ['rollout', 'status', 'deployment/saas', '--timeout=45s'], check=True,
               stdout=subprocess.DEVNULL)
print(json.dumps({'requests': 30, 'success': success, 'errors': errors, 'failures': failures, 'replaced_pod': pod,
                  'scope': 'single-node local API pod termination; Compose database/workers remain running'}, indent=2))
assert success > 0 and errors == 0
