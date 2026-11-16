# Request and execution boundaries

Execution update: [current durable workflows and controls](implementation-update.md) supersedes the initial
worker/cache/billing limitations below. Current deployment gaps are tracked in [limitations](limitations.md).

Selectors (subdomain, path, header, organization claim) name a requested tenant; none proves membership. The baseline
uses the path ID for tenant management and `X-Tenant-ID` for business APIs. The signed JWT supplies identity; database
membership and ACTIVE state authorize selection. If a JWT contains `tenant_id`, it must agree with selection. URL IDs
govern tenant management; header values cannot override them.

```mermaid
sequenceDiagram
 participant Client
 participant JWT
 participant Boundary
 participant PG
 Client->>JWT: bearer + tenant selector
 JWT->>JWT: issuer / audience / expiry / signature
 JWT->>Boundary: verified subject and selected tenant
 Boundary->>PG: Fresh membership + short durable quota reservation
 Boundary->>PG: BEGIN READ ONLY for business read
 Boundary->>PG: set_config tenant LOCAL
 Boundary->>PG: Shared advisory fence + live ACTIVE/membership check
 Boundary->>Boundary: immutable context + RBAC
 Boundary->>PG: RLS-protected query / epoch-validated query cache
 PG-->>Boundary: only selected tenant rows
 Boundary->>PG: COMMIT (setting discarded)
 Boundary-->>Client: response
```

```mermaid
sequenceDiagram
 participant User
 participant UI
 participant API
 participant Memberships
 User->>UI: Select organization B
 UI->>API: Same global identity + selector B
 API->>Memberships: Fresh membership/state lookup
 Memberships-->>API: Active role or deny
 API-->>UI: Tenant B data or 403
```

Immutable TenantContext is passed as a method argument. Receiving a context object is never the HTTP authentication
mechanism. Constructors/boundary helpers are package scoped and trusted application code. Async jobs carry tenant ID in
durable rows; consumers reconstruct only after matching a committed outbox record. Never use CompletableFuture common
pools or implicit security context as authorization. Virtual threads change execution resource costs, not these rules.
There are no application ThreadLocals to clear.

Nested calls use REQUIRES_NEW: outer connection suspends, inner obtains a fresh connection, initializes its own tenant
setting, then outer resumes with its original setting. Nested boundaries consume additional pool capacity; do not
recursively invoke arbitrary nesting. Quota reservation completes before the business transaction begins; the ordinary
read path never holds one JDBC connection while requesting another. Ordinary mutations acquire an exclusive tenant
advisory fence; reads acquire a shared fence. Control-table triggers reject concurrent unfenced metadata changes,
protecting suspension/member revocation without row writes for every read. Supported control flows acquire the fence
before locking rows. Lock-key hash collisions cause false contention only; RLS still compares exact UUIDs. Missing/empty
GUC yields NULL UUID and no rows; malformed UUID causes SQL failure. Business GET transactions are read-only. API quota
reservation uses a separate short write transaction. Accepted authorized attempts count even if subsequent business work
returns404/rolls back. Optional durable request budgets reserve at most sixteen credits, with a fresh database
membership/state check inside the business transaction before every locally consumed credit; they cache no
authorization. Read the [quota protocol](throughput-hardening.md) before enabling this optimization. No JPA flush or
replica data path exists.

```mermaid
sequenceDiagram
 participant Worker
 participant Control
 participant PG
 Worker->>Control: Scan bounded ACTIVE tenant batch
 Worker->>PG: BEGIN + tenant setting + locked state
 Worker->>PG: Claim own pending job SKIP LOCKED
 Worker->>PG: Read own RLS rows and store own result
 Worker->>PG: Audit + COMMIT
```

```mermaid
sequenceDiagram
 participant Publisher
 participant Broker
 participant Consumer
 participant PG
 Publisher->>PG: Lock committed own outbox rows
 Publisher->>Broker: Tenant-keyed immutable envelope
 Broker-->>Publisher: acknowledgment
 Publisher->>PG: published=true
 Broker->>Consumer: At least once envelope
 Consumer->>PG: Reconstruct tenant + ACTIVE check
 Consumer->>PG: Match ID/type/version/aggregate/correlation in own outbox
 Consumer->>PG: INSERT inbox ON CONFLICT DO NOTHING
 Consumer->>PG: Audit only for first processing
 Consumer-->>Broker: Commit offset after transaction
```

The broker is not an authentication authority. Only this application's committed outbox envelopes are accepted.
Production must additionally use Kafka TLS/SASL and producer/topic ACLs. Consumer retries twice then publishes DLT;
failure to publish DLT prevents successful recovery. Local outbox attempts cap at five; operator inspection/replay is
required afterward. Suspended tenants retain validated inbox records without projecting business changes; activation
drains them idempotently. Deprovisioned/deleted tenants remain fenced. No cross-service arbitrary event execution is
supported.
