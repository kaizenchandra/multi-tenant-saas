#!/usr/bin/env python3
"""Logged INSERT/commit diagnostic on a disposable synthetic DB, not SaaS capacity.
Uses the ordinary runtime role and durable WAL. Restores this project's worker in finally.
"""
import json
import subprocess
import time
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1]
compose = ['docker', 'compose', '-f', str(root / 'docker-compose.yml')]
database = 'saas_perf_run_' + uuid.uuid4().hex
file = '/tmp/saas-wal-' + uuid.uuid4().hex + '.sql'


def sql(query, db='saas'):
    assert db == 'saas' or db == database
    return subprocess.check_output(compose + ['exec', '-T', 'postgres', 'psql', '-X', '-qAt',
                                              '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', db], input=query,
                                   text=True)


created = False
running = bool(subprocess.check_output(compose + ['ps', '--status', 'running', '-q', 'app'], text=True).strip())
result = {
    'scope': 'Durable logged INSERT over a Unix socket with saas_runtime; no HTTP, JWT, RLS-domain query, audit/outbox or authorization cost. This is not application throughput.',
    'runs': []}
try:
    if running: subprocess.run(compose + ['stop', '--timeout', '20', 'app'], check=True)
    sql('CREATE DATABASE ' + database + ' TEMPLATE saas_perf_seed OWNER saas_owner;');
    created = True
    sql('CREATE TABLE wal_probe(id bigserial primary key,payload text); GRANT INSERT ON wal_probe TO saas_runtime; GRANT USAGE ON SEQUENCE wal_probe_id_seq TO saas_runtime;',
        database)
    subprocess.run(compose + ['exec', '-T', 'postgres', 'sh', '-c', 'cat > "$1"', 'sh', file],
                   input="INSERT INTO wal_probe(payload) VALUES(repeat('x',128));\n", text=True, check=True)
    for clients in [1, 16, 32]:
        sql('TRUNCATE wal_probe; CHECKPOINT;', database)
        before = json.loads(sql('select row_to_json(w) from pg_stat_wal w;', database))
        completed = subprocess.run(compose + ['exec', '-T', 'postgres', 'pgbench', '-n', '-U', 'saas_runtime',
                                              '-d', database, '-c', str(clients), '-j', '2', '-T', '5', '-f', file],
                                   capture_output=True, text=True)
        after = json.loads(sql('select row_to_json(w) from pg_stat_wal w;', database))
        result['runs'].append({'clients': clients, 'exit_code': completed.returncode,
                               'output': completed.stdout + completed.stderr, 'wal_before': before, 'wal_after': after})
    result['settings'] = json.loads(
        sql("select json_object_agg(name,setting) from pg_settings where name in ('fsync','synchronous_commit','commit_delay','commit_siblings','wal_sync_method');",
            database))
finally:
    subprocess.run(compose + ['exec', '-T', 'postgres', 'rm', '-f', file], check=True)
    if created: sql('DROP DATABASE ' + database + ';')
    if running: subprocess.run(compose + ['start', 'app'], check=True)
(root / 'benchmarks/wal-microbenchmark.json').write_text(json.dumps(result, indent=2) + '\n')
for run in result['runs']: print(run['clients'], run['output'])
assert all(r['exit_code'] == 0 for r in result['runs'])
