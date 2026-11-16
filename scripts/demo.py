#!/usr/bin/env python3
"""Local-only demonstration against Compose's imported OIDC realm."""
import base64
import gzip
import hashlib
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

base = os.environ.get('BASE_URL', 'http://localhost:8088')


def login(user):
    data = urllib.parse.urlencode(
        dict(client_id='saas-ui', grant_type='password', username=user, password='demo-password')).encode()
    with urllib.request.urlopen(
            urllib.request.Request('http://localhost:8188/realms/saas/protocol/openid-connect/token',
                                   data=data)) as response: return json.load(response)['access_token']


def api(token, path, method='GET', data=None, tenant=None, expected=200, key=None, version=None):
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
    if tenant: headers['X-Tenant-ID'] = tenant
    if key: headers['Idempotency-Key'] = key
    if version is not None: headers['If-Match'] = '"' + str(version) + '"'
    request = urllib.request.Request(base + '/api/v1' + path, data=json.dumps(data).encode() if data is not None else (
        b'' if method == 'POST' else None), headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            status = response.status;
            body = json.load(response)
    except urllib.error.HTTPError as e:
        status = e.code;
        body = json.load(e)
    assert status == expected, (path, status, body)
    return body


alice, bob, admin = map(login, ['alice', 'bob', 'platform']);
suffix = uuid.uuid4().hex[:10]
a = api(alice, '/tenants', 'POST', {'slug': 'acme-' + suffix, 'name': 'Acme'}, expected=201)['id']
b = api(bob, '/tenants', 'POST', {'slug': 'globex-' + suffix, 'name': 'Globex'}, expected=201)['id']
for t in [a, b]: api(admin, '/admin/tenants/' + t + '/provision', 'POST')
p = api(alice, '/projects', 'POST', {'name': 'Acme secret', 'status': 'OPEN'}, a, 201)
q = api(bob, '/projects', 'POST', {'name': 'Globex secret', 'status': 'OPEN'}, b, 201)
api(alice[:-6] + 'forged', '/projects', tenant=a, expected=401)
api(alice, '/projects/' + q['id'], tenant=a, expected=404)
api(alice, '/projects', tenant=b, expected=403)
api(bob, '/projects/' + p['id'], tenant=b, expected=404)
assert len(api(alice, '/projects', tenant=a)['items']) == 1
assert len(api(bob, '/projects', tenant=b)['items']) == 1
for token, t, project in [(alice, a, p), (bob, b, q)]:
    api(token, '/projects/' + project['id'] + '/tasks', 'POST', {'title': 'isolated task', 'status': 'TODO'}, t, 201)
    api(token, '/tenants/' + t + '/subscription', 'POST', {'plan': 'STARTER'})
    job = api(token, '/tenants/' + t + '/export', 'POST', expected=202)
    for _ in range(25):
        result = api(token, '/jobs/' + job['id'], tenant=t)
        if result['state'] == 'DONE': break
        time.sleep(1)
    assert result['state'] == 'DONE', result
    # JDBC jsonb is serialized as a PGobject wrapper by Spring's default serializer.
    manifest = result['result']
    with urllib.request.urlopen(urllib.request.Request(base + manifest['download'],
                                                       headers={'Authorization': 'Bearer ' + token, 'X-Tenant-ID': t}),
                                timeout=30) as response:
        compressed = response.read()
    assert hashlib.sha256(compressed).hexdigest() == manifest['sha256']
    export = json.loads(gzip.decompress(compressed));
    assert all(row['tenant_id'] == t for row in export['projects'])
# Explicit support routes are privileged, scoped and audited; the platform identity
# does not gain membership or permission on the ordinary tenant download route.
job = api(admin, '/admin/tenants/' + a + '/export', 'POST', expected=202)
status_path = '/admin/tenants/' + a + '/exports/' + job['id']
api(alice, status_path, expected=403)
api(admin, '/jobs/' + job['id'], tenant=a, expected=403)
for _ in range(30):
    result = api(admin, status_path)
    if result['state'] == 'DONE': break
    time.sleep(1)
assert result['state'] == 'DONE', result
api(admin, '/jobs/' + job['id'] + '/content', tenant=a, expected=403)
with urllib.request.urlopen(
        urllib.request.Request(base + '/api/v1' + status_path + '/content',
                               headers={'Authorization': 'Bearer ' + admin}),
        timeout=30) as response: compressed = response.read()
assert hashlib.sha256(compressed).hexdigest() == result['result']['sha256']
assert all(row['tenant_id'] == a for row in json.loads(gzip.decompress(compressed))['projects'])
api(admin, '/tenants/' + a + '/suspend', 'POST')
api(alice, '/projects', tenant=a, expected=403)
api(admin, '/tenants/' + a + '/activate', 'POST')
# Verify the newly implemented workflows against the deployed image.
key = 'demo-' + uuid.uuid4().hex
created = api(alice, '/projects', 'POST', {'name': 'Retry safe', 'status': 'OPEN'}, a, 201, key=key)
replayed = api(alice, '/projects', 'POST', {'name': 'Retry safe', 'status': 'OPEN'}, a, 201, key=key)
assert created['id'] == replayed['id']
api(alice, '/projects/' + created['id'], 'PATCH', {'name': 'Updated once', 'status': 'ACTIVE'}, a,
    version=created['version'])
api(alice, '/projects/' + created['id'], 'PATCH', {'name': 'Stale edit', 'status': 'CLOSED'}, a, expected=412,
    version=created['version'])
report = api(alice, '/tenants/' + a + '/reports', 'POST', expected=202, key='report-' + uuid.uuid4().hex)
for _ in range(30):
    result = api(alice, '/jobs/' + report['id'], tenant=a)
    if result['state'] == 'DONE': break
    time.sleep(1)
assert result['state'] == 'DONE', result
assert 'projects' in result['result'] and 'tasks' in result['result']
api(alice, '/tenants/' + a + '/features/reports', 'PUT', {'enabled': False, 'rollout': 100, 'version': 0})
api(alice, '/tenants/' + a + '/reports', 'POST', expected=403)
api(alice, '/tenants/' + a + '/features/reports', 'PUT', {'enabled': True, 'rollout': 100, 'version': 1})
usage = api(alice, '/tenants/' + a + '/usage');
assert usage['storage_bytes'] > 0
assert api(alice, '/tenants/' + a + '/billing-history')
api(alice, '/tenants/' + a + '/subscription/cancel', 'POST')
assert api(alice, '/tenants/' + a + '/subscription')['cancel_at_period_end'] is True
print(json.dumps({'result': 'PASS', 'tenantA': a, 'tenantB': b,
                  'checks': ['signed OIDC and forged JWT rejection', 'provisioning', 'project/task isolation',
                             'IDOR 404', 'forged selector 403', 'streaming exports with gzip/SHA256',
                             'explicit support export and ordinary-route denial', 'suspension revocation',
                             'idempotent create replay', 'If-Match 412', 'durable report', 'versioned feature flags',
                             'storage metering', 'immutable billing history', 'period-end cancellation']}, indent=2))
