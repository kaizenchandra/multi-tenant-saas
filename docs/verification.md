# Executed verification — throughput and local hardening

Environment: macOS ARM64, Java 21, real Docker/OrbStack dependencies and single-node Kubernetes. The user selected local
deployment. These checks do not qualify a cloud deployment or a production SLO.

## Latest automated checks

`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q verify` succeeded with **83 Java tests, zero failures/errors/skips**.
Real PostgreSQL17.6 and Redis7.4.6 Testcontainers verify database/cache behavior; H2 is not RLS evidence.
`node --test frontend-tests/session.test.cjs`: **6 passed**. Python compilation, JavaScript syntax and OpenAPI JSON
parsing passed. The prebuilt Docker path was fixed with a Dockerfile-specific ignore file, then successfully built from
the verified JAR. Interactive browser/visual QA remains unverified.

| Suite                                       |  Tests | Result |
|---------------------------------------------|-------:|--------|
| IsolationTest                               |     64 | PASS   |
| AdmissionTest                               |      5 | PASS   |
| QueryCacheTest                              |      2 | PASS   |
| ProductionGuardTest                         |      3 | PASS   |
| TokenValidationTest                         |      2 | PASS   |
| VerifiedTrafficGuardTest                    |      4 | PASS   |
| CacheTest / RedisIsolationTest / ErrorsTest | 1 each | PASS   |

New tests cover conservative quota settlement, exactly-once late correction, stable actor-bound grant replay,
independent grant owners under concurrency, forged selectors that cannot burn victim credits, metered writes with
business rollback, pre-bean transport validation, real signed token expiry/issuer/audience checks, streaming beyond the
old row limits, replaced job generations, midstream membership revocation, atomic bulk storage reservations, zero-delta
storage updates, migration-history/ledger integrity, and exact PROVEN/ASSUMED evidence restoration.

Existing adversarial tests cover omitted predicates, missing context, IDOR, native/bulk SQL, tenant-aware FKs,
alternating connections, nested/REQUIRES_NEW boundaries, multiple memberships, role escalation, cache failure/envelopes,
jobs/events, lifecycle races and strict quotas. A passing suite is not independent penetration testing or full
infrastructure-chaos coverage.

## Current performance investigation

The 348-RPS regression has been investigated using retained-image comparisons, JFR, normalized SQL, WAL/wait and
container CPU captures. [Full report and repeatable commands](performance-regression.md). An approved bounded pause of
unrelated projects reproduced 4,490–5,235 successful mixed RPS on the retained old image, and 4,802–5,042 on the final
build with the same resources. Wider resources reached 5,201–5,413, with p99 417–519 ms; noisy neighbor p99 was 206–260
ms. **All failed the full 10k gate.** SQL tracking disabled trials and the transport-only generator calibration also
remain as failed evidence, not qualified capacity.

New tests cover cold-grant batching, coalesced expiry returns, concurrent admission bounds, fresh membership before
tenant-lane admission, retained authn/authz observations, and denial-only learned guard isolation/capacity/expiry. Three
Python restoration tests pass. Other-project pause state is fully rechecked; no recorded container remains paused. The
final-build runtime profile and its warmup failed, and are explicitly diagnostic, not hidden retries. Historical "final
image" labels below refer to the earlier V17 image, not the current 83-test build.

## Historical mixed and noisy-neighbor 10,000-RPS measurements

The requested tests were executed. **Full 10,000-RPS capacity criteria still FAIL.** Keep offered traffic, launched HTTP
requests, successful business responses and expected hot-tenant throttling separate. All primary phases below last 20
seconds except the 15 second 1k diagnostic. No requests are retried. Thresholds were <1% evaluated-cohort errors, p99<
200 ms, dropped arrivals<0.1%, >=99.9% expected cohort arrivals and zero foreign-result detections.

The mixed fixture uses 256 BUSINESS tenants/32 authenticated identities, one project/two tasks per tenant:80% aggregate
lists,10% details,10% PATCH. Noisy traffic offers 9,000/s to one tenant and 1,000/s to 248 neighbor tenants whose
identities differ from the hot tenant. Tenant policy remains 100/s. Neighbor error/latency is evaluated separately; hot
throttling is expected. These tiny business records do not benchmark large-tenant queries or export contention.

