# Deployment and operational runbook

Execution update: [current durable workflows and controls](implementation-update.md) supersedes the initial
worker/cache/billing limitations below. Current deployment gaps are tracked in [limitations](limitations.md).

Local: README commands start real PostgreSQL17/Redis7/Kafka3/Keycloak26. Testcontainers creates an independent real
PostgreSQL17 database. For Docker socket discovery, set DOCKER_HOST to your local Docker context if necessary. The repo
is a collection of independent services; use only this project's Compose file. No remote deployment has occurred.

Production preparation: build/tag/sign a pinned image, scan resolved dependencies, create managed multi-AZ services and
TLS truststores, run Flyway separately with owner credentials, then deploy runtime with `SPRING_FLYWAY_ENABLED=false`.
Owner credentials must not be injected into application pods. `k8s/platform.yaml` is a starting manifest; replace
placeholder image/endpoints, create saas-secrets from a secret manager, install gateway TLS/authenticated metric
scraping and customize NetworkPolicy. The repository-root `.github/workflows/multi-tenant-saas.yml` runs path-filtered
verification and image builds for this module. The nested workflow is a portable template.

Readiness includes database health; liveness excludes dependency health. Kubernetes probes use a separate management
HTTP connector on 8081 and private service saas-management, keeping saturated business request threads from blocking
probes. Public probe output exposes no credentials. Prometheus endpoint requires a valid OIDC bearer; scraping token
renewal and restricted service identity are operator responsibilities. `observability` includes scrape/alerts/dashboard
examples; not a fully deployed monitoring stack. OTLP tracing is supplied by Micrometer's bridge; configure exporter
endpoint/credentials and trace sampling in the environment. No tenant-ID metric labels exist. Durable audit contains
actor/tenant/action/resource/correlation/time; failed access attempts use bounded counters rather than a complete
denied-request audit ledger.

Investigate isolation alerts: disable affected route or suspend tenant, preserve audit/DB snapshot, validate runtime
pg_roles, inspect pg_class/pg_policies and deployed migration checksum, reproduce with the adversarial suite using safe
data, rotate compromised credentials, then restore service only after independent verification. Never fix availability
by granting BYPASSRLS or disabling policies.

Database incident: fail closed with 5xx; inspect Hikari/DB health, wait for managed failover, revalidate
schema/policies/role before admitting writes. Transaction errors roll back data/audit/outbox atomically. Retry only
client operations known idempotent; project/task/report creates support tenant/actor/route/payload-bound Idempotency-Key
for seven days; other endpoints have their documented semantics. Redis outage falls back to RLS-authorized database
rows. Kafka outage retains outbox rows and attempts; after recovery inspect attempts>=5, validate event origin and reset
attempts to 0 in a scoped audited operator transaction. Replay duplicate event IDs is safe due to inbox; avoid replay
after data changes that require new aggregate versions. Outbox currently retains all events; archive/retention must
preserve deduplication horizons.

Worker incident: inspect jobs state, failure_code and audit. EXPORT jobs now publish bounded gzip chunks and a manifest.
Defaults: 1 GiB compressed,120 seconds generation,24-hour retention. Claims durably increment attempts before execution;
fencing tokens prevent stale completion and unexpected failures record bounded backoff in a separate transaction. Export
chunks are RLS-protected; use the authenticated `/api/v1/jobs/{id}/content` route and verify manifest SHA-256/gzip CRC.
Support downloads must use explicit audited admin routes. No Range/resume is exposed.

Suspension: platform API waits for current tenant lock then revokes subsequent boundaries. Existing responses can
complete afterward if their transaction committed first; the guarantee begins at successful suspension commit. Deletion:
owner request -> 30-day retention -> legal-hold check -> purge business rows, retain audit/control tombstones. For
emergency legal hold use restricted reviewed DBA SQL with the exclusive tenant advisory fence acquired before metadata
rows; unfenced concurrent changes fail55P03. No arbitrary tenant bypass exists in app APIs.

Backups: encrypt, restrict restore credentials and rehearse. Restore one tenant into scratch isolated DB and reconcile
under a tenant write fence; preserve other tenants. Record achieved RPO/RTO. Kubernetes PDB/HPA does not establish
99.99% availability. Drain pods gracefully, run chaos exercises, measure real regional failure behavior before setting
SLO commitments.

## Local hardening checks

