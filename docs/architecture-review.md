# Mandatory architecture review

## 1. What is multi-tenancy?

Independent customer organizations share software/infrastructure while resource access and policy remain tenant scoped.
Sharing improves utilization but increases the blast radius of authorization mistakes; tenant boundaries must be
verified at every supported interface.

## 2. How do tenancy models differ?

Shared schema shares tables and uses RLS, schema-per-tenant separates namespaces, database-per-tenant separates database
operations and restore boundaries. Increasing isolation generally raises pool/migration/fleet cost; schema separation
alone does not isolate CPU or credentials.

## 3. When hybrid?

Use shared infrastructure for ordinary tenants and dedicated placement for hot workloads, residency or contractual
isolation. Require authoritative placement epochs and tested migration/fencing; generic routing without these can create
dual writers.

## 4. Secure active tenant identification?

Validate JWT issuer/signature/audience/expiry, resolve the requested selector and check live ACTIVE tenant membership.
Build immutable context only inside that verified boundary; revalidation trades database load for revocation freshness.

## 5. Why not X-Tenant-ID alone?

Any client can forge a header. It selects the requested organization but cannot grant access; missing/revoked membership
returns 403 regardless of a valid global identity.

## 6. Multiple memberships?

Store a global OIDC subject separately from (tenant,subject,role,active) memberships. Binding identity permanently to
one tenant prevents legitimate collaboration; trusting all memberships in an old token makes revocation stale.

## 7. Secure tenant switching?

Keep the same global token and change the selector. Recheck membership/state and any signed tenant restriction on each
request; the UI selection is not authority and no shared server session tenant can be overwritten.

## 8. Prevent IDOR/BOLA?

Resolve IDs only under authorized tenant transactions, forced RLS and composite FKs. Foreign resources return 404 within
a valid tenant; distinguish selector denial (403) without disclosing object metadata. UUID unpredictability is
insufficient.

## 9. How does RLS work?

PostgreSQL applies USING to visible rows and WITH CHECK to newly written rows under the runtime role. A LOCAL tenant
UUID drives policies, including native/bulk SQL; privileged roles and policy drift remain risks.

## 10. Why FORCE RLS?

Table owners normally bypass ordinary RLS. FORCE subjects owners to policies too, but superuser/BYPASSRLS still bypass.
Separate migration ownership from runtime and test role flags and DDL denial.

## 11. Role bypasses?

Superuser and BYPASSRLS can bypass; owners can alter policy configuration even with FORCE. Runtime must own no protected
table, inherit no dangerous ownership role and have no TRUNCATE/DDL privileges. Startup guards complement catalog
reviews.

## 12. SET LOCAL and transactions?

Parameterized set_config(...,true) persists only in the current transaction. It must execute after BEGIN on exactly the
connection used by repositories; session SET or a second pooled connection breaks the contract.

## 13. Prevent pooled connection leakage?

Use transaction-local settings and Spring-bound JdbcTemplate operations, not manual unrelated connections. Verify
alternating tenants and missing-context reads after commit/rollback; connection pools alone do not clear arbitrary
session state.

## 14. REQUIRES_NEW?

Spring suspends the outer connection and acquires another. Initialize the new transaction independently; after it ends
the original connection resumes its original scope. Nested calls consume additional pool slots and can deadlock if
unbounded.

## 15. Why not Hibernate filters alone?

Filters depend on ORM activation and may miss native SQL, bulk paths or developer omissions. PostgreSQL RLS remains
authoritative. JDBC was chosen here to expose connection/transaction binding directly.

## 16. Native SQL and bulk protection?

Execute both as runtime under the same LOCAL setting and forced RLS. Test omitted predicates and cross-tenant
UPDATE/DELETE. No stored procedure or privileged datasource may silently bypass the policy.

## 17. Tenant-aware foreign keys?

Use composite PK/unique keys and FKs containing tenant_id plus resource/subject ID. A globally unique UUID does not
validate tenant relationship ownership; constraint error text must not leak foreign row details.

## 18. Redis isolation?

Authorize first and use tenant/resource/version namespaces with tenant envelope validation, bounded TTL and safe JSON
parsing. Cache outage falls back to authorized SQL; physical per-tenant memory fairness still needs controls.

## 19. Cache collisions?

Use typed UUID components and fixed separators, include tenant and version, and validate envelopes on reads. Tests use
overlapping resource IDs. Hashing arbitrary ambiguous strings or relying only on global ID uniqueness is insufficient.

## 20. Kafka context?

Carry tenant/event/aggregate/version/type/schema/correlation/time in the envelope. Match against a committed local
outbox row before reconstructing context and deduplicate inbox writes. Broker credentials/ACLs are still required in
deployment.

## 21. Background jobs?

Persist tenant ID with each job, revalidate ACTIVE state, initialize a fresh transaction and claim only own RLS rows.
Bounded batches and idempotent result/audit commits prevent duplicates; claim attempts, lease tokens and bounded retry
delays commit separately, so poison failures cannot reset their attempts.

## 22. ThreadLocal leakage?

Avoid it: this implementation passes immutable context explicitly. If legacy integrations require ThreadLocal,
restore/clear in finally and test reused executors. Propagating a value never establishes receiver authorization.

## 23. Virtual threads?

