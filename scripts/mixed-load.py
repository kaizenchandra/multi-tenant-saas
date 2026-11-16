#!/usr/bin/env python3
"""Local-only mixed/noisy arrival-rate load; tokens never enter persisted evidence."""
import argparse
import concurrent.futures
import hashlib
import json
import os
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser();
p.add_argument('--docker', action='store_true');
p.add_argument('--profile', action='store_true');
p.add_argument('--capture', action='store_true');
p.add_argument('--database', default='saas');
p.add_argument('--fixture-base-url', default='http://localhost:8088');
p.add_argument('--warmup-rps', type=int, default=500);
p.add_argument('--warmup-mode', choices=['mixed', 'noisy']);
p.add_argument('--rps', type=int, default=10000);
p.add_argument('--duration', type=int, default=20);
p.add_argument('--tenants', type=int, default=256);
p.add_argument('--mode', choices=['mixed', 'noisy'], default='mixed');
p.add_argument('--label', required=True);
p.add_argument('--seed', action='store_true');
p.add_argument('--base-url');
p.add_argument('--warmup', type=int, default=10);
args = p.parse_args()
if not args.base_url:
    if args.docker:
        service = json.loads(subprocess.check_output(
            ['kubectl', '--context', 'orbstack', '-n', 'saas-isolation-dev', 'get', 'service', 'saas', '-o', 'json']))
        args.base_url = 'http://' + service['spec']['clusterIP'] + ':' + str(service['spec']['ports'][0]['port'])
    else:
        args.base_url = 'http://localhost:30088'
if args.docker and urllib.parse.urlparse(args.base_url).hostname in ('localhost', '127.0.0.1'):
    p.error(
        'Docker localhost is the load container; omit --base-url to discover the local Service, or provide a reachable private route')
assert args.database == 'saas' or (
        args.database.startswith('saas_perf_') and all(c.isalnum() or c == '_' for c in args.database))
assert 1 <= args.warmup_rps <= 10000
assert 1 <= args.rps <= 100000 and 1 <= args.duration <= 60 and 0 <= args.warmup <= 60 and 2 <= args.tenants <= 1000 and all(
    c.isalnum() or c in '-_' for c in args.label)
assert not (root / f'benchmarks/{args.label}-metadata.json').exists(), 'Use a new label to preserve existing evidence'


def login(user):
    data = urllib.parse.urlencode(
        dict(client_id='saas-ui', grant_type='password', username=user, password='demo-password')).encode()
    with urllib.request.urlopen(
            urllib.request.Request('http://localhost:8188/realms/saas/protocol/openid-connect/token', data=data),
            timeout=15) as r: return json.load(r)['access_token']


def call(token, path, method='GET', body=None, tenant=None, base=None):
    if base is None: base = args.fixture_base_url
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
    if tenant: headers['X-Tenant-ID'] = tenant
    data = json.dumps(body).encode() if body is not None else b'' if method == 'POST' else None
    with urllib.request.urlopen(
            urllib.request.Request(base + '/api/v1' + path, data=data, method=method, headers=headers),
            timeout=20) as r:
        return json.load(r)


tokens = [login(f'load-user-{i:02d}') for i in range(32)];
admin = login('platform')


def fixture(i):
    token = tokens[i % 32];
    slug = f'mixed-fixture-{i:04d}'
    t = call(token, '/tenants', 'POST', {'slug': slug, 'name': slug})['id']
    if args.seed: call(admin, '/admin/tenants/' + t + '/provision', 'POST');call(token,
                                                                                 '/tenants/' + t + '/subscription',
                                                                                 'POST', {'plan': 'BUSINESS'})
    rows = call(token, '/projects', tenant=t)['items']
    if not rows:
        assert args.seed
        project = call(token, '/projects', 'POST', {'name': 'Mixed benchmark project', 'status': 'OPEN'}, t)
        for j in range(2): call(token, '/projects/' + project['id'] + '/tasks', 'POST',
                                {'title': f'Task {j}', 'status': 'TODO'}, t)
    else:
        project = rows[0]
    return {'tenant': t, 'token': token, 'project': project['id'], 'name': project['name']}


with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: tenants = list(
    pool.map(fixture, range(args.tenants)))
