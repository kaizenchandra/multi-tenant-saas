#!/usr/bin/env python3
"""Local large export: bounded-memory download/inflate, checksum and ownership validation."""
import gzip
import hashlib
import json
import os
import re
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1];
base = os.environ.get('BASE_URL', 'http://localhost:30088');
tasks = int(os.environ.get('EXPORT_TASKS', '100000'));
projects = int(os.environ.get('EXPORT_PROJECTS', '2000'))
assert 10001 <= tasks <= 1000000 and 1001 <= projects <= 100000


def login(user):
    body = urllib.parse.urlencode(
        dict(client_id='saas-ui', grant_type='password', username=user, password='demo-password')).encode()
    with urllib.request.urlopen(
            urllib.request.Request('http://localhost:8188/realms/saas/protocol/openid-connect/token',
                                   data=body)) as r: return json.load(r)['access_token']


def api(token, path, method='GET', data=None, tenant=None):
    h = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
    if tenant: h['X-Tenant-ID'] = tenant
    body = json.dumps(data).encode() if data is not None else b'' if method == 'POST' else None
    with urllib.request.urlopen(urllib.request.Request(base + '/api/v1' + path, method=method, headers=h, data=body),
                                timeout=20) as r: return json.load(r)


a, admin = login('alice'), login('platform');
slug = 'large-export-' + uuid.uuid4().hex[:10];
t = api(a, '/tenants', 'POST', {'slug': slug, 'name': slug})['id'];
api(admin, '/admin/tenants/' + t + '/provision', 'POST');
api(a, '/tenants/' + t + '/subscription', 'POST', {'plan': 'ENTERPRISE'})
p = api(a, '/projects', 'POST', {'name': 'Large export anchor', 'status': 'OPEN'}, t)['id']
cmd = ['docker', 'compose', '-f', str(root / 'docker-compose.yml'), 'exec', '-T', 'postgres', 'psql', '-X', '-qAt',
       '-v', 'ON_ERROR_STOP=1', '-U', 'saas_runtime', '-d', 'saas']
seed = f"BEGIN;SELECT set_config('app.current_tenant','{t}',true);SELECT pg_advisory_xact_lock(tenant_lock_key('{t}'));INSERT INTO projects(tenant_id,id,name) SELECT '{t}',gen_random_uuid(),'Bulk-'||i FROM generate_series(1,{projects})i;INSERT INTO tasks(tenant_id,id,project_id,title) SELECT '{t}',gen_random_uuid(),'{p}',left(repeat(gen_random_uuid()::text,6),200) FROM generate_series(1,{tasks});COMMIT;"
seed_started = time.monotonic()
subprocess.run(cmd, input=seed, text=True, check=True, stdout=subprocess.DEVNULL)
seed_seconds = time.monotonic() - seed_started
started = time.monotonic();
job = api(a, '/tenants/' + t + '/export', 'POST')['id']
for _ in range(180):
    result = api(a, '/jobs/' + job, tenant=t)
    if result['state'] in ['DONE', 'FAILED']: break
    time.sleep(1)
assert result['state'] == 'DONE', result
manifest = result['result'];
assert manifest['counts']['projects'] == projects + 1 and manifest['counts']['tasks'] == tasks, manifest
build_seconds = time.monotonic() - started;
sha = hashlib.sha256();
downloaded = 0;
download_start = time.monotonic()
with tempfile.TemporaryFile() as file:
    request = urllib.request.Request(base + manifest['download'],
                                     headers={'Authorization': 'Bearer ' + a, 'X-Tenant-ID': t})
    with urllib.request.urlopen(request, timeout=30) as response:
        while block := response.read(65536): file.write(block);sha.update(block);downloaded += len(block)
    assert sha.hexdigest() == manifest['sha256'] and downloaded == manifest['compressed_bytes']
    file.seek(0);
    expanded = 0;
    owners = 0;
    carry = b''
    with gzip.GzipFile(fileobj=file, mode="rb") as stream:
        pattern = re.compile(rb'"tenant_id"\s*:\s*"([0-9a-f-]{36})"')
        while block := stream.read(65536):
            expanded += len(block);
            combined = carry + block;
            cut = max(0, len(combined) - 80)
            for match in pattern.finditer(combined):
                if match.start() < cut: assert match.group(1).decode() == t;owners += 1
            carry = combined[cut:]
        for match in pattern.finditer(carry): assert match.group(1).decode() == t;owners += 1
    assert owners == projects + 1 + tasks + 1, (owners, projects, tasks)
print(json.dumps(
    {'result': 'PASS', 'tenant': t, 'job': job, 'projects': projects + 1, 'tasks': tasks, 'chunks': manifest['chunks'],
     'compressed_bytes': downloaded, 'expanded_bytes': expanded, 'sha256': sha.hexdigest(),
     'ownership_records_checked': owners, 'seed_seconds': round(seed_seconds, 3),
     'build_seconds': round(build_seconds, 3),
     'download_and_validation_seconds': round(time.monotonic() - download_start, 3),
     'scope': 'local PostgreSQL chunk-backed consistent snapshot, bounded 64KiB client reads; gzip CRC/SHA256 and every tenant ownership marker checked'},
    indent=2))
