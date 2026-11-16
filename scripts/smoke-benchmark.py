#!/usr/bin/env python3
"""Small local latency sample. Not a throughput or production benchmark."""
import json
import os
import statistics
import time
import urllib.parse

base = os.environ.get('BASE_URL', 'http://localhost:8088')
request = urllib.request.Request('http://localhost:8188/realms/saas/protocol/openid-connect/token',
                                 data=urllib.parse.urlencode(
                                     dict(client_id='saas-ui', grant_type='password', username='alice',
                                          password='demo-password')).encode())
with urllib.request.urlopen(request) as r: token = json.load(r)['access_token']
headers = {'Authorization': 'Bearer ' + token}
with urllib.request.urlopen(
        urllib.request.Request(base + '/api/v1/me/tenants', headers=headers)) as r: tenants = json.load(r)
tenant = next(t['id'] for t in tenants if t['state'] == 'ACTIVE');
headers['X-Tenant-ID'] = tenant
samples = [];
errors = 0;
started = time.perf_counter()
for _ in range(50):
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(urllib.request.Request(base + '/api/v1/projects', headers=headers)) as r:
            assert r.status == 200;
            json.load(r)
    except Exception:
        errors += 1
    samples.append((time.perf_counter() - start) * 1000)
    elapsed = time.perf_counter() - start
    if elapsed < 0.025: time.sleep(0.025 - elapsed)
duration = time.perf_counter() - started;
samples.sort()
print(json.dumps({'samples': len(samples), 'concurrency': 1, 'errors': errors, 'elapsed_seconds': round(duration, 3),
                  'achieved_requests_per_second': round(len(samples) / duration, 2),
                  'p50_ms': round(statistics.median(samples), 2), 'p95_ms': round(samples[47], 2),
                  'p99_ms': round(samples[49], 2),
                  'limitation': '50 sequential local list calls, paced at <=40 requests/s; not a scale claim'},
                 indent=2))
