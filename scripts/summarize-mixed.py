#!/usr/bin/env python3
"""Derive measurements from preserved k6 summaries; never replace missing evidence."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
rows = []


def cpu_context(path):
    if not path.exists(): return {}
    samples = [json.loads(line) for line in path.read_text().splitlines()]
    anchor = next((r['group'] for r in samples if r['group'] != 'other'), None)
    if anchor is None: return {}
    # Each Docker stats sweep repeats this own-container anchor. Timestamp-only
    # grouping merges sweeps when a busy host delivers output in bursts.
    sweeps = [];
    current = [];
    own = {}
    for sample in samples:
        if sample['group'] == anchor:
            if current: sweeps.append(current)
            current = []
        current.append(sample)
        if sample['group'] != 'other' and sample['cpu_percent'] is not None:
            own.setdefault(sample['group'], []).append(sample['cpu_percent'])
    totals = [sum((r['cpu_percent'] or 0) for r in sweep if r['group'] == 'other') for sweep in sweeps[1:]]
    return {
        'scope': 'Approximate Docker CPU percent; 100 percent is one core. Anonymous unrelated containers summed per complete stats sweep, excluding partial edges.',
        'external_cpu_percent_mean': sum(totals) / len(totals) if totals else None,
        'external_cpu_percent_max': max(totals, default=None), 'complete_sweeps': len(totals),
        'own_cpu_percent_mean': {key: sum(v) / len(v) for key, v in own.items()}}


for path in sorted((root / 'benchmarks').glob('*-metadata.json')):
    label = path.name.removesuffix('-metadata.json')
    meta = json.loads(path.read_text())
    if meta.get('mode') not in ('mixed', 'noisy') or label.endswith('-warmup'):
        continue
    summary = root / 'benchmarks' / f'{label}-summary.json'
    metrics = json.loads(summary.read_text())['metrics'] if summary.exists() else {}
    cohort = 'neighbor' if meta['mode'] == 'noisy' else 'normal'
    failed = metrics.get('business_failed{cohort:' + cohort + '}', {})
    latency = metrics.get('business_latency{cohort:' + cohort + '}', {})
    requests = metrics.get('http_reqs', {})
    # k6 Rate.passes counts true values: business_failed true means an error.
    successes = failed.get('fails')
    elapsed = requests['count'] / requests['rate'] if requests.get('rate') else None
    condition_path = root / 'benchmarks' / (re.sub(r'-r[1-3]$', '', label) + '-conditions.json')
    conditions = json.loads(condition_path.read_text()) if condition_path.exists() else {}
    before, after = meta.get('wal_before', {}), meta.get('wal_after', {})
    syncs = after.get('wal_sync', 0) - before.get('wal_sync', 0)
    sync_ms = (after.get('wal_sync_time', 0) - before.get('wal_sync_time', 0)) / syncs if syncs else None
    row = dict(label=label, mode=meta['mode'], target_rps=meta['target_rps'],
               wal_mean_sync_ms=sync_ms,
               cpu_context=cpu_context(root / 'benchmarks' / f'{label}-containers.jsonl'),
               jfr_profiled=meta.get('profiled'), telemetry=meta.get('telemetry'),
               sql_tracking=meta.get('postgres_settings', {}).get('pg_stat_statements.track',
                                                                  conditions.get('sql_tracking', 'not recorded')),
               foreground_only=conditions.get('foreground_only'),
               resources=meta.get('container_resources'), api_image_ids=meta.get('api_image_ids'),
               conditions_file=condition_path.name if condition_path.exists() else None,
               result='PASS' if meta['exit_code'] == 0 else 'FAIL',
               summary_available=summary.exists(), base_url=meta['base_url'],
               http_requests=requests.get('count'), http_rps=requests.get('rate'),
               successful_rps=metrics.get('business_success', {}).get('rate'),
               evaluated_cohort=cohort, cohort_error_rate=failed.get('value'),
               cohort_p99_ms=latency.get('p(99)'),
               cohort_successful_rps=successes / elapsed if successes is not None and elapsed else None,
               dropped_iterations=metrics.get('dropped_iterations', {}).get('count'),
               acknowledged_writes=meta.get('successful_writes'),
               committed_version_delta=meta.get('committed_version_delta'),
               unacknowledged_commits=meta.get('unacknowledged_commits'),
               unexpected_responses=metrics.get('unexpected_responses', {}).get('count') if summary.exists() else None,
               isolation_violations=metrics.get('isolation_violations', {}).get('count'),
               capacity_evidence=summary.exists() and not label.startswith('balanced-'),
               note='Incorrect Service port; API idle, not capacity evidence' if label.startswith(
                   'balanced-') else None)
    if not summary.exists():
        row['note'] = 'Generator failed to launch; no request totals inferred'
    rows.append(row)
result = {
    'scope': 'Local shared-host measurements. Cohort RPS includes drain time. No retries; warmups are separate. Zero performance leakage counters are not adversarial isolation proof.',
    'runs': rows}
(root / 'benchmarks' / 'mixed-comparison.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
