# ADR 002 — Bounded request grants and denial optimization

Status: implemented and locally tested; 10,000-RPS capacity remains unqualified.

Runtime evidence found a positive feedback loop: slow traffic disabled the old
500 ms batching heuristic and produced more durable quota transactions. Leased
requests now use the configured 1–16 credit grant for both cold and warm tenants.
The database hard cap remains authoritative. Active expired grants close their
known-unused balance with replacement; unknown balances never receive refunds.
This trades more temporarily reserved cold-tenant credits for predictable bounded
transaction work. Exact per-request mode remains available.

A cached credit is a candidate, never authorization. Current database membership,
tenant state and fingerprint are checked before consumption and before occupying
the verified tenant lane. Stale plan fingerprints use at most three pre-business
attempts with fresh authorization. Business effects are never internally replayed.

A learned token-fingerprint/selector window can only reject. It is populated after
JWT validation and never provides a principal, claims or membership. Unknown or
changed tokens pass to the normal authentication boundary. Strict entry bounds and
expiry limit memory; saturation falls back to normal authentication. Token/selector
rotation evades this optimization, so it is not an edge DDoS defense. No raw bearer
token is retained by the guard.

Independent admission maps use independent monitors. Individual security-filter
observations default off while HTTP, authentication and authorization observations
remain. Full filter spans can be restored for diagnosis. These changes reduce
observed contention and instrumentation work without changing RLS or durable WAL.

The larger pool diagnostic reduced errors while increasing latency and dropped
arrivals. It is not the default or a validated capacity recommendation. The original
local profile stays bounded at two 2.5-CPU APIs, 30 connections and 28 global permits
each. See [actual measurements and limitations](../performance-regression.md).

Final local scheduling correction: each API now requests 2.5 CPU cores to match its unchanged limit. The measured cgroup
weight rose from35 to208. Two replicas request five cores total, plus2.5 for a rolling surge; these are scheduler
requests, not dedicated CPUs. Earlier benchmarks used250m requests. Current mixed/noisy confirmations still fail full10k
criteria; [results and confounders](../performance-regression.md#cpu-request-correction-and-final-configuration).