Use `python3 scripts/database-failure.py` to stop/restart only this project’s PostgreSQL and verify503 / no cached
tenant response, healthy liveness and failed readiness, then recovery. Use `python3 scripts/restore-drill.py` for a
synthetic two-tenant consistent snapshot/scratch restore; it never imports into live data. Run fault drills after
performance tests, not concurrently.

Request-credit budgets are enabled by `SAAS_QUOTAS_LEASED_REQUESTS=true` (legacy `SAAS_QUOTAS_LEASED_READS` fallback) in
local Kubernetes and the production profile, default false in Compose. Monitor saas_api_budget_total, pending_returns,
cached_tenants, quota_rejected and Hikari/HTTP metrics with bounded labels. Never auto-refund an unclosed grant: a
crashed owner may have consumed its credits. Compare confirmed, reserved, assumed and indeterminate usage, preserve
ledger evidence and use an audited billing reconciliation process. Graceful shutdown returns known unused credits;
indeterminate RPC acknowledgments can strand additional grants. To use exact per-request metering, disable the flag;
outstanding grants stay conservatively reserved. See [protocol and trade-offs](throughput-hardening.md).

Production rollout requires current migrations run by the owner separately, security guard success on every
writer/replica, trusted TLS endpoints and non-demo secrets. The guard fails startup rather than silently accepting
insecure fallbacks. No cloud/provider setup was requested or applied.

Quota incident: platform admins preview and POST `/api/v1/admin/tenants/{id}/quota/reconciliation`. This settles up
to1,000 aged grants as ASSUMED and refunds zero uncertain credits. A later trusted owner proof corrects unused credits
once. Preserve ASSUMED ledger/audit evidence; do not manually decrement usage counters or treat assumptions as invoices.
`scripts/quota-reconciliation.py` verifies orphan/replay/late-proof behavior locally. Grant size1–16 trades ledger
writes against conservative stranded reservations. Exact mode disables leases for subsequent requests without discarding
existing grants.

Large export drill: `BASE_URL=http://localhost:30088 python3 scripts/large-export.py`. It creates a new synthetic
ENTERPRISE tenant, seeds100,000tasks/2,001projects, streams to a protected temporary file, checks SHA/gzip CRC and every
tenant ownership marker, then removes the temporary download. Tenant fixtures remain available for inspection. The
browser streams to a chosen file when supported; fallback buffers at most32MiB. For curl, write the authenticated
download directly to a file and compare `sha256sum` with the manifest; never place tokens into persisted reports.

## Repeatable local performance diagnostics

Follow [the investigation protocol](performance-regression.md). Do not overlap fault drills, exports or another load
client. Use fresh labels and synthetic-only clones; never clone/delete live `saas` for tuning.
`repeatable-performance.py` restores the original deployment/Compose worker in `finally`; an interrupted script still
requires manual image/DB/Redis/pool/worker verification. Existing `saas_perf_seed` and the isolated performance Redis
contain only test fixtures. Inspect condition files for `live_deployment_restored`. Nested SQL statistics and JFR add
overhead; use explicit per-clone `--sql-tracking none` for unprofiled comparisons. Turning off `synchronous_commit` or
RLS is prohibited as a throughput workaround.

The current default local manifest uses two 2.5-CPU / 1-GiB APIs, pool 30 and 28 global permits. The 3-CPU/pool-48
override reduced rejections but failed latency and arrivals; do not apply it as a validated production sizing
recommendation. Monitor `saas.api.budget.grant.rpc`, `saas.api.budget.credits.granted`, singleton/batch results, and
`saas.ingress.pre_auth.rejected`. No tenant labels are added. `saas.observability.security-filter-spans=true` restores
diagnostic filter spans, with overhead; authentication/authorization and outer HTTP observations remain enabled by
default.

After any explicitly authorized pause, inspect every recorded ID, retry failed unpause operations, distinguish removed
containers from Docker inspection errors, and verify no remaining paused state. The helper refuses to mark the window
closed on unverified restoration. Never recreate containers another operator removed or unpause unrelated IDs not in the
recorded set. The completed window's final state is in `benchmarks/isolation-final-state.json`.

Final local scheduling correction: each API now requests 2.5 CPU cores to match its unchanged limit. The measured cgroup
weight rose from35 to208. Two replicas request five cores total, plus2.5 for a rolling surge; these are scheduler
requests, not dedicated CPUs. Earlier benchmarks used250m requests. Current mixed/noisy confirmations still fail full10k
criteria; [results and confounders](performance-regression.md#cpu-request-correction-and-final-configuration).