| Run                                           | HTTP responses/s |           Successful business/s | Evaluated-cohort errors | All-response/cohort p99 ms | Dropped | Result        |
|-----------------------------------------------|-----------------:|--------------------------------:|------------------------:|---------------------------:|--------:|---------------|
| Initial mixed, host NodePort                  |         1,538.26 |                          521.58 |                  66.09% |                   3,004.44 | 159,969 | FAIL          |
| Private route, bounded admission              |         4,122.61 |                        1,043.31 |                  74.69% |                     692.87 | 105,083 | FAIL          |
| Mixed,1 ms group-commit trial                 |         8,954.25 |                        4,546.42 |                  49.23% |                     101.97 |  20,252 | FAIL          |
| Pre-release mixed repeat, preallocated client |         7,317.66 |                        2,547.50 |                  65.19% |                     192.25 |  52,408 | FAIL          |
| Noisy before group-commit trial               |         5,077.13 |   662.01 total /643.65 neighbor |         11.80% neighbor |            708.21 neighbor |  81,998 | FAIL          |
| Pre-release noisy, preallocated client        |         9,649.54 | 1,016.52 total /993.86 neighbor |        0.0151% neighbor |            155.21 neighbor |   6,674 | FAIL arrivals |
| Final image mixed 10,000/s                    |         2,104.72 |                          348.22 |                  83.46% |                     812.20 | 151,674 | FAIL          |
| Final image noisy 10,000/s                    |         4,309.19 |   581.31 total /565.90 neighbor |         21.59% neighbor |            559.76 neighbor |  95,782 | FAIL          |
| Final image mixed 1,000/s                     |           999.72 |                          992.67 |                  0.705% |                     143.38 |       0 | PASS          |
| Separate mixed 1,000/s diagnostic             |           999.68 |                          996.22 |                  0.347% |                     142.83 |       0 | PASS          |

The pre-release preallocated noisy run started193,367 HTTP calls, including 19,919 neighbor calls; three neighbor calls
failed. Error/latency criteria passed, but 6,674 dropped arrivals and insufficient hot/neighbor cohort starts invalidate
full-load qualification. That pre-release mixed repeat acknowledged 5,092 writes and observed 5,092 committed project
versions; the best trial acknowledged 9,061/observed 9,061. A preceding run had three commits without a client
acknowledgement; those ambiguous outcomes are retained. Aggregate version comparison is a sanity check, not per-request
causal proof. Successful-response p99 can differ from the all-response percentile (pre-release repeat227.48 ms
versus192.25 ms); fast 429 responses do not establish useful capacity.

Final-image confirmation was worse: mixed acknowledged 798 writes/observed 800 versions, noisy 1,336/1,338; each has two
ambiguous committed writes. Fifty-four mixed and 60 noisy responses were unexpected failures/timeouts. No pods
restarted. The 1k main phase acknowledged 1,985/observed 1,985, passed all thresholds and had zero drops; its separate
five-second 500-RPS warmup failed error/latency thresholds and is retained. These results do not establish cold-start
readiness. The host had about 2 GiB swap in use at final inspection, with unrelated services still running; this context
does not prove active swapping caused any particular request failure. No further tuning removed the observed local
variability.

A 3,000/s mixed repeat also failed (1,011.27 successful/s,p99 430.15 ms,6,989 drops). Best-run throughput is therefore
not a stable sustainable envelope. No 100,000-RPS or 99.99% claim is made. Hardware, routes and client allocations
changed across experiments; these are not controlled single-variable speedup claims.

The final local load profile uses two API replicas,2.5 CPU/1 GiB each,pool 30/min-idle2,28 business permits, private
PostgreSQL/Redis routes, G1 and a four-CPU/2 GiB Linux k6 client. Earlier experiments used 2 or 4 API replicas,
Serial/G1 and different client caps; each metadata file records the actual profile. The 11-CPU/18 GiB Mac and 11-vCPU/~
8.8 GiB VM also host unrelated services, API workers, database, broker and load client. Those services were left
running. CPU throttling, G1 pauses, saturated admission and PostgreSQL WALWrite/WalSync waits were observed. Runtime
samples are sparse and do not prove a single cause for all variance.

WAL timing in one pre-trial mixed run averaged4.608 ms per sync; the 1 ms commit-delay trial averaged3.403 ms. Other
overloaded phases reached 13–26 ms. The local profile keeps `fsync=on`, `synchronous_commit=on` and `fdatasync`. An idle
fsync-method check favored retaining fdatasync (~459 microseconds); it is not application throughput evidence. A real
RLS project-list plan was0.247 ms with seven shared-buffer hits, so speculative SQL rewriting was not justified. Source
changes reduce authorization round trips, unnecessary storage-counter writes, lease commits and dependency hairpins
without weakening isolation.

