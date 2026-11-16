#!/usr/bin/env python3
"""Local synthetic-only DB clones; restore original Deployment/Compose API in finally.

Run prepare once, then comparisons with immutable image tags. Never copies or deletes
the live saas database. Foreground measurements deliberately pause the local worker;
they are not end-to-end broker throughput evidence.
"""
import argparse
import hashlib
import json
import os
import subprocess
import time
import urllib.request
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--prepare', action='store_true')
parser.add_argument('--image', default='multi-tenant-saas-app:latest')
parser.add_argument('--label', default='controlled')
parser.add_argument('--mode', choices=['mixed', 'noisy'], default='mixed')
parser.add_argument('--profile', action='store_true')
parser.add_argument('--rps', type=int, default=10000)
parser.add_argument('--repeats', type=int, default=1)
parser.add_argument('--api-cpus', type=int, choices=[2, 3, 4])
parser.add_argument('--pool-size', type=int, choices=[30, 48])
parser.add_argument('--commit-delay', type=int, choices=[0, 1000, 3000, 5000])
parser.add_argument('--duration', type=int, default=30)
parser.add_argument('--sql-tracking', choices=['all', 'top', 'none'], default='all')
args = parser.parse_args()
assert all(c.isalnum() or c == '-' for c in args.label)
assert 1 <= args.repeats <= 3
assert 1 <= args.duration <= 60
compose = ['docker', 'compose', '-f', str(root / 'docker-compose.yml')]
kube = ['kubectl', '--context', 'orbstack', '-n', 'saas-isolation-dev']
seed = 'saas_perf_seed'


def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def sql(query, database='saas', role='postgres'):
    assert database == 'saas' or database == seed or database.startswith('saas_perf_run_')
    return subprocess.check_output(compose + ['exec', '-T', 'postgres', 'psql', '-X', '-qAt',
                                              '-v', 'ON_ERROR_STOP=1', '-U', role, '-d', database], input=query,
                                   text=True)


def ready(url):
    for _ in range(60):
        try:
            path = '/ui-config' if url.endswith(':30088') else '/actuator/health/readiness'
            with urllib.request.urlopen(url + path, timeout=2) as response:
                if response.status == 200: return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('API did not become ready')


def drain():
    run(kube + ['rollout', 'status', 'deployment/saas', '--timeout=120s'])
    for _ in range(60):
        pods = json.loads(subprocess.check_output(kube + ['get', 'pods', '-l', 'app=saas', '-o', 'json']))['items']
        if len(pods) == 2 and not any(p['metadata'].get('deletionTimestamp') for p in pods): return
        time.sleep(1)
    raise RuntimeError('Old pods remain: no benchmark started')


