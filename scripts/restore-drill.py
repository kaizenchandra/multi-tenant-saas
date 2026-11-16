#!/usr/bin/env python3
"""Local tenant-only backup/restore drill. Scratch DB names are generated, never operator supplied.
Uses a real exported PostgreSQL snapshot and runtime RLS for tenant data.
Never writes restored data into the live database. Temporary dumps are mode-0700-directory protected.
"""
import base64
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


def diagnose(kind, error, trace):
    if isinstance(error, subprocess.CalledProcessError) and error.stderr: print(
        error.stderr.decode()[:1000] if isinstance(error.stderr, bytes) else error.stderr[:1000], file=sys.stderr)
    sys.__excepthook__(kind, error, trace)


sys.excepthook = diagnose
root = Path(__file__).resolve().parents[1]
compose = ['docker', 'compose', '-f', str(root / 'docker-compose.yml'), 'exec', '-T', 'postgres']


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
            urllib.request.Request('http://localhost:8088/api/v1' + path, headers=headers, data=body, method=method),
            timeout=15) as r: return json.load(r)


def psql(sql, database='saas', role='postgres'):
    return subprocess.check_output(
        compose + ['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', role, '-d', database], input=sql, text=True,
        stderr=subprocess.PIPE)


def literal(value):
    content = json.dumps(value);
    tag = '$data_' + uuid.uuid4().hex + '$';
    assert tag not in content;
    return tag + content + tag + '::json'


alice, bob, admin = map(login, ['alice', 'bob', 'platform']);
suffix = uuid.uuid4().hex[:12]
tenants = []
for token, name in [(alice, 'restore-source-'), (bob, 'restore-neighbor-')]:
    tenant = api(token, '/tenants', 'POST', {'slug': name + suffix, 'name': name + suffix})['id'];
    api(admin, '/admin/tenants/' + tenant + '/provision', 'POST')
    project = api(token, '/projects', 'POST', {'name': name + suffix, 'status': 'OPEN'}, tenant)
    task = api(token, '/projects/' + project['id'] + '/tasks', 'POST', {'title': name + 'task', 'status': 'TODO'},
               tenant)
    tenants.append((tenant, project['id'], task['id']))
selected, neighbor = tenants[0][0], tenants[1][0]
# Restore non-empty binary export data and its published generation, not just table DDL.
api(alice, '/tenants/' + selected + '/subscription', 'POST', {'plan': 'STARTER'})
job = api(alice, '/tenants/' + selected + '/export', 'POST')['id']
for _ in range(60):
    result = api(alice, '/jobs/' + job, tenant=selected)
    if result['state'] == 'DONE': break
    time.sleep(.5)
assert result['state'] == 'DONE', result
# Include a non-empty V10 ledger in the restore fixture, not just its table definition.
subject = json.loads(base64.urlsafe_b64decode(alice.split('.')[1] + '=' * (-len(alice.split('.')[1]) % 4)))['sub'];
budget = str(uuid.uuid4())
psql(
    f"BEGIN; SELECT grant_api_budget('{selected}',({literal(subject)})#>>'{{}}',4,'{budget}',NULL,0); SELECT close_api_budget('{selected}','{budget}',3); COMMIT;",
    role='saas_runtime')
orphan = str(uuid.uuid4())
psql(
    f"BEGIN; SELECT grant_api_budget('{selected}',({literal(subject)})#>>'{{}}',4,'{orphan}',NULL,0); UPDATE api_budgets SET window_at=clock_timestamp()-interval '90 seconds' WHERE id='{orphan}'; SELECT reconcile_api_budgets('{selected}','system:restore-drill',100); COMMIT;",
    role='saas_runtime')
api(admin, '/tenants/' + selected + '/suspend', 'POST')
claims = json.loads(base64.urlsafe_b64decode(admin.split('.')[1] + '=' * (-len(admin.split('.')[1]) % 4)));
actor = claims['sub'];
correlation = str(uuid.uuid4())


