# Multi-tenant SaaS — Java 21 / Spring Boot / PostgreSQL RLS

Runnable project/task SaaS reference implementation with an OIDC browser application, explicit platform administration,
transaction-bound RLS, Redis caching, Kafka outbox/inbox, quotas, exports and lifecycle management.
**Production-oriented implementation, not a certified or capacity-validated production service.**
Read [limitations](docs/limitations.md) before deployment.

## Run

```sh
cd multi-tenant-saas
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; select JDK 21 equivalently elsewhere
mvn verify                                # Docker required; PostgreSQL Testcontainers
# Entire local stack (uses demo-only passwords):
docker compose up --build -d
# Or run infrastructure and backend separately:
docker compose up -d postgres redis keycloak kafka
SERVER_PORT=8088 MESSAGING_ENABLED=true java -jar target/multi-tenant-saas-1.0.0.jar
python3 scripts/demo.py
```

Open http://localhost:8088. Login as `alice`, `bob`, or `platform`, password `demo-password`. The platform identity can
provision, inspect, suspend and reactivate tenants. Register as Alice/Bob in the Platform tab (registration itself
requires only authentication), then sign in as platform to provision the returned tenant IDs. Sign out ends both the
local and Keycloak session. UI uses authorization code with PKCE; the demo script uses a development-only password
grant. Ordinary users cannot list platform tenants or provision them. Tenant switching selects a membership and every
request revalidates it. The invitation form accepts its tenant UUID even before membership exists.

Local dependency ports: PostgreSQL 5558, Redis 6388, Keycloak 8188, Kafka 9099, backend 8088 (container 8080).
PostgreSQL data is persistent in the project volume. Avoid deleting that volume unless intentionally resetting all demo
data.

## Structure

* `src/main/java/com/example/saas`: control-plane lifecycle/membership APIs, tenant transaction boundary, business APIs,
  cache, workers, billing sandbox, runtime security guard.
* `src/main/resources/db/migration`: schema, tenant-aware constraints, forced RLS, quotas.
* `src/main/resources/static`: tenant workspace, tenant admin and platform admin UI.
* `src/test`: real PostgreSQL adversarial integration tests and cache failure tests.
* `docker`, `docker-compose.yml`, `Dockerfile`: reproducible local environment.
* `k8s`, `observability`, `.github/workflows`: deployment/monitoring examples and CI template.
* `docs`: architecture, security, API contract, operations, review, failure analysis and verification.

## Quota recovery and streaming exports

Request grants are bounded at 1–16 credits with stable replay IDs, fresh membership checks and conservative hard-cap
accounting. A lost owner never receives an automatic refund: audited settlement reports ASSUMED usage separately, and
late positive unused-credit proof corrects it once. Platform admins can preview/settle through the UI or
`/api/v1/admin/tenants/{id}/quota/reconciliation`.

EXPORT jobs now return a gzip artifact manifest and stream content from RLS-protected PostgreSQL chunks, with
lease-fenced publication and live authorization at each download chunk. Defaults:1 GiB compressed,120 s generation,24h
retention. The tenant and explicit support portals provide
downloads. [Protocol, API changes and trade-offs](docs/reconciliation-and-streaming.md).

```sh
# After Compose + local Kubernetes are ready; creates only synthetic local tenants:
python3 scripts/verify-local.py
# Or individually:
python3 scripts/quota-reconciliation.py
BASE_URL=http://localhost:30088 python3 scripts/large-export.py
# Never overlap fault drills with benchmarks:
python3 scripts/mixed-load.py --docker --seed --rps 10000 --mode mixed --label my-mixed-10000
python3 scripts/mixed-load.py --docker --rps 10000 --mode noisy --label my-noisy-10000
python3 scripts/summarize-mixed.py
```

Use a new benchmark label for every run. Docker mode discovers the local Service address/port, probes it with
authentication and stores credentials only in protected temporary files. The selected target remains local.

## Verification and current limits

