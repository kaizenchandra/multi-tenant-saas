# Local 10,000-RPS investigation

The 10,000-RPS acceptance gate remains **FAILED**. The work below improves quota behavior, separates admission
contention, removes unnecessary security filter observations and adds a bounded denial optimization. It does not
establish a sustainable 10k deployment. PostgreSQL FORCE RLS, fresh membership/state checks, hard quota caps,
`fsync=on`, `synchronous_commit=on` and `fdatasync` stayed enabled.

## Why 4,546 became 348 successful RPS

The historical `groupcommit-mixed-10000` run returned 8,954 HTTP responses/s, of which 4,546 were successful (49.23%
errors). `release-mixed-10000` returned 2,105/s, of which 348 were successful (83.46% errors). Useful throughput
collapsed through both fewer responses and a smaller accepted fraction. Neither run passed the 10k gate. Recorded
admission rejections were 88,484 versus 40,386; both had zero quota rejections, so this was not monthly quota
exhaustion. The latter also had 54 unexpected responses/timeouts. Counting fast 429s as application throughput obscures
this distinction.

There are several supported mechanisms, not evidence for one isolated 13x code regression:

* WAL sync averaged **3.403 ms versus 9.348 ms**. Longer commit waits retain bounded request/connection capacity and
  cause more rejections. Cluster WAL deltas include other databases and collectors; they are not exact per-request disk
  service times.
* The old quota heuristic requested sixteen credits only if the same tenant reached the same pod again within 500 ms;
  otherwise it requested one. With 256 tenants and two pods, roughly below 1,024 evenly distributed requests/s, it
  stopped batching. More durable commits then further reduced throughput. Retained ledger samples show 1,050 singleton /
  11,072 sixteen-credit grants in the fast run versus 5,202 / 1,928 in the slow run. These samples were captured before
  short retention elapsed; they describe grants, not invoiced consumption or successful HTTP requests.
  See [grant histograms](../benchmarks/regression-grant-histograms.json).
* JFR found overlapping monitor waits in selector, verified-tenant, release, principal-rate and ingress paths sharing
  one Admission monitor. One `controlled-base-a` pod accumulated 74.85 seconds of selector waits, 56.67 verified waits
  and 50.84 verified-permit release waits during its recording. Summed thread waits overlap and are not elapsed request
  time or CPU time.
* Statistical Java execution samples included substantial Micrometer observations, regex and JWT/RSA work. Compilation
  continued during measured phases after short warmups. Native poll/epoll samples are waiting evidence, not CPU work.
  Startup/JIT and VM scheduling make cold versus warm comparisons unsafe.
* Later CPU samples recorded unrelated containers averaging about 2.6 CPU cores and peaking around five, while WAL
  latency worsened. A learned-denial trial during that spike performed worse despite using less API CPU; attributing
  that result solely to its code would be unjustified.

The strongest counterfactual is the **same retained slow image** (`perf-baseline`, image prefix `95c6db`) with fresh
fixtures, a 60-second warmup and the approved pause of unrelated projects: it achieved **4,490 and 5,235 successful RPS
without application code changes**. That reproduces the earlier useful-throughput range. The precise historical
contribution of each factor cannot be recovered: the earlier best image is no longer available, image IDs were not
recorded then, and the two historical trials were not controlled experiments.

## Implemented changes

`QuotaBudgets` now asks for its configured bounded grant on cold and warm tenants alike. A configured size of one still
supports exact-sized grants; disabling leased requests retains per-request accounting. Active expired grants stay
available for the next grant RPC to close their known-unused balance in the same transaction. Idle cleanup waits one
second; shutdown still attempts bounded closure. A denied grant queues known-unused proof, and unknown credits are never
refunded. RPC latency, granted credits and singleton/batch/expired-arrival outcomes have bounded metric labels.

`Admission` uses separate monitors for independent maps. Verified tenant permits are now acquired **after fresh
membership verification**, through transaction completion. A forged selector with a cached candidate ticket cannot
occupy a victim tenant lane. Principal/global limits still protect work before the database authorization boundary.