[All mixed results](../benchmarks/mixed-comparison.json), [best mixed summary](../benchmarks/groupcommit-mixed-10000-summary.json), [final-image mixed summary](../benchmarks/release-mixed-10000-summary.json), [final-image noisy summary](../benchmarks/release-noisy-10000-summary.json), [passing final1k summary](../benchmarks/release-mixed-1000-summary.json), [metadata](../benchmarks/release-mixed-10000-metadata.json), [runtime samples](../benchmarks/final-mixed-runtime.jsonl), [query plan](../benchmarks/mixed-list-query-plan.txt), [fsync check](../benchmarks/local-fsync.txt).
Warmups, every earlier failure and two generator launch errors remain. One `balanced-*` run used the wrong Service port
8080 (correct port 80), received no API traffic and is explicitly excluded as capacity evidence. The runner now
discovers the Service port and makes an authenticated probe before allocating load. Failed launcher attempts do not
acquire invented request counts.

Zero foreign-result counters in performance tests are not adversarial isolation proof: these fixtures supply no
foreign-ID probes. Independent API/SQL/cache/export tests provide that evidence. Dedicated load hardware, repeatable
storage latency, CPU/GC profiling under representative skew, longer soaks and measured queue lag are still required
before sizing a10k deployment.

## Current release confirmation

