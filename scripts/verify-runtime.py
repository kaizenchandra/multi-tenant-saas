#!/usr/bin/env python3
"""Read-only evidence for this local Compose/OrbStack deployment. No credentials saved."""
import argparse
import collections
import hashlib
import json
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser();
parser.add_argument('--label', required=True);
args = parser.parse_args()
assert all(c.isalnum() or c in '-_' for c in args.label)
destination = root / 'benchmarks' / f'{args.label}-runtime.json'
assert not destination.exists(), 'Use a new label to preserve evidence'
kube = ['kubectl', '--context', 'orbstack', '-n', 'saas-isolation-dev']
compose = ['docker', 'compose', '-f', str(root / 'docker-compose.yml')]


def output(command): return subprocess.check_output(command, text=True, timeout=20).strip()


def sql(query, role='postgres'):
    return output(
        compose + ['exec', '-T', 'postgres', 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', role, '-d', 'saas',
                   '-c', query])


jarhash = hashlib.sha256((root / 'target/multi-tenant-saas-1.0.0.jar').read_bytes()).hexdigest()
reports = [ET.parse(p).getroot().attrib for p in (root / 'target/surefire-reports').glob('TEST-*.xml')]
tests = {k: sum(int(r[k]) for r in reports) for k in ('tests', 'failures', 'errors', 'skipped')}
assert tests['tests'] > 0 and all(
    tests[k] == 0 for k in ('failures', 'errors', 'skipped')), 'Java report contains failed/skipped tests'
deployment = json.loads(output(kube + ['get', 'deployment', 'saas', '-o', 'json']))
container = deployment['spec']['template']['spec']['containers'][0]
pods = json.loads(output(kube + ['get', 'pods', '-l', 'app=saas', '-o', 'json']))['items']
assert len(pods) == 2 and deployment['status'].get('readyReplicas') == 2, 'Two fully drained ready replicas required'
runtime_pods = []
for pod in pods:
    name = pod['metadata']['name'];
    actual = output(kube + ['exec', name, '--', 'sha256sum', '/app/app.jar']).split()[0]
    assert actual == jarhash, 'API JAR differs from local verified JAR'
    runtime_pods.append({'name': name, 'image_ids': [c['imageID'] for c in pod['status']['containerStatuses']],
                         'jar_sha256': actual, 'ready': all(c['ready'] for c in pod['status']['containerStatuses']),
                         'cpu_cgroup': output(kube + ['exec', name, '--', 'sh', '-c',
                                                      'cat /sys/fs/cgroup/cpu.weight /sys/fs/cgroup/cpu.max'])})
compose_hash = output(compose + ['exec', '-T', 'app', 'sha256sum', '/app/app.jar']).split()[0]
assert compose_hash == jarhash, 'Compose JAR differs from local verified JAR'
role = json.loads(
    sql("select json_build_object('role',current_user,'superuser',rolsuper,'bypassrls',rolbypassrls) from pg_roles where rolname=current_user",
        'saas_runtime'))
assert not role['superuser'] and not role['bypassrls'], 'Unsafe runtime role'
tables = json.loads(
    sql("select json_agg(json_build_object('table',c.relname,'forced',c.relforcerowsecurity,'owner',pg_get_userbyid(c.relowner),'runtime_inherits_owner',pg_has_role('saas_runtime'::regrole,c.relowner,'MEMBER'))) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relkind='r' and c.relrowsecurity"))
assert len(tables) == 17 and all(
    t['forced'] and not t['runtime_inherits_owner'] and t['owner'] != 'saas_runtime' for t in
    tables), 'Incomplete forced RLS'
policies = json.loads(
    sql("select json_agg(row_to_json(p)) from (select c.relname,polcmd::text,pg_get_expr(polqual,polrelid) as using_expr,pg_get_expr(polwithcheck,polrelid) as check_expr from pg_policy join pg_class c on c.oid=polrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relrowsecurity)p"))
expected = "(tenant_id = (NULLIF(current_setting('app.current_tenant'::text, true), ''::text))::uuid)"
assert len(policies) == 17 and len({p['relname'] for p in policies}) == 17
assert all(
    p['polcmd'] == '*' and p['using_expr'] == p['check_expr'] == expected for p in policies), 'Unexpected tenant policy'
assert int(sql('select count(*) from projects', 'saas_runtime')) == 0, 'Missing context exposed rows'
version = int(sql('select max(version::int) from flyway_schema_history where success'))
assert version == 17, 'Unexpected migration version'
settings = json.loads(
    sql("select json_object_agg(name,setting) from pg_settings where name in ('fsync','synchronous_commit','wal_sync_method','commit_delay','pg_stat_statements.track')"))
assert settings['fsync'] == 'on' and settings['synchronous_commit'] == 'on', 'Durable WAL disabled'
assert int(
    sql("select count(*) from pg_database where datname like 'saas_perf_run_%' or datname like 'saas_restore_%'")) == 0, 'Trial/restore database remains'
allowed = ('DB_URL', 'REDIS_HOST', 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE', 'SAAS_ADMISSION_GLOBAL_CAPACITY',
           'SAAS_QUOTAS_LEASED_REQUESTS')
configuration = {e['name']: e.get('value') for e in container['env'] if e['name'] in allowed}
assert configuration['DB_URL'].endswith('/saas') and configuration[
    'REDIS_HOST'] == 'saas-redis', 'Live routing not restored'
restored = [];
window = root / 'benchmarks/isolation-window.json'
if window.exists():
    state = json.loads(window.read_text());
    assert state['window_closed'], 'Pause window not closed'
    for old in state['paused']:
        result = subprocess.run(['docker', 'inspect', '--format', '{{json .State}}', old['id']], capture_output=True,
                                text=True)
        if result.returncode:
            assert any(s in result.stderr.lower() for s in
                       ('no such object', 'no such container')), 'Docker state unverified: ' + result.stderr
            status = 'removed'
        else:
            current = json.loads(result.stdout);
            assert not current['Paused'], 'Recorded container remains paused: ' + old['name']
            status = 'running' if current['Running'] else 'stopped'
        restored.append({'id': old['id'], 'name': old['name'], 'status': status})
    snapshot = {'checked_unix': time.time(), 'counts': dict(collections.Counter(r['status'] for r in restored)),
                'containers': restored}
    previous = root / 'benchmarks/isolation-final-state.json';
    backup = root / 'benchmarks/isolation-final-state-before-release.json'
    if previous.exists() and not backup.exists(): backup.write_bytes(previous.read_bytes())
    previous.write_text(json.dumps(snapshot, indent=2) + '\n')
evidence = {'captured_unix': time.time(), 'java_tests': tests, 'jar_sha256': jarhash,
            'compose_jar_sha256': compose_hash,
            'api_pods': runtime_pods, 'configuration': configuration, 'resources': container['resources'],
            'runtime_role': role,
            'protected_tables': tables, 'policies': policies, 'schema_version': version,
            'missing_context_project_count': 0,
            'postgres_settings': settings, 'benchmark_clones_remaining': 0,
            'paused_window_final_counts': dict(collections.Counter(r['status'] for r in restored)),
            'scope': 'Local Compose/single-node OrbStack only. No capacity/SLO/certification claim.'}
destination.write_text(json.dumps(evidence, indent=2) + '\n')
print(json.dumps({'result': 'PASS', 'file': str(destination), 'java_tests': tests,
                  'paused_window_final_counts': evidence['paused_window_final_counts']}, indent=2))