# Re-mint after seeding, so long fixture preparation cannot leave expired test credentials.
tokens = [login(f'load-user-{i:02d}') for i in range(32)]
for i, t in enumerate(tenants): t['token'] = tokens[i % 32]
# Each cold tenant uses a different principal from the hot tenant, avoiding identity-wide sharing.
if args.mode == 'noisy': tenants = [tenants[0]] + [t for i, t in enumerate(tenants[1:], 1) if i % 32 != 0]
# Fail before allocating a load generator if the selected route is not serving our API.
probe = tenants[0]
assert call(probe['token'], '/projects/' + probe['project'], tenant=probe['tenant'], base=args.base_url)['id'] == probe[
    'project']
compose = ['docker', 'compose', '-f', str(root / 'docker-compose.yml'), 'exec', '-T', 'postgres', 'psql', '-X', '-qAt',
           '-U', 'postgres', '-d', args.database, '-c']
ids = ','.join("'" + t['tenant'] + "'" for t in tenants)


def wal(): return json.loads(subprocess.check_output(compose + [
    "select row_to_json(r) from (select wal_records,wal_bytes,wal_buffers_full,wal_write,wal_sync,wal_write_time,wal_sync_time from pg_stat_wal) r"],
                                                     text=True))


def versions(): return int(
    subprocess.check_output(compose + [f'select coalesce(sum(version),0) from projects where tenant_id in ({ids})'],
                            text=True))