All **eight** drills pass on the 83-test `perf-release` image. Initial stale-plan-ticket and profiling-extension restore
failures remain under `verification-performance-*`; corrected results are under `verification-performance-fixed-*`. The
final 1k mixed phase passes at **999.59 successful RPS, zero errors/drops, p99 37.13 ms**, with 2,000
acknowledged/committed writes. Its separate warmup failed (7.213% errors, p99 475.59 ms, 80 drops), so cold-start
performance is unqualified. The latest shared-host 10k repeats fail: mixed **239.88/812.17** successful RPS, p99
**1,407.10/525.83 ms**; noisy **4.267%/5.622% neighbor errors**, p99 **349.65/419.39 ms**, with dropped arrivals in
every phase. These late runs had other projects active; they do not replace the approved isolated comparison.
See [complete conditions, measurements and interpretation](performance-regression.md#final-83-test-release-confirmation).
No 10k qualification is claimed.

## Latest local release drills

The executable suite is `python3 scripts/verify-local.py --label verification-performance-fixed`; results are
in [suite evidence](../benchmarks/verification-performance-fixed-suite.json). It runs workflows, quota recovery, a large
export, scratch restore and dependency/pod failures sequentially, never alongside a benchmark. Historical evidence below
remains useful but does not replace this suite.

| Latest drill                   | Observed result                                                                                                                                                                                            |
|--------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Compose + Kubernetes workflows | PASS: signed OIDC, two-tenant IDOR/header denial, tenant and explicit support exports, roles, lifecycle, plans and idempotency                                                                             |
| Quota recovery                 | PASS: duplicate grant reserved 4 once; assumed settlement kept reserved 4/confirmed 1/assumed 3; repeated late proof ended reserved 2/confirmed 2/assumed 0                                                |
| Large streaming artifact       | PASS: counts, every ownership marker, gzip CRC and compressed SHA-256                                                                                                                                      |
| Tenant-only restore            | PASS: all 17 table row digests match,5 export chunks and 2 ledger records, scratch dropped, live neighbor unchanged;13.657 s local restore phase, no DR RTO claim                                          |
| Kafka outage                   | PASS: project commit 0.016 s and report completed while broker stopped; publication recovered and exactly one projection. Zero failed dispatch attempts observed in this interval; no stronger retry claim |
| PostgreSQL outage              | PASS: API503 without cached data, liveness200/readiness503, recovery200                                                                                                                                    |
| API pod replacement            | PASS:30/30 calls,zero errors,desired readiness recovered                                                                                                                                                   |

The packed export verified100,000 tasks/2,001 projects,23 chunks,4,887,586 compressed/37,887,209 expanded bytes and
102,002 tenant ownership markers. Actual measured seed1.404 s,build including queue10.236 s,download plus
gzip/SHA/ownership validation0.163 s. These are one local artifact’s timings, not maximum-size or concurrent-export
SLOs. The earlier402-chunk artifact and failed per-row seed attempt remain recorded.

The first expanded restore detected that an insert trigger rewrote closed quota settlement timestamps. V17 fixes
normalization and enforces coherent OPEN/PROVEN/ASSUMED states. A new integration regression preserves all ledger fields
through a delete/reinsert round trip; the repeated scratch drill includes non-empty export chunks plus both
PROVEN/ASSUMED grants. [Failure evidence](../benchmarks/verification-streaming-restore-output-before-v17.txt) is
preserved; [Repeated successful evidence](../benchmarks/verification-streaming-restore.json) matches canonical row
digests across all 17 protected tables.

## Historical 1,000-RPS read workload

Thresholds were declared before the successful runs: HTTP errors <1%, all-response p99 <200 ms and dropped iterations <
0.1% of offered requests. All measured requests are retained without retries. The current workload has 32 BUSINESS
tenants, each with one project and two tasks, alternating two authenticated identities. Global scenario iteration round
robin prevents the original VU-local selection bias. Tenant rate policy remains 100/s. Latest runs use native k6 against
host-facing NodePort localhost:30088, two API replicas, 2 CPU / 1 GiB limit, pool 16 and admission 14 per pod with a 100
ms bounded wait.

| Run                                     | Seconds | HTTP requests | Successful RPS | HTTP 429 | Error rate | p50 / p95 / p99 ms           | Dropped | k6 exit |
|-----------------------------------------|--------:|--------------:|---------------:|---------:|-----------:|------------------------------|--------:|--------:|
| Original local overload, 8 tenants      |       5 |         4,990 |         207.93 |    3,942 |     79.00% | 16.62 / 89.78 / not exported |      12 |      99 |
| Budgeted, before bounded principal wait |      30 |        30,001 |         963.37 |    1,091 |      3.64% | 4.67 / 29.44 / 84.69         |       0 |      99 |
| Queued admission                        |      30 |        30,001 |         997.71 |       65 |     0.217% | 4.70 / 18.69 / 70.02         |       0 |       0 |
| Sustained confirmation                  |      60 |        60,000 |         997.29 |      154 |     0.257% | 5.42 / 21.98 / 74.19         |       0 |       0 |
| Final image and drain configuration     |      30 |        30,001 |         995.47 |      126 |     0.420% | 8.13 / 23.87 / 101.74        |       0 |       0 |

The last three runs pass all thresholds. Their remaining 429 s are classified as admission rejections; there were no
quota rejections or unexpected responses. This is approximately 1,000 **offered** RPS, with measured successful
throughput shown separately. Initial 8-tenant policy capacity was insufficient for 1,000/s, and resource limits changed
from 1 CPU / 768 MiB and pool 4. This is not a controlled single-variable speedup measurement.

[Machine-readable comparison](../benchmarks/throughput-comparison.json), [final raw summary](../benchmarks/release-nodeport-1000rps-summary.json), [final output](../benchmarks/release-nodeport-1000rps-output.txt), [final metadata](../benchmarks/release-nodeport-1000rps-metadata.json), [60-second summary](../benchmarks/sustained-nodeport-1000rps-summary.json).
Latest separate 500-RPS warm-up phases also passed; raw warm-up artifacts and all earlier failures remain. Warm-up
network errors for these three runs were empty.

An internal ClusterIP run before the budget optimization also passed: 999.17 successful/s, p99 77.62 ms, zero HTTP
failures, 22 dropped/30,000 offered. This is a different network route; it is not substituted for the NodePort results.
Earlier NodePort/cold-start/underprovisioned runs failed and remain preserved. Runtime samples showed WAL waits,
admission contention and CPU throttling; virtual-thread pinning was not established as the
cause. [Protocol, resource changes and trade-offs](throughput-hardening.md).

No foreign-resource IDs are supplied in these performance fixtures. A zero leakage counter here is not isolation proof;
independent adversarial tests provide that evidence. A tiny warmed read dataset does not validate large tables,
write-heavy workloads, 10k/100k RPS, prolonged soak or regional p99/99.99% availability.

## Historical local workflows and failure drills

* **Full two-tenant workflows:** signed Keycloak authentication, forged JWT401, foreign ID404, forged selector403,
  provisioning, projects/tasks, exports, suspension/reactivation, idempotent replay, stale If-Match412, reports, feature
  versions, logical storage, billing history and period cancellation passed through
  both [Compose](../benchmarks/verification-throughput-compose.json)
  and [Kubernetes](../benchmarks/verification-throughput-k8s.json).
* **Kafka outage:** stopped only this project's broker, committed a project in 0.009 seconds and completed a report
  while it was down, restarted in finally, then observed publication and exactly one projection. Zero dispatch attempts
  were captured during this particular outage interval; no stronger in-outage-dispatch claim is made. Unit/integration
  tests independently verify durable failed dispatch
  attempts. [Evidence](../benchmarks/verification-throughput-kafka.json).
* **PostgreSQL outage:** stopping this project's database initially exposed a rollback exception mapped to 500. The
  handler and regression test now cover TransactionSystemException. The repeated drill verified503 with no cached tenant
  data, liveness200, readiness503 and recovery200 after restart. The database is always restarted in
  finally. [Evidence](../benchmarks/verification-throughput-postgres.json).
* **API pod termination:** the first repeat had 28/30 successes. A five-second preStop drain, 45-second termination
  grace and 20-second Spring shutdown phase limit were then added. Two subsequent drills each had 30/30 successes and
  zero errors; desired two-replica readiness
  recovered. [Before drain](../benchmarks/verification-throughput-pod-before-drain.json), [after drain](../benchmarks/verification-throughput-pod.json), [repeat](../benchmarks/verification-throughput-pod-repeat.json).
  This is not node/AZ failure coverage.
* **Tenant-only restore:** an authenticated platform suspension fenced a synthetic source tenant. An exported consistent
  snapshot supplied metadata and a runtime-role RLS dump of all 16 protected tables, including a non-empty api_budgets
  ledger. Restored only into a generated disposable database with RLS/FKs enabled; temporarily disabled only scratch
  derived-accounting/cache-epoch triggers to avoid double accounting. Scoped table counts and canonical row-content
  SHA-256 digests matched, no-context/neighbor reads returned zero, the source neighbor remained unchanged and scratch
  was dropped. Measured local drill time 7.982 seconds is not a DR
  RTO. [Evidence](../benchmarks/verification-throughput-restore.json).

Historical runtime evidence records ten successful Flyway versions, NOSUPERUSER/NOBYPASSRLS runtime role, image identity
and two healthy local replicas with zero restarts. Compose is localhost:8088; Kubernetes is localhost:30088.
Dependencies/workers stay in this project's Compose environment. No remote deployment or production secrets/certificates
were configured.

## Reproduce

From `multi-tenant-saas`:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -q verify
node --test frontend-tests/session.test.cjs
docker compose up --build -d
python3 scripts/local-dependencies.py
kubectl --context orbstack apply -f k8s/local-orbstack.yaml
kubectl --context orbstack -n saas-isolation-dev rollout restart deployment/saas
kubectl --context orbstack -n saas-isolation-dev rollout status deployment/saas --timeout=120 s
python3 scripts/demo.py
BASE_URL=http://localhost:30088 python3 scripts/demo.py
python3 scripts/local-load.py --native --seed --tenants 32 --warmup-seconds 20 --duration 30 --label local-confirmation-1000rps
# Run failures separately, after benchmarks:
python3 scripts/kafka-failure.py
python3 scripts/database-failure.py
python3 scripts/restore-drill.py
python3 scripts/k8s-smoke.py
```

Native load execution requires installed k6; omit --native to use an existing local k6 Docker image. Restore never
imports into live tenant data. Review [remaining production gates](limitations.md), including stable10kcapacity,
commercial invoice policy/TLS/ACL wiring, object-store/range exports, dedicated/region routing, replica/DR promotion,
full 35-scenario chaos and independent security review.

## Final CPU scheduling and runtime verification

Local API CPU requests now match their2.5-core limits (two replicas); cgroup weight changed35→208. Final fresh-clone
mixed trials achieved4,200.83/4,042.56 successful RPS,51.252%/52.193% errors,p99 149.27/137.21 ms,27,216/21,394 drops.
Noisy neighbors:0.887%/4.260% errors,p99 190.71/366.19 ms,22,699/35,288 total drops. **All fail full10k qualification.**
Lower external CPU confounds speedup attribution; the scheduling weight change is directly measured.
See [full report](performance-regression.md#cpu-request-correction-and-final-configuration).

The executable read-only `scripts/verify-runtime.py` passed: identical tested JAR hashes in Compose/two ready APIs, safe
non-owner role,17 exact forced RLS policies with checks, zero no-context rows, migrations through17, durable WAL,
restored live routing, no remaining trial/restore DBs,22 recorded surviving containers running/five removed/zero
paused. [Runtime evidence](../benchmarks/verification-performance-runtime.json). This does not assert capacity or
production certification.

Final request-profile 1k check (`reserved-cpu-1000`): **PASS**,999.73 successful RPS,20,000 successes,zero
errors/drops,p99 21.88 ms,2,000 acknowledged/committed writes. Its separate60-second warmup **FAILS**,3.153%errors,p99
341.95 ms,one drop. This is warm-phase evidence
only. [Main](../benchmarks/reserved-cpu-1000-summary.json), [warmup](../benchmarks/reserved-cpu-1000-warmup-summary.json).
No retries or failed evidence were removed.
