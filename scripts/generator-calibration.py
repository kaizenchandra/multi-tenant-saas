#!/usr/bin/env python3
"""Transport-only generator calibration. Fake auth/data, NO SaaS capacity proof.

Uses the same mixed.js, client image/resources, headers and 256 selector pattern.
The private Docker echo route omits the Kubernetes Service, JWT, DB and all policy.
Never runs against the SaaS API or changes the existing services.
"""
import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import time
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--label', required=True)
parser.add_argument('--rps', type=int, default=10000)
parser.add_argument('--duration', type=int, default=20)
args = parser.parse_args()
assert all(c.isalnum() or c == '-' for c in args.label)
assert 1 <= args.rps <= 100000 and 1 <= args.duration <= 60
assert not (root / 'benchmarks' / f'{args.label}-summary.json').exists()
image = subprocess.check_output(['docker', 'image', 'inspect', 'grafana/k6:latest', '--format', '{{.Id}}'],
                                text=True).strip()
server_image = subprocess.check_output(['docker', 'image', 'inspect', 'nginx:1.28-alpine', '--format', '{{.Id}}'],
                                       text=True).strip()
server = 'saas-echo-' + args.label
created = False
with tempfile.TemporaryDirectory(prefix='saas-calibration-') as directory:
    temporary = Path(directory)
    config = temporary / 'nginx.conf'
    config.write_text('''worker_processes 1;
events { worker_connections 4096; }
http { access_log off; server { listen 8080;
 default_type application/json;
 location ~ /api/v1/projects/([a-f0-9-]+)$ { return 200 '{"id":"$1"}'; }
 location / { return 200 '{"items":[]}'; }
} }
''')
    fixtures = temporary / 'fixtures.json'
    fixtures.write_text(json.dumps({'tenants': [
        {'tenant': str(uuid.uuid4()), 'project': str(uuid.uuid4()), 'name': 'Mixed benchmark project',
         'credential': i % 32} for i in range(256)],
        'credentials': ['fake.' + str(i) + '.' + 'x' * 1600 for i in range(32)]}))
    fixtures.chmod(0o600)
    try:
        subprocess.run(['docker', 'run', '-d', '--name', server, '--cpus', '1', '--memory', '128m',
                        '--label', 'saas.performance.fixture=true', '-v', str(config) + ':/etc/nginx/nginx.conf:ro',
                        server_image], check=True, stdout=subprocess.DEVNULL)
        created = True
        info = json.loads(subprocess.check_output(['docker', 'inspect', server]))[0]
        address = next(iter(info['NetworkSettings']['Networks'].values()))['IPAddress']
        command = ['docker', 'run', '--rm', '--name', 'saas-load-' + args.label, '--cpus', '4', '--memory', '2g',
                   '--user', f'{os.getuid()}:{os.getgid()}',
                   '-v', str(root / 'benchmarks') + ':/results', '-v', str(fixtures) + ':/secrets/fixtures.json:ro',
                   '-e', 'TENANTS_FILE=/secrets/fixtures.json', '-e', 'BASE_URL=http://' + address + ':8080',
                   '-e', 'MODE=mixed', '-e', 'RPS=' + str(args.rps), '-e', 'DURATION=' + str(args.duration) + 's',
                   '-e', 'K6_SUMMARY_TREND_STATS=avg,min,med,max,p(90),p(95),p(99)', image,
                   'run', '--summary-export', '/results/' + args.label + '-summary.json', '/results/mixed.js']
        started = time.time()
        with (root / 'benchmarks' / f'{args.label}-output.txt').open('w') as output:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
        metadata = {
            'scope': 'Mock transport only; fake tokens, empty lists, echoed IDs. No SaaS/security/database proof.',
            'route': 'private Docker IP, omits Kubernetes Service', 'started_unix': started,
            'target_rps': args.rps, 'duration_seconds': args.duration, 'exit_code': result.returncode,
            'generator_image_id': image, 'server_image_id': server_image,
            'generator_limits': {'cpus': 4, 'memory': '2GiB'}, 'server_limits': {'cpus': 1, 'memory': '128MiB'},
            'workload_sha256': hashlib.sha256((root / 'benchmarks/mixed.js').read_bytes()).hexdigest()}
        (root / 'benchmarks' / f'{args.label}-calibration.json').write_text(json.dumps(metadata, indent=2) + '\n')
        print(json.dumps(metadata, indent=2))
    finally:
        if created: subprocess.run(['docker', 'rm', '-f', server], check=True, stdout=subprocess.DEVNULL)
raise SystemExit(result.returncode)
