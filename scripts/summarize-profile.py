#!/usr/bin/env python3
"""Summarize actual JFR/SQL/wait samples. Sample counts are not exact CPU time."""
import argparse
import collections
import json
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser();
parser.add_argument('--label', required=True);
args = parser.parse_args()
assert all(c.isalnum() or c in '-_' for c in args.label)
jdk = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip()
result = {'label': args.label,
          'scope': 'Execution samples are statistical. Monitor durations sum overlapping thread waits; compilation durations are elapsed, not CPU time.',
          'pods': []}
for file in sorted((root / 'benchmarks').glob(args.label + '-*.jfr')):
    events = json.loads(subprocess.check_output([jdk + '/bin/jfr', 'print', '--json', '--events',
                                                 'jdk.ExecutionSample,jdk.JavaMonitorEnter,jdk.GCPhasePause,jdk.Compilation,jdk.ThreadCPULoad,jdk.InitialEnvironmentVariable,jdk.InitialSystemProperty',
                                                 str(file)]))['recording']['events']
    buckets = collections.Counter();
    leaf = collections.Counter();
    waits = collections.Counter();
    wait_counts = collections.Counter()
    gc = [];
    compilations = [];
    threads = collections.defaultdict(list)
    for event in events:
        kind = event['type'];
        v = event['values']
        assert kind not in ('jdk.InitialEnvironmentVariable',
                            'jdk.InitialSystemProperty'), 'Profile must exclude initial credentials/environment'
        stack = [f['method']['type']['name'] + '.' + f['method']['name'] for f in
                 (v.get('stackTrace') or {}).get('frames', [])]
        if kind == 'jdk.ExecutionSample':
            if stack: leaf[stack[0]] += 1
            for category, terms in [('jwt', ['java/math', 'sun/security/rsa', 'com/nimbusds']),
                                    ('regex', ['java/util/regex']), ('json', ['com/fasterxml']),
                                    ('jdbc', ['org/postgresql']), ('observations', ['io/micrometer']),
                                    ('spring', ['org/springframework'])]:
                if any(any(term in frame for term in terms) for frame in stack): buckets[category] += 1;break
            else:
                buckets['other'] += 1
        elif kind == 'jdk.JavaMonitorEnter':
            name = next((frame for frame in stack if frame.startswith('com/example/saas')),
                        stack[0] if stack else 'unknown')
            waits[name] += float(v['duration'][2:-1]);
            wait_counts[name] += 1
        elif kind == 'jdk.GCPhasePause':
            gc.append(float(v['duration'][2:-1]))
        elif kind == 'jdk.Compilation':
            compilations.append(float(v['duration'][2:-1]))
        elif kind == 'jdk.ThreadCPULoad':
            thread = v['eventThread'];
            threads[thread.get('javaName') or thread.get('osName')].append(v['user'] + v['system'])
    result['pods'].append({'file': file.name, 'execution_samples': sum(buckets.values()), 'sample_buckets': buckets,
                           'top_leaf_frames': leaf.most_common(20), 'monitor_wait_seconds': waits.most_common(20),
                           'monitor_wait_events': wait_counts.most_common(20), 'gc_pause_count': len(gc),
                           'gc_pause_seconds': sum(gc), 'max_gc_pause_seconds': max(gc, default=None),
                           'compilation_count': len(compilations), 'compilation_elapsed_seconds': sum(compilations),
                           'thread_cpu_reported_fraction': sorted(
                               [(name, sum(values) / len(values)) for name, values in threads.items()],
                               key=lambda x: -x[1])[:12]})
containers = root / 'benchmarks' / f'{args.label}-containers.jsonl'
if containers.exists():
    values = collections.defaultdict(list)
    for line in containers.read_text().splitlines():
        row = json.loads(line)
        if row['cpu_percent'] is not None: values[row['group']].append(row['cpu_percent'])
    result['container_cpu_percent'] = {key: {'mean': sum(v) / len(v), 'max': max(v), 'samples': len(v)} for key, v in
                                       values.items() if key != 'other'}
database = root / 'benchmarks' / f'{args.label}-database.jsonl'
if database.exists():
    samples = [json.loads(line) for line in database.read_text().splitlines()];
    occupancy = collections.Counter()
    for row in samples:
        for group in row['activity']:
            if group['state'] == 'active': occupancy[
                (group['wait_event_type'] or 'CPU-or-runnable') + ':' + (group['wait_event'] or '')] += group[
                'sessions']
    result['database_samples'] = len(samples)
    result['active_session_sample_counts'] = occupancy
sql = root / 'benchmarks' / f'{args.label}-sql.json'
if sql.exists():
    data = json.loads(sql.read_text());
    result['collector_errors'] = data['errors'];
    result['top_sql'] = data['statements'][:15]
(root / 'benchmarks' / f'{args.label}-profile-report.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({k: v for k, v in result.items() if k not in ('pods', 'top_sql')}, indent=2))