`Security` disables individual security-filter observations by default using the actual Spring Security 6.5.11
`shouldObserveRequests` API. Authentication, authorization and outer HTTP observations remain enabled.
`saas.observability.security-filter-spans=true` restores diagnostic filter spans. This choice follows runtime sample
evidence; sample proportions alone are not a CPU-time saving
measurement. [Official observability guide](https://docs.spring.io/spring-security/reference/6.5/servlet/integrations/observability.html).

`VerifiedTrafficGuard` learns only a SHA-256 token fingerprint plus a canonical UUID selector after JWT validation. An
exhausted known pair can receive 429 before repeating expensive authentication. This is **denial-only**, never cached
authentication or membership: every forwarded request still validates JWT and performs current database authorization.
New/changed tokens follow normal authentication. A strict 4,096-pair default cap (configurable up to 65,536), one-second
windows and idle eviction bound memory. Cache saturation skips learning. Token/selector rotation can evade this CPU
optimization; normal subject limits, quotas and tenant verification remain. It is not anonymous DDoS protection. Tests
cover expiry, malformed headers, changed tokens, pair separation and concurrent capacity.

No migration, durability relaxation, BYPASSRLS role or authorization cache was introduced.

## Repeatable foreground protocol

`scripts/repeatable-performance.py` creates a **synthetic-only seed**, then a fresh `saas_perf_run_*` database clone per
trial, with 256 BUSINESS tenants, 32 pinned OIDC identities, one project and two tasks each. It uses a dedicated
disposable Redis namespace/container and immutable API/client image IDs. The live `saas` database is never copied or
deleted. The runner stops this project's Compose worker/API for foreground measurement, switches two local Kubernetes
API replicas to the clone, drains old pods, checkpoints, warms at 1,000 RPS for 60 seconds, and records two measured
phases. It restores the live DB/cache/image/resource configuration in `finally` and drops the clone.

The mixed workload is 80% aggregate list, 10% detail and 10% PATCH. Noisy offers 9,000 RPS to one hot tenant and 1,000
to 248 neighbor tenants with different identities. The tenant policy stays 100/s; hot throttling is expected. Limits
are <1% evaluated-cohort errors, p99 <200 ms, <0.1% dropped arrivals, >=99.9% expected cohort starts, zero detected
foreign results. All phases below are 20 seconds, no retries. The load container has 4 CPU / 2 GiB, 512 preallocated /
1,024 maximum mixed VUs; noisy uses separate bounded VU pools.

The approved pause lasted less than its 15-minute watchdog cap. It covered 27 recorded unrelated containers. A recheck
found three missed by the first cleanup; these were resumed. Final inventory records 22 running and five no longer
existing at the final check, zero paused. The original selection included Kubernetes pods with inherited Compose labels;
future selection explicitly excludes Kubernetes pods. Controllers and other work can replace containers, so this
inventory is not proof of whole-VM quiescence. The hardened cleanup now retries, verifies state, retains prior evidence
and refuses to mark the window closed on an unverified Docker failure. It never recreates removed containers. Tests
exercise successful recheck, daemon failure and
removal. [Window](../benchmarks/isolation-window.json), [manual recheck](../benchmarks/isolation-resume-recheck.json), [final state](../benchmarks/isolation-final-state.json).

This is still one shared 11-vCPU local VM: OS/Kubernetes work and the client compete with the application/database.
Trial order was not randomized. Within a trial, the second phase uses the same JVMs and accumulating audit/outbox rows;
it is not an independent fresh-data trial. Foreground results exclude outbox dispatch and broker throughput. Tiny
project fixtures do not qualify large-tenant joins, concurrent exports or prolonged soak.

## Isolated measurements

These trials use the 82-test `perf-guarded` image. The subsequent 83-test `perf-release` build adds bounded stale-plan
retry before business work; its separate release checks/results are recorded below. No plan changes occur in the
performance workload.

Original profile means two 2.5-CPU / 1-GiB APIs, pool 30 and 28 global permits per pod. Wide means two 3-CPU / 1-GiB
APIs, pool 48 and 46 permits. SQL nested statement tracking was **enabled (`all`) in every isolated run**, with
telemetry but no JFR. Capture `profiled:false` means no JFR, not zero profiling overhead.

| Trial / phase               | Successful business RPS | Evaluated errors | Evaluated p99 ms | Dropped arrivals | Mean WAL sync ms |
|-----------------------------|------------------------:|-----------------:|-----------------:|-----------------:|-----------------:|
| Old image, original / 1     |                4,489.78 |          52.374% |           118.15 |           10,993 |            3.441 |
| Old image, original / 2     |                5,234.89 |          45.030% |            94.71 |            9,215 |            3.020 |
| Final build, original / 1   |                4,802.25 |          49.210% |           115.26 |           10,586 |            4.226 |
| Final build, original / 2   |                5,041.54 |          43.836% |           148.22 |           19,762 |            3.672 |
| Final build, wide mixed / 1 |                5,413.46 |           0.036% |           416.50 |           76,856 |            5.803 |
| Final build, wide mixed / 2 |                5,200.87 |           0.031% |           518.73 |           80,256 |            5.694 |
| Final build, wide noisy / 1 |          1,018.81 total |  0.093% neighbor |  260.18 neighbor |           13,484 |            1.917 |
| Final build, wide noisy / 2 |          1,023.94 total |      0% neighbor |  206.06 neighbor |           11,489 |            2.286 |

**Every row FAILS the full gate.** The wider pool admits more mixed work and reduces errors, while increasing latency
and client occupancy. It is retained as a diagnostic override, not installed as the default. Low all-response latency in
the original profile includes fast rejections and is not useful-throughput latency. Noisy phase two was close to the
latency target, but 206.06 ms is above 200 ms and arrival loss still fails fairness qualification.

Wider mixed phase one acknowledged 12,249 writes but observed 12,255 committed version increments; phase two
acknowledged 11,971 / observed 11,973. Ambiguous commits are retained rather than counted as failures that certainly
rolled back. Fixed-name PATCH is safe to repeat semantically, but the benchmark never retries. Aggregate version
comparisons are sanity checks, not per-request exactly-once proof. All isolated original-profile mixed phases had
matching acknowledged/committed counts.

After other projects resumed, `no-sql-profile-mixed-r1/r2` disabled SQL tracking on the disposable database. They
achieved **4,545.86 / 4,065.22** successful RPS, **0.033% / 0.032%** errors, **412.40 / 409.45 ms** p99 and **107,872 /
112,748** dropped arrivals. Mean WAL sync was **7.058 / 6.286 ms**. These also FAIL; they rule out profiling as the
entire bottleneck, but different external load prevents assigning a clean percentage speedup to profiler removal.

[Derived comparison](../benchmarks/mixed-comparison.json) links primary summaries and records image/resources, profiler
settings and condition files. All failed/cold/contaminated trials remain, including `controlled-*`, `steady-*`,
`learned-final-*` and `cpu3-*`. Setup failures and the historical wrong-Service-port attempt are excluded from capacity
evidence. Warmups are separate and their errors remain visible.

## Storage and CPU interpretation

A disposable logged INSERT pgbench diagnostic used the ordinary non-BYPASSRLS runtime role with durable WAL. Actual
5-second measurements: one client **1,787 TPS / 0.560 ms**, sixteen **3,126 / 5.117 ms**, thirty-two **7,196 / 4.447
ms**, zero failures. [Raw diagnostic](../benchmarks/wal-microbenchmark.json). This is not the SaaS transaction mix: it
omits HTTP/JWT, fresh tenant authorization, domain RLS queries, audit/outbox and cache coordination. It shows the
storage path is not intrinsically capped at 348 commits/s; it does not prove 7,196 SaaS RPS.

Raw `.jfr`, normalized SQL snapshots, active-wait samples and Docker CPU series are preserved per label. JFR excluded
initial environment/system-property events and profiles are protected local files. No debugger session or breakpoints
were available in the IDE; JFR was the explicit runtime-profiling fallback. Nested `pg_stat_statements` times overlap
with calling statements and must not be added as independent elapsed times. CPU-or-runnable database samples cannot
distinguish active execution from host scheduling without OS support; OrbStack exposes no PSI files in these
containers. [PostgreSQL statement statistics](https://www.postgresql.org/docs/17/pgstatstatements.html), [Java 21 jcmd](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html).

The final 82-test build was also profiled in `guarded-final-diagnostic` after other projects resumed. It achieved only
**888.82 successful RPS**, **64.98% errors**, **2,107.99 ms** p99 and **148,693** dropped arrivals; its separate warmup
also failed. This is retained diagnostic evidence, not an isolated capacity comparison. Java execution samples
classified as observations were **124/1,356 (9.1%)**, versus **352/2,099 (16.8%)** in `controlled-base-a`; JWT and regex
remained prominent. Selector monitor waits fell to 1.87–3.57 overlapping thread-seconds per pod in the shorter
recording, but ingress waits remained. Different duration/JIT/external load prevents claiming a measured CPU-time
speedup. Database active-session samples included **761 WALWrite and 82 WalSync** observations; they are occupancy
samples, not exact service-time measurements. Both API containers remained near their CPU
quota. [Baseline profile report](../benchmarks/controlled-base-a-profile-report.json), [82-test profile report](../benchmarks/guarded-final-diagnostic-profile-report.json).

The transport-only calibration used the same client image, 4-CPU/2-GiB cap, headers and `mixed.js`, against a one-CPU
nginx echo on a private Docker route. It delivered **198,879 responses / 9,943.36 RPS**, zero errors, **30.48 ms p99**,
and **1,121 dropped arrivals** over 20 seconds. It **FAILS** the strict arrival/drop criterion. Empty lists/echoed IDs
and fake tokens omit all SaaS work; the route also omits the Kubernetes Service. This establishes substantial client
headroom over useful SaaS throughput but cannot qualify 10k, security, or production
capacity. [Calibration](../benchmarks/generator-transport-10000-calibration.json), [summary](../benchmarks/generator-transport-10000-summary.json).

A 1,024-VU client with long occupied request slots can itself limit launched arrivals: at an illustrative 200 ms average
iteration it supports about 5,120 iterations/s. Wider pools increased occupancy as latency grew, so dropped arrivals do
not prove the client CPU is saturated. Increasing VUs alone would offer more queued/rejected work and would not fix
latency or successful-response capacity.

Release drills found two additional gaps after profiling. A plan change on another replica could expose a stale cached
credit fingerprint as 429. The boundary now invalidates it and uses the existing bounded pre-business retry, with fresh
membership and grant acquisition; business side effects are never replayed. A real PostgreSQL regression checks exactly
one callback and exact confirmed/reserved totals. The restore schema dump now excludes the diagnostic
`pg_stat_statements` extension, preserving restricted owner/runtime privileges instead of granting superuser. The
initial failed suite remains under `verification-performance-*`.

Remaining evidence gates: dedicated client and representative database hardware, sustained background dispatch plus
foreground traffic, random-order repeated trials with database data-size growth controlled, real successful-response
latency, long-duration tenant skew/export workloads, real disk/CPU scheduler telemetry and per-operation RLS-overhead
comparisons. More pooled connections alone is not a fix. The default retains bounded admission and fails safely rather
than claiming 10k capacity.

## Final 83-test release confirmation

The 83-test JAR was rebuilt into `perf-release` and deployed to both Compose and two Kubernetes API replicas. All eight
sequential release drills passed; the initial two failures remain preserved. The 100,000-task / 2,001-project export
produced 23 chunks, 4,887,586 compressed bytes, and validated every one of 102,002 ownership markers, gzip CRC and
SHA-256. Measured seed/build/download-validation: 1.404 / 10.236 / 0.163 seconds. Tenant-only restore matched canonical
contents of all 17 protected tables, including both quota settlement states and binary chunks, dropped its scratch
database and left the neighbor unchanged. Local restore phase was 13.657 seconds; this is not a DR
RTO. [Release suite](../benchmarks/verification-performance-fixed-suite.json), [runtime/image/role evidence](../benchmarks/verification-performance-runtime.json).

The final 1,000-RPS mixed phase **PASS**: 20,000/20,000 successful responses, **999.59 successful RPS**, **37.13 ms
p99**, zero errors/drops, and 2,000 acknowledged / 2,000 committed writes. **Its separate 60-second warmup failed**
(7.213% errors, p99 475.59 ms, 80 dropped arrivals); this is warm-phase evidence only. It ran with live local workers
and SQL tracking enabled; it is separate from foreground-only cloned-fixture
trials. [Summary](../benchmarks/release-confirmation-1000-summary.json).

Final 10k confirmations used the original 2.5-CPU/pool-30 profile, fresh clones/dedicated cache per mode, 60-second 1k
mixed warmup, two 20-second phases, no JFR, SQL tracking `none`. Other projects were running. **All four phases FAIL.**
Noisy errors/latency evaluate neighbors; successful RPS is total hot plus neighbor. CPU context is the approximate sum
of unrelated Docker CPU per complete stats sweep (100% = one core), excluding partial edges; these are not process CPU
samples or a causal decomposition.

| Phase                         | Successful business RPS | Evaluated errors | Evaluated p99 ms | Dropped | WAL sync ms | Other CPU cores, approx. |
|-------------------------------|------------------------:|-----------------:|-----------------:|--------:|------------:|-------------------------:|
| release-confirmation-mixed-r1 |                  239.88 |          79.380% |          1407.10 | 166,844 |      22.918 |                     5.60 |
| release-confirmation-mixed-r2 |                  812.17 |          78.763% |           525.83 | 111,886 |       5.827 |                     2.34 |
| release-confirmation-noisy-r1 |                  798.13 |           4.267% |           349.65 |  37,781 |       4.412 |                     0.86 |
| release-confirmation-noisy-r2 |                  871.66 |           5.622% |           419.39 |  44,450 |       7.690 |                     1.08 |

The mixed phases had zero quota rejections. During mixed phase one, unrelated workloads used about 5.6 cores while both
APIs together averaged only about 1.4 cores despite a five-core combined limit; WAL sync rose to 22.9 ms. This is
consistent with scheduling/storage interference, not a five-core CPU-only service limit. Phase two was less externally
busy but still failed badly. Noisy phases also failed with lower external CPU, so external work is not a complete
explanation or a substitute for tuning. The isolated 82-test measurements remain the better controlled code comparison;
the new 83rd test/fix handles plan changes that the benchmark does not perform. Neither set establishes 10k capacity.

Both clone conditions record `live_deployment_restored:true`, and final runtime evidence checks no trial/restore
database remains, two ready replicas use the tested JAR, live `saas`/Redis routing is restored, all 17 tables retain
FORCE RLS and the runtime role cannot bypass
it. [Mixed conditions](../benchmarks/release-confirmation-mixed-conditions.json), [noisy conditions](../benchmarks/release-confirmation-noisy-conditions.json).
All three final warmups failed: the 1k confirmation above, mixed-clone warmup with 3.882% errors / p99 234.90 ms, and
noisy-clone mixed warmup with 2.521% errors / p99 571.20 ms / 149 drops. Warmup and main failures are separate retained
artifacts; cold-start SLO readiness remains unqualified. The original other-container pause was not extended or
repeated.

## CPU request correction and final configuration

A further local scheduling mismatch was confirmed: each API requested only 250m CPU against a 2,500m CPU limit. The
measured cgroup `cpu.weight` was 35. The node had 11 allocatable CPU cores and about 2.2 cores of pod requests before
the change, sufficient for two 2.5-core requests and a rolling surge. Raising requests to match the existing limits
increased **`cpu.weight` from 35 to 208**, with **`cpu.max` unchanged at
`250000 100000`**. [Measured before/after](../benchmarks/cpu-request-weight.json). This changes relative scheduling
weight and Kubernetes placement; it does not dedicate physical CPUs or isolate storage from Docker workloads.

The final local manifest keeps two 2.5-CPU / 1-GiB limits, pool 30 / global admission 28, with CPU requests now 2.5
each. Aggregate API CPU requests are five cores; allow another 2.5 for a rolling surge plus dependency/system resources.
Smaller nodes need explicit resizing. The earlier isolated and release-confirmation measurements used 250m requests and
remain labeled separately. The wider pool 48 / 3-CPU diagnostic remains an override.

With the higher requests, fresh-clone/cache trials repeated the same 60s warmup, 20s × two measured phases, no JFR, SQL
tracking `none`, other projects active:

| Phase                 | Successful business RPS | Evaluated errors | Evaluated p99 ms | Dropped | WAL sync ms |
|-----------------------|------------------------:|-----------------:|-----------------:|--------:|------------:|
| reserved-cpu-mixed-r1 |                 4200.83 |          51.252% |           149.27 |  27,216 |       3.797 |
| reserved-cpu-mixed-r2 |                 4042.56 |          52.193% |           137.21 |  21,394 |       3.929 |
| reserved-cpu-noisy-r1 |                 1004.95 |           0.887% |           190.71 |  22,699 |       2.905 |
| reserved-cpu-noisy-r2 |                  924.58 |           4.260% |           366.19 |  35,288 |       3.316 |

All four phases still **FAIL** the complete gate. Noisy phase one meets neighbor error/latency limits but loses too many
offered arrivals; phase two fails both. Other-container CPU averaged only about 0.58–0.75 cores during these trials,
versus 5.6 in the earlier poor mixed phase. Consequently, the RPS recovery cannot be assigned solely to request weights.
The weight change itself is directly verified, and the workload's observed CPU demand justifies the larger scheduling
request; it is not proof of 10k capacity. Read the complete error/drop columns rather than fast-rejection latency alone.

A final live-worker 1k check on the corrected request profile **PASS**: **999.73 successful RPS**, zero errors/drops,
**21.88 ms p99**, 20,000 successful responses and 2,000 acknowledged / committed writes. Its separate 60-second warmup
**FAILS** (3.153% errors, p99 341.95 ms, one dropped arrival). It uses the live fixture and SQL tracking `all`, not a
fresh-clone 10k capacity
protocol. [Main summary](../benchmarks/reserved-cpu-1000-summary.json), [warmup](../benchmarks/reserved-cpu-1000-warmup-summary.json), [metadata](../benchmarks/reserved-cpu-1000-metadata.json).

`python3 scripts/verify-runtime.py --label verification-performance` passed on the final configuration: both replicas
and Compose match the verified 83-test JAR; all 17 exact RLS policies have USING/WITH CHECK and FORCE enabled; runtime
is neither superuser, BYPASSRLS nor an owner member; missing scope sees zero projects; durable WAL remains enabled; no
trial/restore databases remain; live DB/cache routing is restored. All 22 surviving recorded containers are running and
five old IDs no longer exist; none is paused. The original pause window was not
repeated. [Runtime evidence](../benchmarks/verification-performance-runtime.json).

## Commands

```sh
# Build the tested local image:
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn verify
docker build -f docker/Dockerfile.prebuilt -t multi-tenant-saas-app:perf-release .
# Diagnostic module prerequisite; recreates only this project's PostgreSQL.
# Do this before load, then refresh private dependency addresses and wait for readiness:
docker compose -f docker-compose.yml -f docker-compose.profiling.yml up -d postgres
python3 scripts/local-dependencies.py
# Existing local dependencies and two API replicas must be ready.
# One-time synthetic-only seed; refuses to overwrite an existing seed:
python3 scripts/repeatable-performance.py --prepare --image multi-tenant-saas-app:perf-release
# Fresh clone/cache per trial; restores local deployment afterward:
python3 scripts/repeatable-performance.py --image multi-tenant-saas-app:perf-release --label fresh-mixed --mode mixed --repeats 2 --duration 20 --sql-tracking none
python3 scripts/repeatable-performance.py --image multi-tenant-saas-app:perf-release --label fresh-noisy --mode noisy --repeats 2 --duration 20 --sql-tracking none
# Diagnostic only; JFR/SQL capture adds overhead:
python3 scripts/repeatable-performance.py --image multi-tenant-saas-app:perf-release --label fresh-profile --mode mixed --duration 20 --profile --sql-tracking all
python3 scripts/summarize-profile.py --label fresh-profile
python3 scripts/summarize-mixed.py
# Read-only release/role/image/pause verification (new label each time):
python3 scripts/verify-runtime.py --label fresh-runtime
# Transport-only calibration, no real authentication or SaaS operations:
python3 scripts/generator-calibration.py --label fresh-generator --rps 10000 --duration 20
```

Use new labels; do not overlap fault drills or other load tests. The original approved pause artifact is deliberately
preserved and a second pause requires a fresh bounded operational decision. The profiling Compose override is local
diagnostics only; base Compose omits nested statement tracking. The repeatable runner sets its SQL tracking explicitly
per cloned database.