def audit(action):
    psql(
        f"BEGIN; SET LOCAL app.current_tenant='{selected}'; INSERT INTO audit(tenant_id,id,actor,action,resource,correlation) SELECT '{selected}','{uuid.uuid4()}',({literal(actor)})#>>'{{}}',({literal(action)})#>>'{{}}','{selected}','{correlation}'; COMMIT;",
        role='saas_runtime')


audit('TENANT_BACKUP_REQUESTED')
scratch = 'saas_restore_' + uuid.uuid4().hex;
assert scratch.startswith('saas_restore_') and scratch != 'saas'
tables = ['projects', 'tasks', 'project_members', 'task_assignments', 'invitations', 'usage', 'audit', 'outbox',
          'inbox', 'jobs', 'feature_flags', 'idempotency', 'billing_history', 'event_projection', 'usage_storage',
          'api_budgets', 'export_chunks']
master = None;
created = False;
started = time.monotonic()
try:
    # Hold only the exporter snapshot open; tenant writes were fenced by an authenticated suspension.
    master = subprocess.Popen(
        compose + ['env', 'PGOPTIONS=-c idle_in_transaction_session_timeout=60000', 'psql', '-X', '-qAt', '-v',
                   'ON_ERROR_STOP=1', '-U', 'saas_runtime', '-d', 'saas'], stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    master.stdin.write('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT pg_export_snapshot();\n');
    master.stdin.flush();
    snapshot = master.stdout.readline().strip();
    assert snapshot and all(c in '0123456789ABCDEF-' for c in snapshot), snapshot
    prefix = f"BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET TRANSACTION SNAPSHOT '{snapshot}'; SET LOCAL app.current_tenant='{selected}'; "
    metadata = json.loads(psql(
        prefix + f"SELECT json_build_object('tenant',(SELECT row_to_json(t) FROM tenants t WHERE id='{selected}'),'members',(SELECT json_agg(m) FROM memberships m WHERE tenant_id='{selected}')); COMMIT;",
        role='saas_runtime'))
    with tempfile.TemporaryDirectory(prefix='saas-restore-') as folder:
        os.chmod(folder, 0o700)
        # Diagnostic statistics are cluster infrastructure, not tenant restore schema.
        # Never grant the owner superuser just to replay a profiling extension.
        schema = subprocess.check_output(
            compose + ['pg_dump', '-U', 'postgres', '-d', 'saas', '--schema-only', '--no-owner',
                       '--exclude-extension=pg_stat_statements'], stderr=subprocess.PIPE)
        plans = subprocess.check_output(
            compose + ['pg_dump', '-U', 'saas_runtime', '-d', 'saas', '--data-only', '--column-inserts',
                       '--table=public.plans', '--snapshot=' + snapshot], stderr=subprocess.PIPE)
        data = subprocess.check_output(
            compose + ['env', 'PGOPTIONS=-c app.current_tenant=' + selected, 'pg_dump', '-U', 'saas_runtime', '-d',
                       'saas', '--data-only', '--column-inserts', '--enable-row-security', '--snapshot=' + snapshot] + [
                '--table=public.' + t for t in tables], stderr=subprocess.PIPE)
        assert neighbor.encode() not in data, 'Neighbor UUID leaked into tenant backup'
        Path(folder, 'tenant.sql').write_bytes(data)
        source_counts = {t: int(psql(prefix + f'SELECT count(*) FROM {t}; COMMIT;', role='saas_runtime').strip()) for t
                         in tables}
        assert source_counts['export_chunks'] > 0 and source_counts['api_budgets'] > 0


        def digest(table, transaction, database='saas'):
            rows = psql(
                transaction + f"SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM {table} r; COMMIT;",
                database, 'saas_runtime')
            return hashlib.sha256(rows.encode()).hexdigest()


        source_digests = {t: digest(t, prefix) for t in tables}
        subprocess.run(compose + ['createdb', '-U', 'postgres', '-O', 'saas_owner', scratch], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE);
        created = True
        subprocess.run(compose + ['psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-U', 'saas_owner', '-d', scratch],
                       input=schema, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        subprocess.run(compose + ['psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-U', 'saas_runtime', '-d', scratch],
                       input=plans, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        psql('INSERT INTO tenants SELECT * FROM json_populate_record(NULL::tenants,' + literal(metadata[
                                                                                                   'tenant']) + '); INSERT INTO memberships SELECT * FROM json_populate_recordset(NULL::memberships,' + literal(
            metadata['members']) + ');', scratch, 'saas_runtime')
        # Counter/storage triggers must not double-account restored rows; owner disables only non-internal
        # accounting triggers on the isolated scratch database, while FORCE RLS remains enabled.
        accounting = ' '.join(f'ALTER TABLE {table} DISABLE TRIGGER {table}_storage_{operation};' for table in
                              ['projects', 'tasks', 'jobs', 'export_chunks'] for operation in
                              ['insert', 'update', 'delete'])
        psql(
            accounting + ' ALTER TABLE projects DISABLE TRIGGER projects_query_epoch; ALTER TABLE tasks DISABLE TRIGGER tasks_query_epoch;',
            scratch, 'saas_owner')
        subprocess.run(compose + ['env', 'PGOPTIONS=-c app.current_tenant=' + selected, 'psql', '-X', '-q', '-v',
                                  'ON_ERROR_STOP=1', '-U', 'saas_runtime', '-d', scratch], input=data, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        psql(accounting.replace('DISABLE',
                                'ENABLE') + ' ALTER TABLE projects ENABLE TRIGGER projects_query_epoch; ALTER TABLE tasks ENABLE TRIGGER tasks_query_epoch;',
             scratch, 'saas_owner')
        restored = {t: int(
            psql(f"BEGIN; SET LOCAL app.current_tenant='{selected}'; SELECT count(*) FROM {t}; COMMIT;", scratch,
                 'saas_runtime').strip()) for t in tables}
        assert restored == source_counts, (source_counts, restored)
        restored_digests = {t: digest(t, f"BEGIN; SET LOCAL app.current_tenant='{selected}'; ", scratch) for t in
                            tables}
        assert restored_digests == source_digests, 'Restored row content differs from consistent source snapshot'
        assert int(psql('SELECT count(*) FROM projects;', scratch, 'saas_runtime').strip()) == 0
        assert int(
            psql(f"BEGIN; SET LOCAL app.current_tenant='{neighbor}'; SELECT count(*) FROM projects; COMMIT;", scratch,
                 'saas_runtime').strip()) == 0
        assert int(psql('SELECT count(*) FROM tenants;', scratch, 'postgres').strip()) == 1
        checksum = hashlib.sha256(data).hexdigest()
    master.stdin.write('COMMIT;\n\\q\n');
    master.stdin.flush();
    master.communicate(timeout=10);
    master = None
    if created:
        subprocess.run(compose + ['dropdb', '-U', 'postgres', '--force', scratch], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE);
        created = False
    audit('TENANT_RESTORE_VERIFIED')
    api(admin, '/tenants/' + selected + '/activate', 'POST')
    print(json.dumps(
        {'result': 'PASS', 'snapshot_sha256': checksum, 'tables': restored, 'row_content_digests_match': True,
         'scratch_database_dropped': True,
         'source_neighbor_unchanged': api(bob, '/projects/' + tenants[1][1], tenant=neighbor)['id'] == tenants[1][1],
         'restore_seconds': round(time.monotonic() - started, 3),
         'scope': 'tenant-only consistent snapshot restored into a disposable local database; no live import or DR RTO claim'},
        indent=2))
finally:
    if master:
        master.stdin.close();
        master.terminate();
        master.wait(timeout=10)
    if created: subprocess.run(compose + ['dropdb', '-U', 'postgres', '--force', scratch], check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
