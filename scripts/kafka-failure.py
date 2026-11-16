#!/usr/bin/env python3
"""Local-only failure drill; always restarts this project's broker in finally."""
import json
import subprocess
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

compose = ['docker', 'compose', '-f', str(Path(__file__).resolve().parents[1] / 'docker-compose.yml')]


def login(user):
    body = urllib.parse.urlencode(
        dict(client_id='saas-ui', grant_type='password', username=user, password='demo-password')).encode()
    with urllib.request.urlopen(
            urllib.request.Request('http://localhost:8188/realms/saas/protocol/openid-connect/token',
                                   data=body)) as r: return json.load(r)['access_token']


def api(token, path, method='GET', data=None, tenant=None):
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
    if tenant: headers['X-Tenant-ID'] = tenant
    body = json.dumps(data).encode() if data is not None else b'' if method == 'POST' else None
    with urllib.request.urlopen(
            urllib.request.Request('http://localhost:8088/api/v1' + path, headers=headers, method=method, data=body),
            timeout=15) as r: return json.load(r)


alice, admin = login('alice'), login('platform')
tenant = \
    api(alice, '/tenants', 'POST', {'slug': 'broker-drill-' + uuid.uuid4().hex[:12], 'name': 'Broker failure drill'})[
        'id']
api(admin, '/admin/tenants/' + tenant + '/provision', 'POST')
pending = None
try:
    subprocess.run(compose + ['stop', '--timeout', '3', 'kafka'], check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL)
    started = time.monotonic();
    project = api(alice, '/projects', 'POST', {'name': 'Committed during broker outage', 'status': 'OPEN'}, tenant);
    commit_seconds = time.monotonic() - started
    for _ in range(20):
        rows = api(alice, '/tenants/' + tenant + '/events')['outbox'];
        pending = next(e for e in rows if e['type'] == 'ProjectCreated')
        if pending['attempts'] >= 1: break
        time.sleep(.5)
    assert pending['published'] is False, pending
    report = api(alice, '/tenants/' + tenant + '/reports', 'POST')
    for _ in range(30):
        result = api(alice, '/jobs/' + report['id'], tenant=tenant)
        if result['state'] == 'DONE': break
        time.sleep(.5)
    assert result['state'] == 'DONE', result
finally:
    subprocess.run(compose + ['start', 'kafka'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
for _ in range(120):
    state = api(alice, '/tenants/' + tenant + '/events');
    event = next(e for e in state['outbox'] if e['type'] == 'ProjectCreated');
    projection = next((p for p in state['projection'] if p['id'] == 'ProjectCreated'), None)
    if event['published'] and projection and projection['events'] == 1: break
    time.sleep(.5)
assert event['published'] and projection['events'] == 1, state
print(json.dumps({'result': 'PASS', 'tenant': tenant, 'api_commit_during_outage_seconds': round(commit_seconds, 3),
                  'durable_attempts_observed': pending['attempts'],
                  'report_completed_during_outage': result['state'] == 'DONE',
                  'published_after_restart': event['published'], 'projection_count': projection['events'],
                  'scope': 'local Compose broker stopped and restarted; API and PostgreSQL remain running'}, indent=2))
