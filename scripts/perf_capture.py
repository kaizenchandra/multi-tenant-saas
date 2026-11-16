"""Bounded local diagnostics. No tokens, environment variables or SQL bind values saved."""
import collections
import json
import re
import subprocess
import threading
import time
from pathlib import Path


class Capture:
    def __init__(self, root, label, database="saas", record_jfr=True):
        self.root, self.label = Path(root), label
        self.database = database
        self.record_jfr = record_jfr
        assert database == "saas" or (database.startswith("saas_perf_") and database.replace("_", "").isalnum())
        self.stop = threading.Event()
        self.threads, self.recordings = [], []
        self.psql = ['docker', 'compose', '-f', str(self.root / 'docker-compose.yml'),
                     'exec', '-T', 'postgres', 'psql', '-X', '-qAt', '-v',
                     'ON_ERROR_STOP=1', '-U', 'postgres', '-d', database]
        self.errors = collections.Counter()

    def sql(self, query):
        return json.loads(subprocess.check_output(self.psql + ['-c', query], text=True))

    def jcmd(self, recording, *arguments):
        return subprocess.check_output(['docker', 'run', '--rm',
                                        '--pid=container:' + recording['container'], '-v', recording['tmp'] + ':/tmp',
                                        '--user', '10001:0', 'eclipse-temurin:21-jdk', 'jcmd', '1', *arguments],
                                       text=True, stderr=subprocess.STDOUT, timeout=20)

    def begin(self):
        ids = subprocess.check_output(['docker', 'ps', '-q', '--filter',
                                       'label=io.kubernetes.pod.namespace=saas-isolation-dev', '--filter',
                                       'label=io.kubernetes.container.name=app'], text=True).split()
        if not self.record_jfr: ids = []
        for cid in ids:
            info = json.loads(subprocess.check_output(['docker', 'inspect', cid]))[0]
            temporary = next(m['Source'] for m in info['Mounts'] if m['Destination'] == '/tmp')
            assert temporary.startswith('/var/lib/')
            recording = dict(container=cid, tmp=temporary, file='/tmp/' + self.label + '.jfr')
            output = self.jcmd(recording, 'JFR.start', 'name=saas-profile',
                               'settings=profile', 'maxsize=16m', 'filename=' + recording['file'],
                               'jdk.InitialEnvironmentVariable#enabled=false',
                               'jdk.InitialSystemProperty#enabled=false', 'jdk.JavaExceptionThrow#enabled=false')
            if 'Started recording' not in output:
                raise RuntimeError('JFR failed: ' + output)
            self.recordings.append(recording)
        self.stats = subprocess.Popen(['docker', 'stats', '--format', '{{json .}}'],
                                      stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        self.db = subprocess.Popen(self.psql, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, text=True, bufsize=1)
        for target in (self.sample_stats, self.sample_db):
            t = threading.Thread(target=target, daemon=True)
            t.start();
            self.threads.append(t)
        self.before = self.sql(self.statement_query().replace("datname='saas'", "datname='" + self.database + "'"))

    def sample_stats(self):
        with (self.root / 'benchmarks' / f'{self.label}-containers.jsonl').open('w') as out:
            for line in self.stats.stdout:
                if self.stop.is_set(): break
                try:
                    cleaned = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', line).strip()
                    if not cleaned: continue
                    row = json.loads(cleaned)
                    own = row['Name'].startswith(('multi-tenant-saas-', 'k8s_app_saas-', 'saas-load-', 'saas-perf-'))
                    percent = row['CPUPerc'].strip('%')
                    out.write(json.dumps(dict(unix=time.time(), group=row['Name'] if own else 'other',
                                              cpu_percent=float(percent) if percent not in ('--', '') else None,
                                              memory=row['MemUsage'],
                                              pids=row['PIDs'])) + '\n');
                    out.flush()
                except Exception as error:
                    self.errors[type(error).__name__] += 1

    def sample_db(self):
        query = """select json_build_object('unix', extract(epoch from clock_timestamp()),
                                            'activity', (select coalesce(json_agg(r), '[]')
                                                         from (select state,
                                                                      wait_event_type,
                                                                      wait_event,
                                                                      case
                                                                          when query ilike '%grant_api_budget%' then 'grant'
                                                                          when query ilike '%establish_tenant%' then 'authorize'
                                                                          when query = 'COMMIT' then 'commit'
                                                                          else 'other' end as operation,
                                                                      count(*)             as sessions
                                                               from pg_stat_activity
                                                               where datname = 'saas'
                                                                 and pid <> pg_backend_pid()
                                                               group by 1, 2, 3, 4) r),
                                            'wal', (select row_to_json(w) from pg_stat_wal w),
                                            'checkpointer', (select row_to_json(c) from pg_stat_checkpointer c),
                                            'pressure',
                                            json_build_object('cpu', pg_read_file('/proc/pressure/cpu', true),
                                                              'io', pg_read_file('/proc/pressure/io', true), 'memory',
                                                              pg_read_file('/proc/pressure/memory', true))) ::jsonb::text; \
                """
        with (self.root / 'benchmarks' / f'{self.label}-database.jsonl').open('w') as out:
            while not self.stop.is_set():
                try:
                    self.db.stdin.write(query.replace("datname='saas'", "datname='" + self.database + "'") + '\n');
                    self.db.stdin.flush()
                    line = self.db.stdout.readline()
                    json.loads(line)
                    out.write(line);
                    out.flush()
                except Exception as error:
                    self.errors[type(error).__name__] += 1;
                    break
                self.stop.wait(.1)

    @staticmethod
    def statement_query():
        return """select coalesce(json_agg(r), '[]')
                  from (select queryid::text,userid,
                               toplevel,
                               query,
                               calls,
                               total_exec_time, rows, wal_records, wal_bytes::float8, shared_blks_hit, shared_blks_read, shared_blk_read_time, shared_blk_write_time
                        from pg_stat_statements
                        where dbid=(select oid from pg_database where datname='saas')) r"""

    def finish(self):
        after = self.sql(self.statement_query().replace("datname='saas'", "datname='" + self.database + "'"))
        self.stop.set()
        self.stats.terminate();
        self.db.terminate()
        for thread in self.threads: thread.join(timeout=3)
        self.stats.wait(timeout=5);
        self.db.wait(timeout=5)
        before = {(s['queryid'], s['userid'], s['toplevel']): s for s in self.before}
        deltas = []
        for row in after:
            old = before.get((row['queryid'], row['userid'], row['toplevel']), {})
            result = {k: row[k] for k in ('queryid', 'toplevel', 'query')}
            for k in ('calls', 'total_exec_time', 'rows', 'wal_records', 'wal_bytes',
                      'shared_blks_hit', 'shared_blks_read', 'shared_blk_read_time', 'shared_blk_write_time'):
                result[k] = row[k] - old.get(k, 0)
            if result['calls'] > 0: deltas.append(result)
        for recording in self.recordings:
            try:
                self.jcmd(recording, 'JFR.stop', 'name=saas-profile')
                target = self.root / 'benchmarks' / f'{self.label}-{recording["container"]}.jfr'
                subprocess.run(['docker', 'cp', recording['container'] + ':' + recording['file'], str(target)],
                               check=True)
                target.chmod(0o600)
                subprocess.run(['docker', 'exec', recording['container'], 'rm', recording['file']], check=True)
            except Exception as error:
                self.errors[type(error).__name__] += 1
        (self.root / 'benchmarks' / f'{self.label}-sql.json').write_text(json.dumps(
            {'scope': 'profiled run; nested SQL times overlap and cannot be added to top-level times',
             'statements': sorted(deltas, key=lambda r: -r['total_exec_time']), 'errors': self.errors},
            indent=2) + '\n')
