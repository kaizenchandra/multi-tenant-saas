# Quota reconciliation and streaming export protocol

This implementation targets the local Compose/OrbStack deployment. Database RLS remains authoritative for every new
table and artifact read. Reconciliation never guesses that an unacknowledged credit was unused.

## Safe quota settlement

Migrations V11–V17 extend the existing grant ledger. Grant IDs are stable across one bounded retry, so a lost
acknowledgement cannot reserve a second grant. Replays are actor-bound, expire with the original database rate window
and never mint replacement credits. Grants are capped at sixteen credits by the database and configurable from one to
sixteen with `saas.quotas.grant-size`. Both reads and writes can use them; exact per-request mode remains available by
disabling leased requests. All grants remain bounded by current tenant rate/monthly caps.

Cold and warm tenants both request the configured grant size; there is no throughput-dependent singleton heuristic. This
avoids a low-throughput/more-commits feedback loop. Active expired grants retain known-unused evidence for closure in
the next replacement RPC; idle cleanup still closes them conservatively. Cold tenants may temporarily reserve more
unused credits, bounded by the same hard cap and documented crash uncertainty.

Each grant settles as OPEN, PROVEN or ASSUMED. Normal closure atomically returns the exact known-unused amount and
confirms used credits once. After the database window plus a 61-second safety age, the worker or explicit platform
operation may settle unknown credits as ASSUMED. **This refunds zero credits and does not label them confirmed usage.**
A duplicate settlement has no effect. The retained ledger and append-only audit record explain the assumption.

If an acknowledged owner later reports its known-unused balance, closure can correct an ASSUMED settlement once: release
only proven-unused credits, remove the assumed amount and confirm the actual used amount. Repeated returns do nothing.
Assumed records are excluded from normal short retention. This is a conservative quota ledger, not permission to invoice
uncertain consumption; commercial billing/disputes need an explicit customer policy.

Usage fields: `api`/`api_reserved` is the hard-cap reservation total; `api_confirmed` is admitted/proven consumption;
`api_assumed` is conservatively settled uncertain consumption; `api_inflight_or_indeterminate` excludes the separately
reported assumed credits. A normally acknowledged sixteen-credit grant can strand up to fifteen unconfirmed credits per
tenant per process; lost RPC acknowledgements can leave additional indeterminate grants. No accounting or reconciliation
route can grant membership or bypass RLS.

Platform administration:

* `GET /api/v1/admin/tenants/{id}/quota/reconciliation` previews counts/eligibility.
* `POST` to that route settles at most 1,000 expired grants conservatively and audits the authenticated platform actor.
  Repeating it is naturally idempotent.
* Ordinary tenant users cannot invoke these routes. Late proof/refunds come from the trusted grant owner, not arbitrary
  client-supplied refund amounts.

## Fewer round trips with fresh authorization

A cached credit ticket is only a candidate. `establish_tenant` sets the transaction-local scope, takes the
shared/exclusive advisory fence and freshly reads ACTIVE state, membership, role, plan fingerprint and cache epoch in
one database round trip. Local credit consumption occurs only after that authorization. A forged selector cannot burn
another tenant's cached credits or occupy its verified-tenant admission lane; that permit is acquired only after fresh
membership and retained through commit/rollback. Changed plan fingerprints reject and invalidate old credits before
business access.

Credit consumption uses atomic compare-and-set rather than waiting for a Java monitor while a database fence is held.
Otherwise a grant RPC waiting behind an exclusive fence could create a Java/SQL lock-order cycle. Expired/raced tickets
retry at most three times, outside the prior transaction and before business work; business side effects are never
replayed by this retry. No authorization decision is cached. Failed uncached authorization removes the empty slot
immediately; untrusted selectors cannot fill the shared credit cache. Return queues are bounded with backpressure, and
shutdown cleanup has a deadline; unresolved durable grants remain conservatively reserved instead of delaying process
termination indefinitely. Token expiry is checked again at internal download boundaries.

## Streaming exports

All newly generated EXPORT jobs produce a gzip JSON artifact and a small manifest rather than embedding the whole
document into job-result JSON. The document includes projects, tasks, project members and task assignments from one
PostgreSQL REPEATABLE READ snapshot under runtime RLS. A separate, lazy pool allows at most two snapshot connections.
Forward-only cursors fetch 256 rows; gzip uses a 64 KiB buffer and persisted chunks are at most 256 KiB. No application
layer collects the full export in heap.