They reduce blocking thread cost but do not increase database capacity or propagate trust. Explicit context and
transaction-scoped connections remain necessary; ingress queues and pools still need bounds.

## 24. Tenant RBAC?

Resolve role from current membership inside the tenant boundary: viewers read, members write, admins manage
members/configuration, owners manage plans/deletion. Resource-specific private project ACLs are not implemented and
require additional rules if introduced.

## 25. Platform vs tenant admins?

PLATFORM_ADMIN is an IdP realm role checked only on explicit platform routes. It does not silently become tenant
membership; emergency exports run through an audited support path with the real actor.

## 26. Safe provisioning?

Serialize by unique slug and tenant lock, atomically initialize membership/usage/event then ACTIVE. Duplicate requests
converge. External resource provisioning will require durable step journals and compensation beyond the current
single-database transaction.

## 27. Deactivate/delete?

Commit SUSPENDED to deny new boundaries; owner deletion enters DEPROVISIONING with retention/legal-hold checks, disables
access and purges live business rows later. Audit/backups/control tombstones persist under policy; immediate total
erasure is not promised.

## 28. Concurrent quotas?

Reserve API usage in short atomic transactions before business work; optional durable grants reserve up to sixteen
request credits conservatively. Fresh authorization remains mandatory for each credit. Project/seat/job limits use
exclusive fenced transactions. Plan downgrade blocks further additions rather than deleting data. Unknown usage after a
budget-owner crash remains reserved; audited conservative settlement separates assumed from confirmed consumption, and
late proof can correct it once. Commercial invoice policy remains external.

## 29. Noisy neighbors?

Tenant rate windows, finite pools, SQL/transaction timeouts, bounded worker batches and Redis maxmemory limit harm. They
cannot ensure perfect latency isolation on shared CPU/I/O; admission queues and dedicated hot-tenant placement are
needed at scale.

## 30. Plan entitlements?

Central plans table supplies limits/features and tenant configuration may disable exports. All export paths evaluate
both. Versioned tenant feature flags support deterministic subject percentage rollout without bypassing plan defaults;
scattered plan conditionals are avoided.

## 31. Subscription billing?

Sandbox plan/status APIs and timestamped signed deduplicated callbacks demonstrate integration boundaries without card
storage. Trial expiry, cancellation, immutable history and subscription write restrictions are implemented. Semantic
webhook fingerprints reject conflicting IDs and stale events cannot undo newer restrictions. A real provider still needs
ledger reconciliation; the sandbox is not financial compliance evidence and plan activation is disabled in production.

## 32. Privileged audit?

Append tenant/system and platform actor/action/resource/time/correlation in the relevant transaction. Runtime cannot
UPDATE/DELETE audit. Owner/DBA tampering requires external immutable export and access evidence; append-only grants are
not cryptographic tamper proofing.

## 33. Tenant-specific export?

Authorized admins enqueue an own-tenant durable job, worker revalidates ACTIVE state and reads RLS tables. The worker
streams a consistent cursor snapshot through gzip into bounded RLS-protected PostgreSQL chunks. Publication is
lease-fenced and downloads revalidate live authorization per chunk. Default bounds are 1 GiB compressed, 120 seconds
generation and 24-hour retention. Object-store/range support remains future work.

## 34. Restore one tenant?

Restore shared backup to an isolated scratch database, export/verify the one tenant, fence its live writes and reconcile
data/event epochs before import. The local drill executes a consistent runtime-RLS tenant dump/scratch restore,
validates scoped counts/no-context/neighbor denial and drops only the generated scratch DB. Live import is not
implemented. Restoring the whole shared primary would affect others; deduplication and referential integrity must be
checked.

## 35. Move to dedicated DB?

Provision identical schema/security, snapshot/copy, reconcile changes or suspend/drain writes, verify checksums/FKs,
advance placement epoch and fence source. Retain rollback plan; reverse switching after target writes requires
reconciliation, not a stale snapshot.

## 36. Multi-region placement?

Prefer one tenant home/writer region with authoritative routing and epochs. Async DR improves resilience but may have
stale memberships/suspensions; fail closed until authorization is current. Active-active needs conflict/fencing design
beyond this deployment.

## 37. Data residency?

Keep primary, replicas, backups, broker, exports, traces and support access within contracted regions; residency cannot
be inferred from the primary DB region alone. Enforce routing policy and audit emergency exceptions.

## 38. Test isolation?

Use real PostgreSQL runtime roles, multiple tenants and overlapping IDs; attack selectors, CRUD, joins, bulk/native SQL,
FKs, cache envelopes, jobs, events, membership revocation and pooled resources. Supplement with independent review and
chaos/drift tests.

## 39. Benchmark overhead?

Use identical datasets/prepared queries, warm/cold cache, controlled tenant skew, offered/achieved throughput and
latency distributions. Compare scoped runtime query plans with a separate nonproduction baseline; never disable
production policies. Local smoke latency does not establish a regional SLO.

## 40. What proves production readiness?

Passing compilation alone is insufficient. Require independent threat review, full adversarial coverage,
migration/backup/restore/DR exercises, measured peak/skewed loads and SLOs, secure secrets/TLS/ACLs, operational
ownership, monitored failure drills and closure of documented gaps. None may be replaced by fabricated claims.
