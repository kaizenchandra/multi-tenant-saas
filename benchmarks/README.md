# Reproducible local performance checks

From the project directory:

```sh
python3 scripts/local-load.py --native --seed --tenants 32 --warmup-seconds 20 --duration 30 --label local-confirmation-1000rps
python3 scripts/local-load.py --native --seed --tenants 32 --warmup-seconds 10 --duration 60 --label local-sustained-1000rps
```

Native mode uses installed k6; omit `--native` to run the cached Docker image, recording its immutable ID. Credentials
live only in a 0600 temporary env file or native child environment. The runner seeds/reuses 32 BUSINESS tenants with one
project and two tasks each, alternating two identities, then targets localhost:30088 (host-facing Kubernetes NodePort).
Use `--base-url` for a different route; metadata distinguishes internal ClusterIP measurements. Run fault drills
separately.

`load.js` uses `exec.scenario.iterationInTest` for global round robin, with 256 preallocated and 1,000 maximum VUs. It
separates authorized responses, admission/quota rejections and unexpected errors. Thresholds: HTTP failures <1%,
all-response p99 <200 ms and dropped iterations <0.1% of offered requests. Measured requests are never retried. Warm-up
has separate artifacts; any warm-up network retries/errors are disclosed.

| Host-facing run  | Duration | Successful RPS | p99 ms | Admission 429 | Dropped | Result |
|------------------|---------:|---------------:|-------:|--------------:|--------:|--------|
| Queued admission |     30 s |         997.71 |  70.02 |   65 / 30,001 |       0 | PASS   |
| Sustained        |     60 s |         997.29 |  74.19 |  154 / 60,000 |       0 | PASS   |
| Final image      |     30 s |         995.47 | 101.74 |  126 / 30,001 |       0 | PASS   |

Original eight-tenant overload achieved 207.93 successful/s with 3,942 / 4,990 HTTP 429 responses and exit 99. An
internal ClusterIP advisory-fence run also passed, but uses a different route. Other failed attempts and profile
captures remain intact. Resource, workload and code changes are detailed
in [throughput hardening](../docs/throughput-hardening.md) and [verification](../docs/verification.md).

The 100/s tenant policy remains enabled. A single hot tenant should throttle. No `forbiddenId` is supplied in local
performance fixtures, so zero leakage counters are not isolation proof. Independent adversarial tests supply that
evidence. Adding foreign-ID probes sends an extra HTTP request per iteration and changes quotas/offered load.

10,000-RPS mixed/noisy workloads have now been measured and fail full arrival/capacity criteria; see below.100,000 RPS,
thousands of tenants, concurrent export contention and RLS-overhead A/B remain unverified. Compare identical warmed
`EXPLAIN (ANALYZE, BUFFERS)` queries on an isolated test database without disabling production RLS. Record
offered/achieved successful throughput, latency/errors, drops, CPU/GC, pool saturation, queue lag and neighbor impact.

[Machine-readable comparison](throughput-comparison.json) preserves the old failure, intermediate failure and three
passing NodePort runs.

## Mixed and noisy-neighbor workloads

```sh
python3 scripts/local-dependencies.py
kubectl --context orbstack -n saas-isolation-dev patch deployment saas --type=strategic --patch-file=k8s/local-load-patch.yaml
# Wait for readiness and old pods to finish draining, then run one workload at a time:
python3 scripts/mixed-load.py --docker --seed --rps 10000 --mode mixed --label my-mixed-10000
python3 scripts/mixed-load.py --docker --rps 10000 --mode noisy --label my-noisy-10000
python3 scripts/summarize-mixed.py
```

Docker mode discovers Service `saas` port 80, avoiding container-localhost/incorrect8080 routes. Native default is host
NodePort30088; use an explicit base URL to select another route. An authenticated probe precedes load. Each label is
unique; existing results cannot be silently overwritten. The fixture has256 BUSINESS tenants/32
subjects,10%PATCH/90%reads; noisy mode has9,000 hot/1,000 neighbor offered/s with248 neighbor tenants. Credentials are
deduplicated in a protected temporary JSON file shared by VUs, then removed. Current Docker client caps:4 CPU/2 GiB;
preallocated512VUs mixed or 512 hot+128 neighbor. Exact resources, profile, WAL settings, image identity and ambiguous
commits are recorded in metadata.

All 10k runs remain FAIL. The best group-commit mixed trial measured4,546.42 successful/s,p99 101.97 ms but 49.23%errors
and 20,252 drops. Its repeat measured2,547.50 successful/s,p99 192.25 ms,65.19%errors and 52,408 drops. A pre-release
noisy run measured993.86 successful neighbor/s,three errors/19,919 neighbor calls,p99 155.21 ms, but 6,674total drops.
Neighbor latency/error criteria passed; arrival criteria failed. A separate1k mixed diagnostic passed at 996.22
successful/s,p99 142.83 ms. Lower-rate repeats varied; none establish a stable higher envelope.

[Complete derived comparison](mixed-comparison.json), [detailed verification](../docs/verification.md), [quota/export protocol](../docs/reconciliation-and-streaming.md).
Every failed run remains, including two failed generator launches (no request counts fabricated) and a
wrong-Service-port attempt explicitly excluded from capacity evidence. All-response p99 includes429 s; compare
successful-response latency before making capacity claims. All tests retain PostgreSQL RLS, fresh authorization and
synchronous durable WAL. Full-rate hot-tenant fairness needs a dedicated client and repeatable hardware/soak
measurements.

Final-image confirmation: mixed 10k348.22 successful/s,83.46%failures,p99 812.20 ms,151,674 drops; noisy 10k565.90
successful neighbor/s,21.59%neighbor failures,p99 559.76 ms,95,782 drops. Both FAIL. The final1k mixed main phase passed
at 992.67 successful/s,p99 143.38 ms,141/20,000admission rejections and zero drops; its separate short warmup failed.
These results supersede any assumption that best-run throughput is stable. Final raw artifacts use the `release-mixed-*`
and `release-noisy-*` labels.

## Current WAL/CPU and isolated repeat protocol

[Investigation](../docs/performance-regression.md) records the approved isolation window, same-image recovery, source
fixes, all measured phases and profiler limitations. Use `repeatable-performance.py` for fresh synthetic-only
database/cache fixtures, 60s warmup, immutable IDs and automatic restoration. Default SQL tracking is `all` for
diagnostics; pass `--sql-tracking none` for capacity trials and record the difference. JFR captures are diagnostic
overhead, not equivalent to unprofiled capacity. Trial phases share JVMs and accumulating audit/outbox rows. The
dedicated seed/cache are test infrastructure, not production tenant database routing.

`generator-calibration.py` uses fake tokens/echo JSON and the same client/workload. It tests transport/client headroom
only. Its zero errors cannot prove SaaS security or capacity. The current result missed the strict dropped-arrival
threshold. Do not include calibration in SaaS aggregates. The approved pause window has ended; its state is preserved
and the helper refuses to silently start a second window. Cleanup rechecks actual state and surfaces failed restoration.
