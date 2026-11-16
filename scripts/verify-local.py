#!/usr/bin/env python3
"""Local-only release drills, sequentially; never overlap fault drills with load tests.

Creates synthetic tenants and a disposable restore database. The dependency drills
restart this Compose project's service in finally. Never run against a remote target.
"""
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--label', default='verification-streaming')
args = parser.parse_args()
assert all(c.isalnum() or c in '-_' for c in args.label)
if args.label != 'verification-streaming':
    assert not (root / 'benchmarks' / f'{args.label}-suite.json').exists(), 'Use a new label to preserve evidence'
checks = [
    ('compose', 'demo.py', 'http://localhost:8088'),
    ('k8s', 'demo.py', 'http://localhost:30088'),
    ('quota', 'quota-reconciliation.py', 'http://localhost:8088'),
    ('large-export', 'large-export.py', 'http://localhost:30088'),
    ('restore', 'restore-drill.py', 'http://localhost:8088'),
    ('kafka', 'kafka-failure.py', 'http://localhost:8088'),
    ('postgres', 'database-failure.py', 'http://localhost:8088'),
    ('pod', 'k8s-smoke.py', 'http://localhost:30088'),
]
results = []
for name, script, base in checks:
    label = args.label + '-' + name
    (root / 'benchmarks' / f'{label}.json').unlink(missing_ok=True)
    started = time.monotonic()
    completed = subprocess.run([sys.executable, str(root / 'scripts' / script)],
                               cwd=root, env={**os.environ, 'BASE_URL': base},
                               capture_output=True, text=True)
    (root / 'benchmarks' / f'{label}-output.txt').write_text(completed.stdout + completed.stderr)
    outcome = {'check': name, 'exit_code': completed.returncode,
               'elapsed_seconds': round(time.monotonic() - started, 3), 'base_url': base}
    if completed.returncode == 0:
        try:
            detail = json.loads(completed.stdout)
            (root / 'benchmarks' / f'{label}.json').write_text(json.dumps(detail, indent=2) + '\n')
        except json.JSONDecodeError:
            outcome['exit_code'] = 1
            outcome['error'] = 'Successful process did not produce a JSON result'
    results.append(outcome)
    print(json.dumps(outcome), flush=True)
    (root / 'benchmarks' / f'{args.label}-suite.json').write_text(json.dumps(results, indent=2) + '\n')
raise SystemExit(1 if any(r['exit_code'] for r in results) else 0)
