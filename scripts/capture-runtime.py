#!/usr/bin/env python3
"""Bounded read-only local metrics; bearer credentials are never persisted."""
import argparse
import json
import subprocess
import time
import urllib.parse
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser();
p.add_argument('--label', required=True);
p.add_argument('--samples', type=int, default=6);
args = p.parse_args()
assert 1 <= args.samples <= 30 and all(c.isalnum() or c in '-_' for c in args.label)
body = urllib.parse.urlencode(
    dict(client_id='saas-ui', grant_type='password', username='platform', password='demo-password')).encode()
with urllib.request.urlopen(
        urllib.request.Request('http://localhost:8188/realms/saas/protocol/openid-connect/token',
                               data=body)) as r: token = \
    json.load(r)['access_token']
cmd = ['kubectl', '--context', 'orbstack', '-n', 'saas-isolation-dev']
compose = ['docker', 'compose', '-f', str(root / 'docker-compose.yml'), 'exec', '-T', 'postgres']
prefixes = ('process_cpu_usage', 'system_cpu_usage', 'jvm_gc_pause_seconds_', 'jvm_memory_used_bytes',
            'hikaricp_connections_active', 'hikaricp_connections_pending', 'http_server_requests_seconds_',
            'saas_api_budget_')
with (root / f'benchmarks/{args.label}-runtime.jsonl').open('w') as out:
    for _ in range(args.samples):
        sample = {'unix': time.time(), 'pods': {}}
        pods = json.loads(subprocess.check_output(cmd + ['get', 'pods', '-l', 'app=saas', '-o', 'json']))['items']
        for pod in pods:
            try:
                with urllib.request.urlopen(
                        urllib.request.Request('http://' + pod['status']['podIP'] + ':8081/actuator/prometheus',
                                               headers={'Authorization': 'Bearer ' + token}), timeout=3) as r:
                    data = r.read().decode()
                sample['pods'][pod['metadata']['name']] = {
                    'metrics': [line for line in data.splitlines() if line.startswith(prefixes)],
                    'cpu_stat': subprocess.check_output(
                        cmd + ['exec', pod['metadata']['name'], '--', 'cat', '/sys/fs/cgroup/cpu.stat'], text=True),
                    'cpu_max': subprocess.check_output(
                        cmd + ['exec', pod['metadata']['name'], '--', 'cat', '/sys/fs/cgroup/cpu.max'], text=True)}
            except Exception as e:
                sample['pods'][pod['metadata']['name']] = type(e).__name__
        sample['postgres'] = json.loads(subprocess.check_output(
            compose + ['psql', '-X', '-qAt', '-U', 'postgres', '-d', 'saas', '-c',
                       "select coalesce(json_agg(row_to_json(r)),'[]') from (select state,wait_event_type,wait_event,count(*) from pg_stat_activity where datname='saas' group by 1,2,3)r"],
            text=True))
        out.write(json.dumps(sample) + '\n');
        out.flush();
        time.sleep(2)