`export_chunks` has forced RLS and a composite tenant/job foreign key. Each attempt has a fresh generation UUID bound to
the durable job lease. Checkpoints every 256rows revalidate current tenant state, the requester’s current admin
membership (or the explicit authorized support job), lease identity and expiry. They renew a lease only when it has less
than 20 seconds remaining. Full256KiB buffers and table tails reserve compressed storage and commit chunks in short
transactions, independently of authorization checkpoints. A retry removes old unpublished generations in bounded
batches. Stale workers cannot publish; only final lease validation publishes DONE plus checksum, size, counts,
generation and a 24-hour expiry atomically.

Default limits are 1 GiB compressed per artifact and 120 seconds generation time, configurable by
`saas.exports.max-bytes` and `saas.exports.max-seconds`. Plan storage caps still apply atomically. These are explicit
resource bounds, not the former 1,000-project/10,000-task limits. Long snapshots retain database versions until
completion and remain an operational cost.

`GET /api/v1/jobs/{id}/content` requires the tenant selector and current admin membership. It returns
`application/gzip`, attachment disposition, no-store, Content-Length and an ETag containing the compressed SHA-256. Each
chunk revalidates membership, token expiry, tenant state, publication generation and artifact expiry. Revocation stops
subsequent chunks; bytes already transmitted cannot be recalled. Client disconnects release process admission in
finally. Truncation is detectable through Content-Length, gzip CRC and the published SHA-256.

Downloads have two process slots and one slot per tenant, a five-minute checked deadline and no JDBC connection held
during socket writes. A blocked operating-system socket can retain its bounded HTTP slot; this is not perfect bandwidth
isolation. The browser streams to the File System Access API where supported. Its fallback is explicitly capped at 32
MiB instead of buffering arbitrary large artifacts. The authenticated API/curl remains available for other clients. The
updated demo downloads and verifies gzip content.

Exports run on a bounded job executor, separately from Kafka publishing. Two concurrent generators cannot block the
scheduler itself. Capacity deferrals do not consume poison-job attempts. Expired/failed chunks are deleted in bounded
tenant-scoped passes, releasing logical storage; tenant deletion purges them before parent jobs.

## Bulk storage integrity

V14 replaces per-row accounting with AFTER statement triggers using transition relations. It aggregates the full byte
delta and atomically reserves storage once per statement, including chunk inserts/deletes. Zero-byte
status/version/lease updates do not write the storage counter. An oversized bulk statement rolls back all its rows and
byte reservation. RuntimeGuard verifies all 17 protected tables, fence/cache triggers and 12 storage-accounting
triggers. Restores disable only derived accounting/cache-epoch triggers in the disposable target, then re-enable and
validate content digests. V17 preserves both PROVEN/ASSUMED settlement and original timestamps during import; a
regression test round-trips ledger rows and the scratch drill verifies non-empty export chunks plus both settlement
states.

References: [pgJDBC cursor requirements](https://jdbc.postgresql.org/documentation/query/), [PostgreSQL transition relations](https://www.postgresql.org/docs/17/sql-createtrigger.html), [k6 arrival-rate allocation](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/arrival-rate-vu-allocation/).

## Local deployment and benchmark boundaries

The local load patch uses two API replicas with 2.5 CPU each, thirty maximum/two minimum-idle connections per replica
and twenty-eight business permits. PostgreSQL allows 192 connections to cover active, rolling and draining replicas,
Compose workers, two snapshot readers and administration. The Linux k6 client has a four-CPU/2-GiB cap. These are local
test allocations, not measured production sizing.

`local-dependencies.py` discovers only this Compose project's PostgreSQL/Redis addresses, validates service labels and
publishes private Kubernetes Service/EndpointSlice routes. Refresh them after recreating dependency containers. This
removes dependency traffic through Mac host ports; no database role, RLS or durability requirement is relaxed.

Authenticated ingress applies concurrency and denial-only subject/selector rate guards before controller parsing. A
selector never proves membership, and a forged selector affects only the authenticated caller's own pair. PostgreSQL
remains the authoritative 100/s tenant limit across actors and replicas. Per-process protective caps can be stricter
under skew/failover; shared infrastructure still does not provide perfect performance isolation.

Production transport/secrets validation now runs before ordinary bean creation; database role/policy/storage checks run
after migrations and before serving traffic. Snapshot readers inherit the main driver's TLS properties and use a longer
bounded socket deadline. Runtime credentials cannot alter migration history, and budget records cannot outlive their
referenced period counter. Legacy pending export identities are recovered only from committed request audit; absent
proof fails closed.

The benchmark fixture uses 256 tenants and thirty-two authenticated identities, with credentials in a protected,
deduplicated temporary file shared by k6 virtual users. Large environment payloads are not copied into every virtual
user. It records exact generator/network route/resources, offered cohort counts, drops, acknowledged and ambiguous
committed writes, and WAL timings. Failed launch attempts have no fabricated request totals. See the extended
verification report for actual measurements.
