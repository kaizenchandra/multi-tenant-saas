# Tenancy ownership specification

Initial storage: shared database/schema with forced RLS. Tenant-owned tables: projects, tasks, project_members,
task_assignments, invitations, audit, usage, jobs, outbox and inbox. Every row has tenant_id and a tenant-leading
primary/index path. Tenant relationship FKs contain tenant_id. Control-plane tables
tenants/memberships/plans/billing_events/platform_audit have trusted reviewed API access; no generic repository
interface is exposed.

The authenticated OIDC subject is global to the single configured issuer and can own many memberships. Requested tenant
selection is never proof of access. No request-scoped ThreadLocal exists. Data-plane work is scoped to one immutable
context and one transaction/connection. Export/job/event results are also data-plane rows, not exceptions to isolation.

Architecture model comparison, extension triggers and tradeoffs: [architecture](architecture.md). Exact
policies/grants: [RLS guide](rls-security.md). Request/worker/message sequences: [context guide](tenant-context.md).
Hybrid/region routing is a future design in [migration](tenant-migration.md) and [capacity](capacity-planning.md); no
speculative datasource router is enabled.
