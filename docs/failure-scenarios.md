# Failure scenarios and coverage

Each entry distinguishes implemented protections from unexecuted fault drills. All protected-data operations prioritize
denial over availability. Detection should correlate bounded security/worker counters, HTTP 4xx/5xx, audit state
transitions and database/broker telemetry; no tenant-ID metric labels are used. Data integrity is unchanged on
denied/rolled-back operations; infrastructure failures can leave uncertain client acknowledgments requiring
idempotency/reconciliation.

## 1. Foreign project ID

Failure/leakage risk: IDOR would disclose data. Prevention and user-visible behavior: RLS lookup returns 404; no writes.
Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant write is authorized.
Detection/automated test: IDOR HTTP test. Actual coverage: **IDOR test executed**. Recovery: restore the trusted
boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work; never weaken RLS. Residual
risk: Timing side channels remain.

## 2. Omitted tenant predicate

Failure/leakage risk: Unscoped repository exposes all tenants. Prevention and user-visible behavior: Forced RLS limits
visible/modified rows. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant
write is authorized. Detection/automated test: Native/bulk integration test. Actual coverage: **Omitted-predicate test
executed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only
idempotent work; never weaken RLS. Residual risk: Privileged SQL/GUC forgery out of threat model.

## 3. Cache key omits tenant

Failure/leakage risk: Shared key leaks cached response. Prevention and user-visible behavior: Typed tenant/version keys
plus envelope validation and authorized SQL first. Integrity: protected transactions commit data/audit/outbox together
or roll back; no cross-tenant write is authorized. Detection/automated test: Cache key/envelope tests. Actual coverage:
**Cache tests executed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then
retry only idempotent work; never weaken RLS. Residual risk: Malicious trusted cache with forged valid envelope remains
an infrastructure compromise.

## 4. Thread retains context

Failure/leakage risk: Previous identity reused. Prevention and user-visible behavior: No ThreadLocal; explicit immutable
arguments. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant write is
authorized. Detection/automated test: Reused single-thread executor. Actual coverage: **Thread reuse test executed**.
Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work;
never weaken RLS. Residual risk: Future libraries must preserve rule.

## 5. Connection retains setting

Failure/leakage risk: Pool serves previous tenant rows. Prevention and user-visible behavior: LOCAL setting resets at
transaction end. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant write
is authorized. Detection/automated test: Alternating tenant/missing-scope tests. Actual coverage: **Connection reuse
test executed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only
idempotent work; never weaken RLS. Residual risk: Session SET by future code must be prohibited.

## 6. Consumer wrong tenant

Failure/leakage risk: Misrouted event mutates another tenant. Prevention and user-visible behavior: Match own committed
outbox and reconstruct transaction. Integrity: protected transactions commit data/audit/outbox together or roll back; no
cross-tenant write is authorized. Detection/automated test: Wrong-tenant consumer test. Actual coverage: **Consumer test
executed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only
idempotent work; never weaken RLS. Residual risk: Arbitrary broker producers need ACLs.

## 7. Job missing context

Failure/leakage risk: Global job scans leak rows. Prevention and user-visible behavior: Required UUID + fresh scoped
system boundary. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant write
is authorized. Detection/automated test: Null context and export tests. Actual coverage: **Job tests executed**.
Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work;
never weaken RLS. Residual risk: No independent worker authentication deployment.

## 8. Multi-membership switch

Failure/leakage risk: Prior tenant state reused. Prevention and user-visible behavior: Fresh live role/state check for
selected tenant. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant write
is authorized. Detection/automated test: Switch A/B test. Actual coverage: **Switch test executed**. Recovery: restore
the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work; never weaken RLS.
Residual risk: UI must clear selected objects.

## 9. Forged header

Failure/leakage risk: Attacker selects foreign tenant. Prevention and user-visible behavior: Membership check
returns 403. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant write is
authorized. Detection/automated test: Forged-selector HTTP test. Actual coverage: **Header test executed**. Recovery:
restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work; never
weaken RLS. Residual risk: Credential theft outside scope.

## 10. JWT selector conflict