**83 Java tests, 6 frontend tests, 3 benchmark-restoration tests and all 8 local release drills passed.** Drills cover
Compose/Kubernetes tenant workflows, quota reconciliation, a 100,000-task streaming export, tenant-only scratch restore,
Kafka/PostgreSQL recovery and pod
replacement. [Executed checks](docs/verification.md), [runtime/image/RLS evidence](benchmarks/verification-performance-runtime.json).

The quota cold-arrival batching cliff is removed; expiry returns are coalesced with replacement; admission locks are
separated; tenant permits follow fresh membership; stale plan tickets retry before business work. A bounded denial-only
token fingerprint guard avoids repeated authentication for exhausted known pairs. PostgreSQL FORCE RLS, fresh membership
and durable WAL remain authoritative.

The final warm 1,000-RPS mixed phase passed: **999.73 successful RPS, zero errors/drops, p99 21.88 ms**, with 2,000
acknowledged / committed writes. Its separate warmup failed (3.153% errors, p99 341.95 ms); cold-start performance
remains unqualified. [Summary](benchmarks/reserved-cpu-1000-summary.json).

**The 10,000-RPS gate remains failed.** The approved isolated comparison reproduced 4,490–5,235 successful mixed RPS on
the retained old slow image, versus 4,802–5,042 on the profiled build. A wider pool reached 5,201–5,413 but failed
latency/arrivals. The final 83-test build with corrected CPU requests reached 4,043–4,201 successful mixed RPS with
51–52% errors; noisy neighbors had 0.887% / 4.260% errors, p99 190.71 / 366.19 ms and dropped arrivals. Earlier poor
repeats and failed warmups remain preserved. These short trials do not establish sustainable
capacity. [WAL/CPU investigation, all results and commands](docs/performance-regression.md).

The final local Kubernetes manifest has two 2.5-CPU / 1-GiB API limits, pool 30 / global admission 28. Each now requests
2.5 CPU, with measured cgroup weight 35→208. Aggregate CPU requests are five cores, plus 2.5 for rolling surge and
dependency/system capacity; smaller nodes need explicit resizing. Higher requests change scheduling weight, not physical
CPU/storage isolation. The bounded other-project pause ended; all 22 surviving recorded containers are running, five old
IDs no longer exist and none is paused.

The 100k tenants/10M users/100k RPS/p99 200 ms / 99.99% figures remain architectural targets. Long soaks, dedicated
client/storage, realistic tenant-size skew, sustained broker drain and regional resilience are unqualified. The selected
target is local; no cloud deployment, production SLO or certification is
claimed. [Remaining gates](docs/limitations.md).

## Local Kubernetes and diagnostics

```sh
python3 scripts/local-dependencies.py
kubectl --context orbstack apply -f k8s/local-orbstack.yaml
# After rebuilding a mutable local image:
kubectl --context orbstack -n saas-isolation-dev rollout restart deployment/saas
kubectl --context orbstack -n saas-isolation-dev rollout status deployment/saas
# New labels preserve earlier evidence:
python3 scripts/verify-local.py --label my-release
python3 scripts/verify-runtime.py --label my-runtime
```

Open http://localhost:30088; keep Compose dependencies/workers running. Never overlap fault drills and benchmarks. Use
the fresh synthetic database/cache protocol in the [performance report](docs/performance-regression.md#commands),
recording immutable images, warmup, CPU requests/limits and profiler settings. Foreground cloned trials stop this app's
worker and do not measure Kafka throughput. Transport calibration uses fake data/authentication and cannot prove SaaS
capacity.

Boot 3.5.16 manages the dependency versions; Java 21
compatibility: [official requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html). Run
`mvn dependency:tree` for resolved artifacts. PostgreSQL
references: [RLS](https://www.postgresql.org/docs/17/ddl-rowsecurity.html), [transaction settings](https://www.postgresql.org/docs/17/functions-admin.html).
More workflow
detail: [implementation update](docs/implementation-update.md), [quota/export protocol](docs/reconciliation-and-streaming.md), [metering trade-offs](docs/throughput-hardening.md).
Frontend tests: `node --test frontend-tests/session.test.cjs`.
