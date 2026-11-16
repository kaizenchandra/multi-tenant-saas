# Local throughput hardening and metering contract

The initial 1,000-RPS run achieved only 207.93 successful responses/s with 79% 429s. Eight tenants could not
legitimately supply 1,000/s under the unchanged 100/s tenant policy. The initial VU-local selector also biased fresh
virtual users toward one tenant. The current runner uses 32 populated tenants and a global scenario iteration round
robin. This changes the workload as well as the code; the measured improvement is not a controlled single-variable
speedup.

## Evidence and changes

Runtime PostgreSQL wait sampling found WAL sync/write waits, and JVM thread dumps showed admission/commit waits. CPU
throttling and probe restarts were observed with undersized local pods. Virtual-thread pinning was investigated but not
established as the cause; no debugger attach was possible with the available tools. Raw failed attempts and profile
captures remain in benchmarks. Fixes:

* API reservations commit independently before business work. Business reads are truly read-only and use shared
  transaction advisory fences instead of exclusive tenant locks or row share locks that create MultiXact/WAL pressure.
* Lifecycle/member mutations take the exclusive fence before row locks. Database triggers reject conflicting unfenced
  control changes, preventing a read/suspension race from silently bypassing the fence. Hash collisions cause contention
  only; tenant RLS still compares exact UUIDs.
* Project-list cache keys contain tenant UUID, authoritative database data_epoch, query and pagination. Statement
  triggers increment epochs for native/bulk project/task changes. Every hit still pays fresh authorization/quota/fence
  checks. Values are immutable, entries bounded and concurrent misses coalesced.
* Admission permits are bounded by authenticated principal and then verified tenant. Principal bursts can wait within
  one 100 ms overall admission deadline; forged tenant selectors cannot consume a victim lane. Servlet threads and
  connection pools remain bounded.
* The current local Kubernetes manifest has 2.5 CPU / 1 GiB limits, 30 pool connections, 28 global and 6 verified-tenant
  concurrent operations per API pod. Original limits were 1 CPU / 768 MiB and pool 4. There are two replicas plus
  Compose workers/API. Hardware/resource changes are part of the result.
* A separate private management connector on 8081 handles probes; readiness includes PostgreSQL and liveness excludes
  it. Platform threads default to 64 maximum; virtual threads remain opt-in and do not carry authorization context.

## Durable request budgets and exports

The current implementation extends leases to both reads and writes, caps each grant at sixteen credits, and adds
conservative settlement plus late-proof correction. `SAAS_QUOTAS_LEASED_REQUESTS=true` is the preferred flag; legacy
`SAAS_QUOTAS_LEASED_READS` remains a fallback. Default Compose stays in exact mode. No membership or JWT authorization
outcome is cached. The complete accounting, crash/retry and streaming protocol is
in [reconciliation and streaming](reconciliation-and-streaming.md).

The local mixed-load patch now uses two 2.5-CPU/1-GiB API replicas, thirty maximum/two minimum-idle connections each and
twenty-eight business permits. Private PostgreSQL/Redis EndpointSlices remove host-port dependency hairpins. PostgreSQL
allows 192 connections for active/rolling/draining API pods, Compose workers, snapshot readers and administration. G1
replaces container-selected Serial GC. These profile changes are included in benchmark metadata.

Statement-level transition-table storage accounting aggregates one reservation per SQL statement, skips zero-byte
updates and preserves atomic hard caps. The 100,000-task seed fell from a multi-minute per-row-trigger attempt to an
actual 1.881-second statement-based seed. Cursor-backed gzip exports now pack bounded chunks independently of
authorization checkpoints and renew leases only when needed. This removes hundreds of unnecessary lease/chunk commits;
source authorization still checks every 256 rows.

A reversible 1,000-microsecond `commit_delay` trial improved the measured mixed workload while preserving `fsync=on`,
`synchronous_commit=on` and `fdatasync`. It is now explicit in the local Compose profile. It is not a universal
production recommendation; repeat measurements on the actual storage system.
See [PostgreSQL WAL configuration](https://www.postgresql.org/docs/17/runtime-config-wal.html)
and [latest measurements](verification.md). No durability or RLS checks were disabled to improve throughput.

## Validation scope

[Verification](verification.md) records actual benchmark and fault-drill results. Passing warm reads is not a regional
or 99.99% qualification. Mixed/noisy 10,000-RPS tests have now been executed and fail full arrival/capacity criteria;
neighbor error/latency improvements are reported separately. A large export is verified, but prolonged soak, 100,000 RPS
and RLS-overhead A/B remain unverified. No foreign resource IDs are supplied in the performance workload, so its zero
leakage counter is not isolation proof; adversarial PostgreSQL/API/cache tests establish that separate evidence.

Protocol
references: [PostgreSQL transaction advisory locks](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS)
and [k6 global iteration identity](https://grafana.com/docs/k6/latest/javascript-api/k6-execution/). Shared advisory
locks coordinate trusted operations; RLS remains the data access boundary.

## Follow-up WAL/CPU investigation

The [performance regression report](performance-regression.md) supersedes prior assumptions about the 348-RPS collapse.
Cold quota grants now use the configured batch size; expiry closure is combined with replacement where safe. Independent
Admission maps have separate locks. Tenant-lane admission follows fresh membership. Individual security-filter spans
default off while authn/authz and HTTP observations remain. A strictly bounded denial-only token/selector fingerprint
guard rejects exhausted known pairs before repeated JWT work; it grants no authentication or access. Token rotation can
evade this optimization, so edge protection remains necessary.

A larger pool reduced errors but increased latency and dropped arrivals; it is not the default or a claimed capacity
fix. Even the old image recovered to about 4.5–5.2k successful RPS under isolated conditions. Current 10k criteria
remain failed. Immutable image IDs, fresh synthetic database/cache fixtures, consistent warmup and per-trial profiler
settings are now recorded by the repeatable runner.

Final local scheduling correction: each API now requests 2.5 CPU cores to match its unchanged limit. The measured cgroup
weight rose from35 to208. Two replicas request five cores total, plus2.5 for a rolling surge; these are scheduler
requests, not dedicated CPUs. Earlier benchmarks used250m requests. Current mixed/noisy confirmations still fail full10k
criteria; [results and confounders](performance-regression.md#cpu-request-correction-and-final-configuration).