if args.prepare:
    assert sql(
        "select count(*) from pg_database where datname='saas_perf_seed';").strip() == '0', 'Seed already exists; do not replace silently'
    sql('CREATE DATABASE saas_perf_seed OWNER saas_owner;')
    try:
        network = json.loads(subprocess.check_output(
            ['docker', 'inspect', subprocess.check_output(compose + ['ps', '-q', 'postgres'], text=True).strip()]))[0][
            'NetworkSettings']['Networks']
        assert len(network) == 1
        run(['docker', 'run', '-d', '--name', 'saas-perf-seeder', '--network', next(iter(network)),
             '--memory', '512m', '--cpus', '1', '-p', '127.0.0.1:18089:8080',
             '-e', 'DB_URL=jdbc:postgresql://postgres:5432/' + seed,
             '-e', 'REDIS_HOST=redis', '-e', 'REDIS_PORT=6379',
             '-e', 'WORKERS_ENABLED=false', '-e', 'MESSAGING_ENABLED=false',
             '-e', 'OIDC_JWK_URI=http://keycloak:8080/realms/saas/protocol/openid-connect/certs', args.image])
        ready('http://localhost:18089')
        users = {u['username']: str(uuid.UUID(u['id'])) for u in
                 json.loads((root / 'docker/realm.json').read_text())['users']}
        statements = []
        for i in range(256):
            tenant, project = uuid.uuid4(), uuid.uuid4();
            actor = users[f'load-user-{i % 32:02d}'];
            slug = f'mixed-fixture-{i:04d}'
            statements.append(f"""BEGIN; SELECT set_config('app.current_tenant','{tenant}',true);
             SELECT pg_advisory_xact_lock(tenant_lock_key('{tenant}'));
             INSERT INTO tenants(id,slug,name,owner_subject,state,plan,subscription)
              VALUES('{tenant}','{slug}','{slug}','{actor}','ACTIVE','BUSINESS','ACTIVE');
             INSERT INTO memberships VALUES('{tenant}','{actor}','TENANT_OWNER',true);
             INSERT INTO usage(tenant_id) VALUES('{tenant}');
             INSERT INTO projects(tenant_id,id,name) VALUES('{tenant}','{project}','Mixed benchmark project');
             INSERT INTO tasks(tenant_id,id,project_id,title) SELECT '{tenant}',gen_random_uuid(),'{project}','Task '||j FROM generate_series(0,1)j;
             COMMIT;""")
        sql('\n'.join(statements), seed, 'saas_runtime')
        sql('CREATE EXTENSION pg_stat_statements; ANALYZE;', seed)
    finally:
        run(['docker', 'rm', '-f', 'saas-perf-seeder'])
    schema = sql("SELECT md5(string_agg(slug||id::text||owner_subject,',' ORDER BY slug)) FROM tenants;", seed).strip()
    (root / 'benchmarks/repeatable-seed.json').write_text(json.dumps(
        {'database': seed, 'tenants': 256, 'projects': 256, 'tasks': 512, 'fixture_digest': schema,
         'synthetic_only': True}, indent=2) + '\n')
    print('Synthetic seed ready');
    raise SystemExit(0)

original = json.loads(subprocess.check_output(kube + ['get', 'deployment', 'saas', '-o', 'json']))
container = original['spec']['template']['spec']['containers'][0]
database = 'saas_perf_run_' + uuid.uuid4().hex
created = False
original_app = bool(subprocess.check_output(compose + ['ps', '--status', 'running', '-q', 'app'], text=True).strip())
record = {'label': args.label, 'mode': args.mode, 'database': database, 'image': args.image,
          'foreground_only': True, 'api_cpu_override': args.api_cpus, 'pool_size_override': args.pool_size,
          'commit_delay_override': args.commit_delay,
          'sql_tracking': args.sql_tracking,
          'seed': json.loads((root / 'benchmarks/repeatable-seed.json').read_text())}
