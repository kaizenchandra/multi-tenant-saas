# Capacity assumptions, targets and regional design

Execution update: [current durable workflows and controls](implementation-update.md) supersedes the initial
worker/cache/billing limitations below. Current deployment gaps are tracked in [limitations](limitations.md).

All calculations below are estimates. Registered tenants are not active tenants, users are not open connections, and
connection pools must be budgeted across API pods, workers and administrative jobs.

| Tier       | Registered | Peak active fraction | Peak requests per active tenant/s | Projects per tenant | Logical business bytes/tenant |
|------------|-----------:|---------------------:|----------------------------------:|--------------------:|------------------------------:|
| Small      |     95,000 |                   1% |                                 2 |                  50 |                          1 MB |
| Medium     |      4,900 |                  10% |                                50 |               5,000 |                        100 MB |
| Enterprise |        100 |                 100% |                               736 |           1,000,000 |                         20 GB |

Estimated aggregate peak: 1,900 + 24,500 + 73,600 = 100,000 RPS. This deliberately skewed **future** workload exceeds
current plans and 100/s limiter; it requires sharding/dedicated hot-tenant isolation and reworked quotas, not simply
more pods. A 50,000 RPS single tenant will be throttled; shared PostgreSQL cannot promise perfect neighbor isolation.

Logical data ~95 GB + 490 GB + 2 TB = 2.585 TB before tasks, indexes, replication and audit. At ~2x indexes + ~30%
headroom allow >10 TB primary storage; measure actual row/index sizes. One billion 1KB rows is 1TB logical before
indexes. Ten million registered users at 1% concurrent active gives 100k concurrent users; if 2% issue a query at once
that's 2k concurrent queries, not 10M database connections.

Little's law: at 100k requests/s and 10ms database occupancy, demand ~1,000 database-active slots before queueing. A
single unsharded PostgreSQL is not assumed to sustain that. Current 16-connection pools x 12 pods already reserve 192
connections; include consumers/management and avoid saturating DB max_connections. Use PgBouncer transaction pooling
only after LOCAL settings/pinning tests. Current reads share an advisory lifecycle fence; writes remain exclusive and
quota grants serialize only short counter updates. Durable request budgets reduce synchronous WAL commits while keeping
the cap conservative. The current local load profile has 30x2 API pools, a 16-connection Compose API/worker pool and at
most two snapshot connections per active generator process. PostgreSQL max_connections192 includes rollout/drain overlap
and administration. Private dependency routes and bounded admission improve efficiency but do not eliminate WAL/CPU
sharing. Measured 10,000-RPS mixed/noisy tests still fail full capacity/arrival criteria; see verification. A
100,000-task export is measured, not an unlimited-data claim.

At 20% event-producing writes and 100k RPS, expect 20k events/s; at 1KB each ~20MB/s logical plus replication (3x),
retention and consumer traffic. At 1KB audit/event, 20k writes/s produces ~1.728TB/day. Caching 10M 1KB values costs >
10GB plus Redis key/allocator overhead; current Compose caps memory at 128MB with LRU. Exports of 1TB/day average
11.6MB/s but require burst caps. 32 tenants per worker tick and one bounded job per tenant are fair-ish at low scale,
not a 100,000-tenant high-throughput scheduler. Partition workers by stable tenant hash and introduce leased queues at
scale.

## Region options

| Mode                   | Advantage                                                   | Risk                                           |
|------------------------|-------------------------------------------------------------|------------------------------------------------|
| Single-region multi-AZ | simplest transactional authorization; automatic DB failover | regional disaster                              |
| Active-passive region  | lower cost DR                                               | asynchronous RPO; promotion lag; stale auth    |
| Tenant-home-region     | clear writer/data residency                                 | global directory/routing availability          |
| Active-active          | low regional latency                                        | conflicts and authorization fencing complexity |

Prefer tenant-home-region with one writer region per tenant. Add explicit placement and routing epoch to control records
only when a second data plane exists. Routing must fail closed on stale placement epochs. Initial RPO/RTO are
unmeasured; propose <=5min RPO / <=60min RTO only after restore exercises. Multi-AZ synchronous replication can improve
RPO but does not establish achieved SLOs. Region failover must revalidate membership/suspension against a current
authoritative fence; asynchronously replicated ACTIVE state must not silently revive suspended access. Data residency
covers DB, backups, logs, exports and broker replicas. Cross-region replication can itself violate residency.
