#!/usr/bin/env python3
"""Reproducible bounded k6 workload; credentials stay in a temporary 0600 file."""
import argparse
import json
import os
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser();
p.add_argument('--native', action='store_true');
p.add_argument('--tenants', type=int, default=32);
p.add_argument('--seed', action='store_true');
p.add_argument('--duration', type=int, default=20);
p.add_argument('--rps', type=int, default=1000);
p.add_argument('--label', default='optimized-1000rps');
p.add_argument('--warmup-seconds', type=int, default=0);
p.add_argument('--base-url', default='http://host.docker.internal:30088');
args = p.parse_args()
if args.native: args.base_url = args.base_url.replace('host.docker.internal', 'localhost')
assert 1 <= args.tenants <= 1000 and 1 <= args.duration <= 60 and 1 <= args.rps <= 100000
assert all(c.isalnum() or c in '-_' for c in args.label)


def login(user):
    body = urllib.parse.urlencode(
        dict(client_id='saas-ui', grant_type='password', username=user, password='demo-password')).encode()
    with urllib.request.urlopen(
            urllib.request.Request('http://localhost:8188/realms/saas/protocol/openid-connect/token',
                                   data=body)) as response: return json.load(response)['access_token']


def api(token, path, method='GET', data=None, tenant=None):
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
    if tenant: headers['X-Tenant-ID'] = tenant
    body = json.dumps(data).encode() if data is not None else b'' if method == 'POST' else None
    with urllib.request.urlopen(
            urllib.request.Request('http://localhost:8088/api/v1' + path, headers=headers, method=method, data=body),
            timeout=15) as response: return json.load(response)


alice, bob = login('alice'), login('bob');
tenants = []
if args.seed:
    admin = login('platform')
    for i in range(args.tenants):
        token = alice if i % 2 == 0 else bob
        t = api(token, '/tenants', 'POST', {'slug': f'load-fixture-{i:04d}', 'name': f'Load fixture {i:04d}'})['id'];
        api(admin, '/admin/tenants/' + t + '/provision', 'POST')
        api(token, '/tenants/' + t + '/subscription', 'POST', {'plan': 'BUSINESS'})
        projects = api(token, '/projects', tenant=t)['items']
        if not projects:
            project = api(token, '/projects', 'POST', {'name': 'Benchmark project', 'status': 'OPEN'}, t)
            for j in range(2): api(token, '/projects/' + project['id'] + '/tasks', 'POST',
                                   {'title': f'Task {j}', 'status': 'TODO'}, t)
        tenants.append({'tenant': t, 'token': token})
else:
    tenants = [{'tenant': t['id'], 'token': alice} for t in api(alice, '/me/tenants') if t['state'] == 'ACTIVE'][
        :args.tenants]
assert len(tenants) == args.tenants, 'Insufficient active tenants; use --seed or reduce --tenants'
if args.native:
    binary = shutil.which('k6');
    assert binary, 'Native k6 is not installed'
    image = None;
    version = subprocess.check_output([binary, 'version'], text=True).strip()
else:
    image = subprocess.check_output(['docker', 'image', 'inspect', 'grafana/k6:latest', '--format', '{{.Id}}'],
                                    text=True).strip()
    version = subprocess.check_output(['docker', 'run', '--rm', image, 'version'], text=True).strip()
# Warm authentication/JWK and database paths without consuming a large fraction of the rate window.
warm_base = args.base_url.replace('host.docker.internal', 'localhost')
warm_errors = []
for item in tenants:
    for _ in range(3):
        for attempt in range(4):
            try:
                with urllib.request.urlopen(urllib.request.Request(warm_base + '/api/v1/projects',
                                                                   headers={'Authorization': 'Bearer ' + item['token'],
                                                                            'X-Tenant-ID': item['tenant']}),
                                            timeout=15) as response:
                    json.load(response)
                break
            except urllib.error.HTTPError:
                raise
            except (urllib.error.URLError, ConnectionError, TimeoutError) as error:
                warm_errors.append(type(error).__name__)
                if attempt == 3: raise
                time.sleep(.5)

time.sleep(1)


def run_phase(label, rate, duration):
    with tempfile.NamedTemporaryFile(mode='w', prefix='saas-load-', suffix='.env') as env:
        os.chmod(env.name, 0o600)
        env.write('TENANTS=' + json.dumps(
            tenants) + f'\nRPS={rate}\nDURATION={duration}s\nBASE_URL={args.base_url}\nK6_SUMMARY_TREND_STATS=avg,min,med,max,p(90),p(95),p(99)\n');
        env.flush()
        if args.native:
            overrides = dict(line.split('=', 1) for line in Path(env.name).read_text().splitlines())
            result = subprocess.run([binary, 'run', '--summary-export', str(root / f'benchmarks/{label}-summary.json'),
                                     str(root / 'benchmarks/load.js')], env={**os.environ, **overrides},
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        else:
            result = subprocess.run(['docker', 'run', '--rm', '--label', 'saas.benchmark=' + label, '--user',
                                     f'{os.getuid()}:{os.getgid()}', '--env-file', env.name, '-v',
                                     str(root / 'benchmarks') + ':/results', image, 'run', '--summary-export',
                                     f'/results/{label}-summary.json', '/results/load.js'], stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True)

        (root / f'benchmarks/{label}-output.txt').write_text(result.stdout)
        (root / f'benchmarks/{label}-metadata.json').write_text(json.dumps(
            {'base_url': args.base_url, 'rate_target': rate, 'duration_seconds': duration, 'tenant_count': len(tenants),
             'tenant_ids': [t['tenant'] for t in tenants], 'seeded': args.seed,
             'fixture': 'one project and two tasks per seeded tenant',
             'load_generator': 'native' if args.native else 'docker', 'image_id': image, 'k6_version': version,
             'exit_code': result.returncode, 'rate_policy_per_tenant': 100, 'pods': 2, 'pool_connections_per_pod': 16,
             'admission_per_pod': 14, 'leased_read_budgets': True, 'budget_grant_max': 4, 'admission_wait_ms': 100,
             'cpu_limit_per_pod': 2, 'preallocated_vus': 256, 'max_vus': 1000, 'warmup_seconds': args.warmup_seconds,
             'warmup_network_errors': warm_errors,
             'scope': 'local single-node Kubernetes; project listing with joins; global iteration round robin; no foreign-ID probes'},
            indent=2) + '\n')
        return result


assert 0 <= args.warmup_seconds <= 60
if args.warmup_seconds:
    warm = run_phase(args.label + '-warmup', min(500, args.rps), args.warmup_seconds)
    print('Warm-up exit:', warm.returncode, '(raw artifacts preserved separately)')
result = run_phase(args.label, args.rps, args.duration)
print(result.stdout);
print('k6 exit:', result.returncode)
