# Database isolation contract

Execution update: [current durable workflows and controls](implementation-update.md) supersedes the initial
worker/cache/billing limitations below. Current deployment gaps are tracked in [limitations](limitations.md).

Migration owner `saas_owner` is separate from `saas_runtime`. Both are NOSUPERUSER/NOBYPASSRLS; runtime owns no tables,
cannot alter policies, cannot truncate tables, and cannot mutate audit rows. RuntimeGuard rejects unsafe role flags,
inherited ownership, absent FORCE/ENABLE flags, unexpected policy counts/expressions and missing
control-fence/query-epoch triggers at startup. It does not replace independent policy/role catalog audits or drift
detection.

Sixteen data-plane tables have forced RLS: projects, tasks, project_members, task_assignments, invitations, usage,
audit, outbox, inbox, jobs, feature_flags, idempotency, billing_history, event_projection, usage_storage and
api_budgets. `USING` limits reads/update/delete; `WITH CHECK` prevents writes to another tenant. Empty/missing setting
fails closed via `nullif(current_setting('app.current_tenant',true),'')::uuid`.

All application tenant work begins a Spring-bound transaction and invokes parameterized
`set_config('app.current_tenant',?,true)` on its connection. Connection reuse discards LOCAL settings on
commit/rollback. Session-level SET is prohibited in application code. Native SQL and bulk SQL remain subject to
policies. IDs alone do not prevent relationship attacks: composite foreign keys bind every project/task/member
relationship to the same tenant.

Control tables (tenants, memberships, plans, billing_events, platform_audit) deliberately have reviewed privileged
access paths rather than tenant RLS. `/me/tenants` is a subject-filtered global directory. These tables are not exposed
through generic CRUD/query endpoints. Splitting control credentials/processes is a recommended production hardening
step. RLS is a boundary against accidental tenant predicate omission, not compromised application credentials or SQL
injection capable of changing the GUC. Use parameterized SQL throughout.

Restore/replicas must use identical roles, FORCE policies and schema version and pass the adversarial suite before
routing traffic. No read replica is configured. Constraint failures map to generic 409 to avoid returning foreign row
details. Side-channel elimination (timing, contention, global slug availability) is outside the guarantee; directory
slugs are public identifiers. Audits avoid tokens and raw payloads but retain subject identifiers under documented
retention policy.

Migrations V6–V10 split metering from business transactions, add replay retention, shared/exclusive transaction advisory
fences, statement-level cache epochs and durable bounded read budgets. V10 backfills usage with an explicit tenant loop
because FORCE RLS applies to the migration owner. Upgrade pre-fence writers under maintenance/draining; mixed old/new
writers are not claimed safe. Restore must include all seventeen tables and current control metadata.
`scripts/restore-drill.py` restores a consistent scoped runtime-role snapshot only into a generated disposable database,
validates isolation, then drops it. It does not import into the live database.

V11–V16 add safe quota settlement, streaming export chunks, single-RPC fresh authorization, statement-level storage
accounting, bounded sixteen-credit grants and quota/history integrity. RuntimeGuard runs after migrations and before
serving, checks all 17 protected tables and 12 statement accounting triggers, and rejects bypass roles/owners. Runtime
cannot alter Flyway history. A composite ledger/usage FK prevents deleting a reserved billing period. `export_chunks`
has FORCE RLS and tenant-aware job FKs. Legacy pending exports recover requester identity only from committed
export-request audit; missing proof fails closed. See [protocol](reconciliation-and-streaming.md).
