# Remaining production gates

The selected deployment target is local Compose/OrbStack.
The [WAL/CPU investigation and isolated repeats](performance-regression.md) document the current failed 10k gate.
Current implemented controls and measured verification are documented
in [throughput hardening](throughput-hardening.md), [implementation update](implementation-update.md)
and [verification](verification.md). A passing build, local recovery drills and short benchmarks do not certify
production readiness.

* Warm read-only 1,000-RPS NodePort runs passed historically. Mixed and noisy-neighbor 10,000-RPS tests are now executed
  and still fail full arrival/capacity criteria. Best and repeated results, including every failure and generator setup
  error, are in verification; do not infer sustainable capacity from the best short run. Neighbor error/latency
  improved, but dropped arrivals still invalidate full-load fairness qualification. This VM shares CPU/memory/storage
  with unrelated services and the client. Prolonged soak, dedicated-generator measurements,100,000 RPS and 99.99% remain
  unverified. Shared database/WAL/CPU are not perfect performance isolation.
* Optional request budgets reserve1–16 credits for reads and writes under the hard cap. Confirmed attempts include later
  business rollback/error. Unknown credits stay reserved and can be settled as ASSUMED without refund; only later
  positive unused-credit proof corrects them once. Ordinarily up to 15 credits per live grant can be stranded, and lost
  acknowledgments may strand more full grants. The commercial customer/invoice/dispute policy is still required. Logical
  storage excludes indexes/WAL/audit/events/replicas/backups; Redis caps do not replace infrastructure memory controls.
* Large streaming exports are implemented using RLS-protected PostgreSQL gzip chunks. A 100,000-task/2,001-project
  artifact is verified. Default bounds are1 GiB compressed,120 s generation and 24h retention. Download checks current
  membership/state/expiry per chunk and bounds concurrency, but already-sent bytes cannot be revoked. Exports cover the
  four project-domain tables, not a complete GDPR subject/tenant portability package. There is no Range/resume, S3
  adapter or perfect socket bandwidth isolation. Long snapshots retain MVCC versions. The browser fallback is capped at
  32 MiB; use supported streaming-file access or an authenticated API client for larger artifacts. Search remains scoped
  SQL without a separate search index; recurring customer-configured reports are absent.
* A tenant-only consistent snapshot/scratch restore drill is implemented. Live tenant restore orchestration,
  dedicated-database routing, online migration, replica failover, data residency enforcement and regional failover are
  not deployed. A two-replica single-node OrbStack deployment is not multi-AZ. Disaster RPO/RTO remain unmeasured; local
  scratch restore time is not a DR commitment. Background scans are bounded/rotating but not a partitioned100,000-tenant
  scheduler.
* OIDC accepts one issuer. Immediate global identity-provider account revocation needs introspection/revocation design;
  live tenant membership/state checks are fresh on every call. Frontend session logic has Node tests; interactive
  browser/visual QA remains unavailable. sessionStorage tokens remain vulnerable to same-origin XSS. Invitations target
  existing OIDC subject IDs and are not emailed.
* Tenant RBAC/ownership transfer are implemented; projects are shared among active members by role, with no
  private-project ACL/custom permission catalog. Idempotency covers project/task/report creates for seven days, not
  every mutation. Optional project If-Match guards stale updates; tasks remain last-write-wins. The OpenAPI document
  intentionally uses generic JDBC response maps rather than a generated typed SDK.
* Billing remains a sandbox without raw cards/commercial provider or financial certification. Webhooks now reject
  conflicting reused IDs and ignore stale semantic events; provider-specific ordering/dispute semantics are absent.
  Production disables sandbox plan activation, so a real commercial provisioning/billing adapter is still required for
  paid plan changes. Calendar months define usage periods; committed background work can finish after subscription
  restrictions.
* RuntimeGuard checks 17 protected tables and 12 statement accounting triggers, exact policies, FORCE/ENABLE, role
  privileges/ownership and required fence/epoch triggers. Control-plane tables require trusted reviewed scoping.
  Compromised application credentials/code can set custom GUCs; arbitrary SQL/code execution is outside the
  accidental-predicate-omission threat model. Upgrade pre-fence writers under draining/maintenance. Future read replicas
  require the same role/policy/schema verification before routing.
* Deletion retains immutable audit/billing and control tombstones under retention/legal hold. This does not prove GDPR
  erasure from backups/audit. WORM export, backup expiry, customer-managed keys and independent privileged-access
  evidence remain deployment responsibilities. Timing/resource side channels are not eliminated.
* ProductionGuard rejects demo credentials, insecure OIDC/DB/Redis/Kafka options, runtime migrations and sandbox
  activation. Actual production CA trust, service ACLs, secrets, production OIDC client, certificates, TLS gateway,
  collectors and network-policy enforcement require a target environment and validation. Compose uses loopback-only
  development ports/demo credentials. Monitoring templates are not a deployed observability service.
* Full 35-scenario infrastructure chaos coverage, regional/migration/replica drills, supply-chain scanning and
  independent penetration testing remain outstanding. Test and local outage success do not establish certification or
  production SLOs.

The learned denial guard stores token fingerprints, never authentication outcomes; all forwarded requests still validate
JWT and live membership. Unknown/rotated credentials and selector churn can bypass this CPU optimization. Its bounded
capacity fails back to normal authentication. Individual security-filter observations default off, with authn/authz/HTTP
observations retained. Foreground performance trials pause this app's worker and do not measure sustained Kafka/outbox
drain. The client itself missed the strict arrival threshold against a mock echo service; independent load
infrastructure remains a verification gate. No performance test supplies foreign-ID attacks, so its zero leak counter is
not independent isolation evidence.