Failure/leakage risk: Confused deputy trusts one of two tenants. Prevention and user-visible behavior: Reject signed
tenant claim conflict. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant
write is authorized. Detection/automated test: Conflicting-claim test. Actual coverage: **Claim test executed**.
Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work;
never weaken RLS. Residual risk: Invitation acceptance must match same rule.

## 11. Suspended mid-request

Failure/leakage risk: Stale state admits work. Prevention and user-visible behavior: Shared/exclusive advisory fence
defines commit order; new work denied after suspend commit. Integrity: protected transactions commit data/audit/outbox
together or roll back; no cross-tenant write is authorized. Detection/automated test: Suspension test plus lock protocol
review. Actual coverage: **Sequential and in-flight ordering tests executed**. Recovery: restore the trusted
boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work; never weaken RLS. Residual
risk: Already committed/in-flight response cannot be recalled.

## 12. Membership revoked with valid token

Failure/leakage risk: Token remains globally valid. Prevention and user-visible behavior: Re-read membership on every
tenant boundary. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant write
is authorized. Detection/automated test: Stale-membership HTTP test. Actual coverage: **Revocation test executed**.
Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work;
never weaken RLS. Residual risk: Global IdP disable not immediate token revocation.

## 13. Foreign relationship

Failure/leakage risk: Cross-tenant FK corrupts domain. Prevention and user-visible behavior: Composite tenant-aware
foreign keys reject insert. Integrity: protected transactions commit data/audit/outbox together or roll back; no
cross-tenant write is authorized. Detection/automated test: FK integration test. Actual coverage: **Composite FK test
executed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only
idempotent work; never weaken RLS. Residual risk: Admin imports need same constraints.

## 14. Bulk cross-tenant update

Failure/leakage risk: Mass modification without tenant filter. Prevention and user-visible behavior: RLS applies to
UPDATE/DELETE. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant write
is authorized. Detection/automated test: Unscoped UPDATE/delete tests. Actual coverage: **Bulk test executed**.
Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work;
never weaken RLS. Residual risk: Owner can alter policies.

## 15. Native SQL

Failure/leakage risk: ORM bypass leaks data. Prevention and user-visible behavior: Database policy applies regardless of
ORM. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant write is
authorized. Detection/automated test: Raw JdbcTemplate tests. Actual coverage: **Native test executed**. Recovery:
restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work; never
weaken RLS. Residual risk: Unsafe SQL/GUC injection excluded.

## 16. Replica policy drift

Failure/leakage risk: Replica serves rows without protection. Prevention and user-visible behavior: No replica path;
validate migrations/roles before adding. Integrity: protected transactions commit data/audit/outbox together or roll
back; no cross-tenant write is authorized. Detection/automated test: Replica acceptance suite required. Actual coverage:
**Not deployed/tested**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then
retry only idempotent work; never weaken RLS. Residual risk: Deployment drift remains risk.

## 17. Privileged runtime role

Failure/leakage risk: Superuser/BYPASSRLS exposes all rows. Prevention and user-visible behavior: Startup guard refuses
unsafe role flags. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant
write is authorized. Detection/automated test: Role flag and DDL denial test. Actual coverage: **Role test executed**.
Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work;
never weaken RLS. Residual risk: External privilege changes need continuous drift monitoring.

## 18. Provisioning crash

Failure/leakage risk: Partial resources ACTIVE. Prevention and user-visible behavior: Single transaction commits all
mandatory setup or none. Integrity: protected transactions commit data/audit/outbox together or roll back; no
cross-tenant write is authorized. Detection/automated test: Transaction rollback crash injection required. Actual
coverage: **Duplicate test executed; crash injection pending**. Recovery: restore the trusted boundary/dependency,
inspect tenant/audit/outbox state, then retry only idempotent work; never weaken RLS. Residual risk: External
provisioning saga absent.

## 19. Duplicate provisioning

Failure/leakage risk: Duplicate owner/resources/events. Prevention and user-visible behavior: Tenant lock plus
idempotent ACTIVE response. Integrity: protected transactions commit data/audit/outbox together or roll back; no
cross-tenant write is authorized. Detection/automated test: Duplicate provision test. Actual coverage: **Sequential
duplicate test executed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then
retry only idempotent work; never weaken RLS. Residual risk: Concurrent duplicate stress pending.