def phase(label, rps, duration):
    phase_mode = args.warmup_mode if label.endswith('-warmup') and args.warmup_mode else args.mode
    credential_map = {};
    credentials = [];
    fixtures = []
    for t in tenants:
        if t['token'] not in credential_map: credential_map[t['token']] = len(credentials);credentials.append(
            t['token'])
        fixtures.append({**{k: v for k, v in t.items() if k != 'token'}, 'credential': credential_map[t['token']]})
    secret = tempfile.NamedTemporaryFile(mode='w', prefix='saas-credentials-', suffix='.json');
    os.chmod(secret.name, 0o600)
    json.dump({'tenants': fixtures, 'credentials': credentials}, secret);
    secret.flush()
    env = {**os.environ, 'TENANTS_FILE': '/secrets/tenants.json' if args.docker else secret.name,
           'BASE_URL': args.base_url, 'MODE': phase_mode, 'RPS': str(rps), 'DURATION': str(duration) + 's',
           'K6_SUMMARY_TREND_STATS': 'avg,min,med,max,p(90),p(95),p(99)'}
    capture = None
    if (args.profile or args.capture) and not label.endswith('-warmup'):
        from perf_capture import Capture
        capture = Capture(root, label, args.database, record_jfr=args.profile);
        capture.begin()
    before = versions();
    wal_before = wal();
    start = time.time()
    output_path = root / f'benchmarks/{label}-output.txt';
    peak_rss_kib = 0
    with output_path.open('w') as output:
        if args.docker:
            image = subprocess.check_output(['docker', 'image', 'inspect', 'grafana/k6:latest', '--format', '{{.Id}}'],
                                            text=True).strip()
            envfile = tempfile.NamedTemporaryFile(mode='w', prefix='saas-mixed-', suffix='.env');
            os.chmod(envfile.name, 0o600)
            envfile.write('\n'.join(k + '=' + v for k, v in env.items() if
                                    k in ['TENANTS_FILE', 'BASE_URL', 'MODE', 'RPS', 'DURATION',
                                          'K6_SUMMARY_TREND_STATS']) + '\n');
            envfile.flush()
            command = ['docker', 'run', '--rm', '--name', 'saas-load-' + label, '--cpus', '4', '--memory', '2g',
                       '--label', 'saas.benchmark=' + label, '--user', f'{os.getuid()}:{os.getgid()}', '--env-file',
                       envfile.name, '-v', str(root / 'benchmarks') + ':/results', '-v',
                       secret.name + ':/secrets/tenants.json:ro', image, 'run', '--summary-export',
                       '/results/' + label + '-summary.json', '/results/mixed.js']
        else:
            command = ['k6', 'run', '--summary-export', str(root / f'benchmarks/{label}-summary.json'),
                       str(root / 'benchmarks/mixed.js')]
        process = subprocess.Popen(command, env=env, stdout=output, stderr=subprocess.STDOUT, text=True)
        while process.poll() is None:
            try:
                peak_rss_kib = max(peak_rss_kib,
                                   int(subprocess.check_output(['ps', '-p', str(process.pid), '-o', 'rss='],
                                                               text=True).strip() or '0'))
            except (ValueError, subprocess.CalledProcessError):
                pass
            time.sleep(.25)
    secret.close()
    if args.docker: envfile.close()
    result = subprocess.CompletedProcess(process.args, process.returncode, output_path.read_text())
    if capture: capture.finish()
    after = versions();
    summary_path = root / f'benchmarks/{label}-summary.json';
    summary = json.loads(summary_path.read_text()) if summary_path.exists() else {'metrics': {}}
    successful_writes = summary['metrics'].get('write_success', {}).get('count', 0) if summary_path.exists() else None
    deployment = json.loads(subprocess.check_output(
        ['kubectl', '--context', 'orbstack', '-n', 'saas-isolation-dev', 'get', 'deployment', 'saas', '-o', 'json']))[
        'spec']
    metadata = {'database': args.database, 'fixture_base_url': args.fixture_base_url, 'telemetry': bool(capture),
                'profiled': bool(capture) and args.profile,
                'workload_sha256': hashlib.sha256((root / 'benchmarks/mixed.js').read_bytes()).hexdigest(),
                'api_image_ids': sorted({c['imageID'] for pod in json.loads(subprocess.check_output(
                    ['kubectl', '--context', 'orbstack', '-n', 'saas-isolation-dev', 'get', 'pods', '-l', 'app=saas',
                     '-o', 'json']))['items'] for c in pod.get('status', {}).get('containerStatuses', [])}),
                'mode': phase_mode, 'postgres_settings': json.loads(subprocess.check_output(compose + [
            "select json_object_agg(name,setting) from pg_settings where name in ('max_connections','fsync','synchronous_commit','wal_sync_method','commit_delay','commit_siblings','shared_buffers','wal_buffers','track_wal_io_timing','pg_stat_statements.track')"],
                                                                                            text=True)),
                'wal_before': wal_before, 'wal_after': wal(), 'target_rps': rps, 'duration_seconds': duration,
                'tenants': len(tenants), 'principals': 32, 'write_ratio': .1, 'base_url': args.base_url,
                'started_unix': start, 'load_generator': 'docker' if args.docker else 'native',
                'generator_image_id': image if args.docker else None,
                'generator_limits': {'cpus': 4, 'memory': '2GiB'} if args.docker else None,
                'observed_k6_peak_rss_kib': None if args.docker else peak_rss_kib,
                'preallocated_vus': 512 if phase_mode == 'mixed' else 640,
                'max_vus': 1024 if phase_mode == 'mixed' else 1280, 'exit_code': result.returncode,
                'successful_writes': successful_writes, 'committed_version_delta': after - before,
                'summary_available': summary_path.exists(),
                'unacknowledged_commits': after - before - successful_writes if successful_writes is not None else None,
                'write_commit_check': successful_writes <= after - before if successful_writes is not None else None,
                'replicas': deployment['replicas'],
                'container_resources': deployment['template']['spec']['containers'][0]['resources'],
                'deployment_env': {e['name']: e.get('value', 'secret-reference') for e in
                                   deployment['template']['spec']['containers'][0]['env'] if
                                   e['name'].startswith(('SAAS_ADMISSION', 'SPRING_DATASOURCE_HIKARI', 'SAAS_QUOTAS'))},
                'database_host': next((urllib.parse.urlparse(e['value'][5:]).hostname for e in
                                       deployment['template']['spec']['containers'][0]['env'] if e['name'] == 'DB_URL'),
                                      None), 'redis_host': next(
            (e['value'] for e in deployment['template']['spec']['containers'][0]['env'] if e['name'] == 'REDIS_HOST'),
            None),
                'fixture': 'one project/two tasks per tenant; fixed-name PATCH, detail and aggregate list; 100/s tenant rate policy unchanged',
                'k6_version': subprocess.check_output(
                    ['docker', 'run', '--rm', image, 'version'] if args.docker else ['k6', 'version'],
                    text=True).strip()}
    (root / f'benchmarks/{label}-metadata.json').write_text(json.dumps(metadata, indent=2) + '\n');
    (root / f'benchmarks/{label}-output.txt').write_text(result.stdout);
    print(result.stdout);
    print('k6 exit', result.returncode, 'write commit check', metadata['write_commit_check'], flush=True)
    return metadata


if args.warmup: phase(args.label + '-warmup', min(args.rps, args.warmup_rps), args.warmup)
result = phase(args.label, args.rps, args.duration)
raise SystemExit(0 if result['exit_code'] == 0 else 1)