try:
    existing = subprocess.run(['docker', 'inspect', 'saas-perf-redis'], capture_output=True, text=True)
    if existing.returncode:
        original_pg = json.loads(subprocess.check_output(
            ['docker', 'inspect', subprocess.check_output(compose + ['ps', '-q', 'postgres'], text=True).strip()]))[0]
        network = next(iter(original_pg['NetworkSettings']['Networks']))
        run(['docker', 'run', '-d', '--name', 'saas-perf-redis', '--label', 'saas.performance.fixture=true',
             '--network', network, '--memory', '256m', '--cpus', '0.5', 'redis:7.4.6-alpine',
             'redis-server', '--maxmemory', '128mb', '--maxmemory-policy', 'allkeys-lru'])
    cache = json.loads(subprocess.check_output(['docker', 'inspect', 'saas-perf-redis']))[0]
    assert cache['Config']['Labels'].get('saas.performance.fixture') == 'true'
    address = next(iter(cache['NetworkSettings']['Networks'].values()))['IPAddress']
    objects = [{'apiVersion': 'v1', 'kind': 'Service',
                'metadata': {'name': 'saas-perf-redis', 'namespace': 'saas-isolation-dev'},
                'spec': {'ports': [{'name': 'tcp', 'port': 6379}]}},
               {'apiVersion': 'discovery.k8s.io/v1', 'kind': 'EndpointSlice',
                'metadata': {'name': 'saas-perf-redis', 'namespace': 'saas-isolation-dev',
                             'labels': {'kubernetes.io/service-name': 'saas-perf-redis',
                                        'endpointslice.kubernetes.io/managed-by': 'saas-perf'}}, 'addressType': 'IPv4',
                'ports': [{'name': 'tcp', 'protocol': 'TCP', 'port': 6379}],
                'endpoints': [{'addresses': [address], 'conditions': {'ready': True}}]}]
    run(kube + ['apply', '-f', '-'], input=json.dumps({'apiVersion': 'v1', 'kind': 'List', 'items': objects}),
        text=True)
    run(['docker', 'exec', 'saas-perf-redis', 'redis-cli', 'FLUSHDB'])
    if original_app: run(compose + ['stop', '--timeout', '20', 'app'])
    sql('CREATE DATABASE ' + database + ' TEMPLATE saas_perf_seed OWNER saas_owner;');
    created = True
    sql("ALTER DATABASE " + database + " SET pg_stat_statements.track='" + args.sql_tracking + "';")
    if args.commit_delay is not None:
        sql('ALTER DATABASE ' + database + ' SET commit_delay=' + str(args.commit_delay) + ';')
    patch = {'spec': {'template': {'spec': {'containers': [{'name': 'app', 'image': args.image,
                                                            'env': [{'name': 'DB_URL',
                                                                     'value': 'jdbc:postgresql://saas-postgres:5432/' + database},
                                                                    {'name': 'REDIS_HOST',
                                                                     'value': 'saas-perf-redis'}]}]}}}}
    selected = patch['spec']['template']['spec']['containers'][0]
    if args.api_cpus:
        selected['resources'] = json.loads(json.dumps(container['resources']))
        selected['resources']['limits']['cpu'] = str(args.api_cpus)
    if args.pool_size:
        selected['env'] += [{'name': 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE', 'value': str(args.pool_size)},
                            {'name': 'SAAS_ADMISSION_GLOBAL_CAPACITY', 'value': str(args.pool_size - 2)}]
    run(kube + ['patch', 'deployment', 'saas', '--type=strategic', '-p', json.dumps(patch)])
    drain();
    ready('http://localhost:30088')
    # Keep initial clone/FPI and planner conditions consistent. Warmup populates buffers/JIT.
    sql('CHECKPOINT;', database)
    record['phases'] = []
    for repeat in range(args.repeats):
        label = args.label if args.repeats == 1 else args.label + '-r' + str(repeat + 1)
        command = ['python3', str(root / 'scripts/mixed-load.py'), '--docker', '--capture', '--database', database,
                   '--fixture-base-url', 'http://localhost:30088', '--warmup', '60' if repeat == 0 else '0',
                   '--warmup-rps', '1000', '--warmup-mode', 'mixed', '--duration', str(args.duration), '--rps',
                   str(args.rps), '--mode', args.mode, '--label', label]
        if args.profile: command.append('--profile')
        started = time.time();
        result = subprocess.run(command, cwd=root)
        record['phases'].append({'label': label, 'started_unix': started, 'exit_code': result.returncode})
    record['exit_code'] = max(r['exit_code'] for r in record['phases'])
    record['final_fixture_counts'] = json.loads(
        sql("select json_build_object('tenants',(select count(*) from tenants),'projects',(select count(*) from projects),'tasks',(select count(*) from tasks));",
            database))
except Exception as error:
    record['error'] = type(error).__name__ + ': ' + str(error)
    record['exit_code'] = 125
    raise
finally:
    restore = {'spec': {'template': {'spec': {'containers': [{'name': 'app', 'image': container['image'],
                                                              'resources': container['resources'],
                                                              'env': [e for e in container['env'] if
                                                                      e['name'] in ('DB_URL', 'REDIS_HOST',
                                                                                    'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE',
                                                                                    'SAAS_ADMISSION_GLOBAL_CAPACITY')]}]}}}}
    run(kube + ['patch', 'deployment', 'saas', '--type=strategic', '-p', json.dumps(restore)])
    drain()
    if original_app: run(compose + ['start', 'app'])
    if created: sql('DROP DATABASE ' + database + ';')
    record['live_deployment_restored'] = True
    (root / 'benchmarks' / f'{args.label}-conditions.json').write_text(json.dumps(record, indent=2) + '\n')
raise SystemExit(record['exit_code'])