## 20. Deletion vs job

Failure/leakage risk: Job recreates deleted data. Prevention and user-visible behavior: Lock tenant and recheck ACTIVE;
cancel jobs on deletion. Integrity: protected transactions commit data/audit/outbox together or roll back; no
cross-tenant write is authorized. Detection/automated test: Retention/job race harness required. Actual coverage:
**Deletion fence and retention purge test executed**. Recovery: restore the trusted boundary/dependency, inspect
tenant/audit/outbox state, then retry only idempotent work; never weaken RLS. Residual risk: Large purge needs chunks.

## 21. Export unauthorized rows

Failure/leakage risk: Whole-DB export leaks others. Prevention and user-visible behavior: RLS worker and own-job fetch
authorization. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant write
is authorized. Detection/automated test: Background export + live demo. Actual coverage: **Tests executed**. Recovery:
restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work; never
weaken RLS. Residual risk: Bounded JSON export only.

## 22. Object key reused

Failure/leakage risk: Signed URL exposes foreign object. Prevention and user-visible behavior: No object storage path
implemented; use tenant namespace if added. Integrity: protected transactions commit data/audit/outbox together or roll
back; no cross-tenant write is authorized. Detection/automated test: S3 adversarial test required before enabling.
Actual coverage: **Not applicable to current DB results**. Recovery: restore the trusted boundary/dependency, inspect
tenant/audit/outbox state, then retry only idempotent work; never weaken RLS. Residual risk: No S3 adapter.

## 23. Hot tenant exhausts pool

Failure/leakage risk: Availability cross-talk, no direct data leak. Prevention and user-visible behavior: 100/s limit,
finite pool, SQL deadlines. Integrity: protected transactions commit data/audit/outbox together or roll back; no
cross-tenant write is authorized. Detection/automated test: Skewed workload target in k6. Actual coverage: **No load
result claimed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only
idempotent work; never weaken RLS. Residual risk: Waiting hot transactions still consume shared slots.

## 24. Cache millions of entries

Failure/leakage risk: Memory exhaustion evicts others. Prevention and user-visible behavior: Per-tenant LRU entry
caps,Compose maxmemory and TTL; query-cache entries are bounded and tenant/epoch keyed. Integrity: protected
transactions commit data/audit/outbox together or roll back; no cross-tenant write is authorized. Detection/automated
test: Memory pressure load required. Actual coverage: **Not executed**. Recovery: restore the trusted
boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work; never weaken RLS. Residual
risk: Per-tenant entry count is bounded; bytes/infrastructure memory remain shared.

## 25. Kafka dominated by one tenant

Failure/leakage risk: Lag delays neighbors. Prevention and user-visible behavior: Tenant key, bounded outbox per tick,
retries. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant write is
authorized. Detection/automated test: Hot-key workload required. Actual coverage: **Broker smoke only**. Recovery:
restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work; never
weaken RLS. Residual risk: Single partition local topic not scale architecture.

## 26. PostgreSQL primary failure

Failure/leakage risk: Transaction abort/lost commit ack. Prevention and user-visible behavior: Rollback fails closed;
managed failover needed. Integrity: protected transactions commit data/audit/outbox together or roll back; no
cross-tenant write is authorized. Detection/automated test: Local PostgreSQL stop/restart verifies503/no cached
data,liveness200,readiness503,recovery200. Actual coverage: **Local outage drill executed; primary/replica promotion not
executed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only
idempotent work; never weaken RLS. Residual risk: Client idempotency differs by operation.

## 27. Redis outage

Failure/leakage risk: Cache unavailable. Prevention and user-visible behavior: Safe authorized DB fallback. Integrity:
protected transactions commit data/audit/outbox together or roll back; no cross-tenant write is authorized.
Detection/automated test: Mock outage test. Actual coverage: **Outage test executed**. Recovery: restore the trusted
boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work; never weaken RLS. Residual
risk: Database load increases.

## 28. Kafka outage

