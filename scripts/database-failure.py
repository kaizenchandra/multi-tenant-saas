#!/usr/bin/env python3
"""Local-only fail-closed database drill; always starts this project's DB in finally."""
import json
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1];
compose = ['docker', 'compose', '-f', str(root / 'docker-compose.yml')]


def login(user):
    data = urllib.parse.urlencode(
        dict(client_id='saas-ui', grant_type='password', username=user, password='demo-password')).encode()
    with urllib.request.urlopen(
            urllib.request.Request('http://localhost:8188/realms/saas/protocol/openid-connect/token',
                                   data=data)) as r: return json.load(r)['access_token']


def request(path, token=None, tenant=None, method='GET', body=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    if tenant: headers['X-Tenant-ID'] = tenant
    data = json.dumps(body).encode() if body is not None else b'' if method == 'POST' else None
    try:
        with urllib.request.urlopen(
                urllib.request.Request('http://localhost:8088' + path, headers=headers, method=method, data=data),
                timeout=15) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)


a, admin = login('alice'), login('platform');
name = 'db-failure-' + uuid.uuid4().hex[:12]
status, t = request('/api/v1/tenants', a, method='POST', body={'slug': name, 'name': name});
assert status == 201;
t = t['id']
assert request('/api/v1/admin/tenants/' + t + '/provision', admin, method='POST')[0] == 200
assert request('/api/v1/projects', a, t, 'POST', {'name': 'Protected cached value', 'status': 'OPEN'})[0] == 201
for _ in range(2): assert request('/api/v1/projects', a, t)[0] == 200
try:
    subprocess.run(compose + ['stop', '--timeout', '3', 'postgres'], check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL)
    status, body = request('/api/v1/projects', a, t);
    assert status == 503, (status, body);
    assert 'items' not in body and 'Protected cached value' not in json.dumps(body)
    live = request('/actuator/health/liveness')[0];
    ready = request('/actuator/health/readiness')[0];
    assert live == 200 and ready == 503, (live, ready)
finally:
    subprocess.run(compose + ['start', 'postgres'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
for _ in range(30):
    status, body = request('/api/v1/projects', a, t)
    if status == 200: break
    time.sleep(.5)
assert status == 200 and len(body['items']) == 1, (status, body)
print(json.dumps(
    {'result': 'PASS', 'outage_api_status': 503, 'liveness_during_outage': live, 'readiness_during_outage': ready,
     'cached_tenant_data_not_served': True, 'recovery_api_status': status,
     'scope': 'local PostgreSQL stopped/restarted; no primary/replica failover or DR RTO claim'}, indent=2))
