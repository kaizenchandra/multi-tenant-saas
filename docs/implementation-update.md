# Executable workflow update

## Durable work

`DurableWork` replaces the original worker implementation. A job claim commits attempt count, a random fencing token and
a 30-second lease before execution. Completion verifies token/state/lease; transaction failure is recorded in a separate
transaction with persisted exponential delay. After three attempts the job is FAILED; a tenant administrator can retry
explicitly. Expired leases are reclaimed, and exhausted abandoned leases become FAILED. Unexpected process death
therefore does not reset attempts. EXPORT reads scoped full rows within documented size limits; REPORT produces separate
grouped project/task aggregates. Results and audit commit atomically.

Outbox claims commit a random lease and attempt before the network call. Kafka send/ack waits run with no database
transaction on two dedicated publisher threads with a32-item bounded queue. A five-second retry pause after broker
failures prevents network retries from starving report execution. Ack/failure reconciliation uses the lease token,
bounded exponential delay and five attempts. Failure or process loss may duplicate delivery; inbox IDs and transactional
projections make repeat processing idempotent. Operator replay resets delivery state only when no live lease exists,
with explicit PLATFORM_ADMIN and actor audit. Poison events still reach Kafka DLT after bounded consumer retries; no
arbitrary inbound event is trusted.

Consumers validate a committed tenant-local outbox record and persist an unprocessed inbox record even while the tenant
is SUSPENDED. They perform no tenant business projection until ACTIVE. Resumption drains deferred inbox records and
increments type counters exactly once. Deprovisioned/deleted tenants remain fenced. Scheduled worker batches are bounded
and rotate a tenant cursor; hot-tenant fairness at very large scale still needs partitioned scheduling.

## Isolation and concurrency

The additional feature_flags, idempotency, billing_history, event_projection, usage_storage and api_budgets tables have
forced RLS. Runtime startup checks all seventeen protected tables, exact USING/WITH CHECK expressions, one policy per
table, runtime flags and inherited table ownership. Ordinary native/bulk operations remain protected.

Project/task/report create Idempotency-Key values are tenant scoped and bind actor, route and SHA-256 request hash. A
key reused for a different operation conflicts; committed responses replay without duplicate writes. Fingerprints use
canonical sorted JSON across replicas, with legacy outer-map ordering accepted for old keys. Keys expire after seven
days with bounded cleanup. Optional project If-Match accepts the numeric version or quoted numeric ETag and returns412
on stale edit. No general retry safety for all endpoints is claimed.

Database triggers maintain atomic logical storage accounting for project/task fields and job result JSON. Limits live in
the centralized plans table. Concurrent growth must reserve capacity in the same transaction; rollback releases it and
deletion decrements usage. Existing over-limit data after downgrade is preserved, and further positive growth is
blocked.

A per-pod admission gate bounds principal/global demand before JDBC acquisition and applies a tenant lane only after
authentication/membership verification. The current local load patch uses 28 global,6 per tenant,30 pool connections and
100 ms bounded waiting; a forged selector cannot occupy a victim tenant lane. Reads now share a transaction advisory
fence and use real read-only transactions; mutations/lifecycle changes remain exclusive. Short independent metering and
optional durable request-credit grants reduce WAL commit pressure. See [throughput hardening](throughput-hardening.md).

Redis uses tenant hash-tag namespaces suitable for same-slot scripts, versioned resource keys and a bounded per-tenant
LRU index (default 100 entries, configurable TTL30 s). Atomic Lua insertion evicts excess keys. Tenant lifecycle
suspension/deletion invalidates after transaction commit; Redis failure cannot bypass SQL authorization. Cache
writes/invalidation fail safely to database access.

## Product workflows

Versioned export/reports flags support deterministic per-subject percentage rollout using SHA-256 buckets; they cannot
enable an export disallowed by the plan. Reads evaluate current configuration, so no authorization cache invalidation
window exists. Owner transfer and member suspend/reactivate preserve live membership checks and seat limits.

Trial expiration and calendar-period cancellation write immutable billing history and change subscription status.
Expired/non-active subscriptions deny new tenant mutations with 402; read-only subscription recovery remains available
to the owner. Sandbox plan reactivation records history and clears scheduled cancellation. Webhooks retain
authentication/HMAC/deduplication controls and append history. Stable semantic fingerprints reject conflicting event-ID
reuse; occurredAt orders entitlement changes so stale events cannot reactivate a later suspension. Re-signed identical
events deduplicate even when the delivery timestamp changes.

Retention deletes at most 500 rows from each table per pass and continues in FK-safe order only after child tables are
empty. Interrupted work remains DEPROVISIONING and resumes; audit, billing history and control metadata retain
documented retention semantics.

## UI and production configuration

The browser reads its IdP URL from public `/ui-config`, uses PKCE, shares token refresh among concurrent calls, ends the
IdP session on logout, accepts invitation tenant UUIDs outside existing memberships, and provides project/task
pagination, versioned feature configuration, job inspection/retry, reports and cancellation. All rendered data uses
textContent. Browser visual testing remains unverified; Node tests exercise session logic.

`application-production.yml` removes demo credential defaults and configures Redis TLS plus Kafka SASL_SSL.
`gateway/nginx.conf` requires deployment certificates and supplies ingress size/time/IP bounds. Kubernetes activates the
production profile; operators must create the referenced credentials, CA trust and managed services. ProductionGuard
rejects demo secrets, non-HTTPS OIDC, PostgreSQL without sslmode=verify-full, duplicate DB URL options, insecure
Redis/Kafka overrides and runtime Flyway. Sandbox plan activation is disabled in production. These files do not
establish a production deployment without environment validation; the selected target remains local.

A two-replica local Kubernetes deployment is verified in namespace saas-isolation-dev at http://localhost:30088; its
dependencies/workers remain in Compose. Local Compose publishes the backend at http://localhost:8088 because another
host application occupies8080. PostgreSQL remains 5558, Redis6388, Keycloak8188, Kafka9099. Demo realm user IDs are
pinned to preserve memberships across local IdP recreation.

## Reconciliation and streaming update

[Reconciliation and streaming](reconciliation-and-streaming.md) specifies migrations V11–V17: stable grant replay,
assumed/proven settlement, late-proof correction, generation-fenced RLS export chunks, single-RPC fresh authorization,
statement storage accounting and preserved restore evidence. No uncertain credits are automatically refunded. New
exports produce manifests/downloads rather than inline documents; tenant and explicit support interfaces implement both
routes. Startup transport checks precede ordinary bean creation, and database role/policy checks precede serving.

The current local load profile uses private PostgreSQL/Redis routes, two G1 API replicas, bounded denial-only
subject/selector guards and durable1 ms group commit. Mixed/noisy 10k benchmarks were actually run and do not meet full
capacity criteria. See verification for measured values and remaining limits; there is no cloud deployment claim.