Failure/leakage risk: Events delayed or duplicates after lost ack. Prevention and user-visible behavior: Retain outbox,
bounded attempts, inbox dedup. Integrity: protected transactions commit data/audit/outbox together or roll back; no
cross-tenant write is authorized. Detection/automated test: Mock broker failure plus local container outage/recovery.
Actual coverage: **Outbox outage test and local Kafka drill executed**. Recovery: restore the trusted
boundary/dependency, inspect tenant/audit/outbox state, then retry only idempotent work; never weaken RLS. Residual
risk: Real failover/retry storm drill pending.

## 29. Duplicate billing webhook

Failure/leakage risk: Repeated entitlement/payment action. Prevention and user-visible behavior: Timestamped HMAC +
unique event ID transaction. Integrity: protected transactions commit data/audit/outbox together or roll back; no
cross-tenant write is authorized. Detection/automated test: Signed callback replay and forged signature tests. Actual
coverage: **Signed callback tests executed**. Recovery: restore the trusted boundary/dependency, inspect
tenant/audit/outbox state, then retry only idempotent work; never weaken RLS. Residual risk: Conflicting same-ID payload
returns409 and stale semantic events cannot overwrite newer state; provider-specific ledger reconciliation remains
missing.

## 30. Concurrent project quota

Failure/leakage risk: Read-count races exceed plan. Prevention and user-visible behavior: Tenant lock + count/update
same transaction. Integrity: protected transactions commit data/audit/outbox together or roll back; no cross-tenant
write is authorized. Detection/automated test: 12 contenders for 4 free slots. Actual coverage: **Quota concurrency test
executed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry only
idempotent work; never weaken RLS. Residual risk: Writes remain exclusive; read budgets conservatively reserve
unconfirmed credits after crashes.

## 31. Downgrade over quota

Failure/leakage risk: Forced deletion or accidental unlimited growth. Prevention and user-visible behavior: Preserve
existing rows; block new creation above new limit. Integrity: protected transactions commit data/audit/outbox together
or roll back; no cross-tenant write is authorized. Detection/automated test: Downgrade overload test required. Actual
coverage: **Not executed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then
retry only idempotent work; never weaken RLS. Residual risk: No warning/negotiated grace period UI.

## 32. Migration across plans

Failure/leakage risk: Plan-specific code/schema mismatch. Prevention and user-visible behavior: One schema version for
all plans; feature limits separate. Integrity: protected transactions commit data/audit/outbox together or roll back; no
cross-tenant write is authorized. Detection/automated test: Flyway migration integration. Actual coverage: **Initial
migrations executed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry
only idempotent work; never weaken RLS. Residual risk: Rolling compatibility migration drill pending.

## 33. Shared to dedicated migration

Failure/leakage risk: Dual writers/lost changes. Prevention and user-visible behavior: Design-only fenced
epoch/snapshot/CDC runbook. Integrity: protected transactions commit data/audit/outbox together or roll back; no
cross-tenant write is authorized. Detection/automated test: Migration fault harness required. Actual coverage: **Not
implemented/executed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then retry
only idempotent work; never weaken RLS. Residual risk: No runtime router.

## 34. Regional outage

Failure/leakage risk: Stale routing/auth reopens access. Prevention and user-visible behavior: Single-region only;
future current-epoch authorization fence. Integrity: protected transactions commit data/audit/outbox together or roll
back; no cross-tenant write is authorized. Detection/automated test: Regional failover drill required. Actual coverage:
**Not implemented/executed**. Recovery: restore the trusted boundary/dependency, inspect tenant/audit/outbox state, then
retry only idempotent work; never weaken RLS. Residual risk: RPO/RTO unmeasured.

## 35. Emergency admin operation

Failure/leakage risk: Support privilege abuse. Prevention and user-visible behavior: Explicit role gate and
actor-audited export/health/lifecycle. Integrity: protected transactions commit data/audit/outbox together or roll back;
no cross-tenant write is authorized. Detection/automated test: Admin denial + support demo. Actual coverage: **Role
denial executed; independent abuse review pending**. Recovery: restore the trusted boundary/dependency, inspect
tenant/audit/outbox state, then retry only idempotent work; never weaken RLS. Residual risk: No dual-control approval or
external audit sink.
