# MASTER PROMPT: Design and Develop a Production-Grade Multi-Tenant SaaS Platform with Strong Tenant Isolation Using Java 21 and Spring Boot

## 1. ROLE AND ENGINEERING RESPONSIBILITIES

Act as a **Principal Software Engineer, Distinguished Distributed Systems Architect, Enterprise Solution Architect, Java
21 Expert, Spring Security Architect, Database Architect, SaaS Platform Engineer, Cloud Architect, and Site Reliability
Engineer** with 15+ years of experience designing secure, scalable, enterprise-grade multi-tenant SaaS platforms.

Your task is to **design, develop, test, benchmark, document, and deploy a complete Multi-Tenant SaaS Platform using
Java 21 and Spring Boot**, following enterprise architecture best practices and established design patterns.

Treat this as a real enterprise engineering project, not merely a system design interview exercise.

You are responsible for delivering:

1. Functional and non-functional requirements
2. High-Level Design (HLD)
3. Low-Level Design (LLD)
4. SaaS tenancy architecture
5. Tenant registration and provisioning
6. Tenant identification and resolution
7. Tenant authentication and authorization
8. Tenant context management
9. Strong data isolation
10. Database tenancy strategies
11. Tenant-aware persistence and transactions
12. Tenant-aware caching
13. Tenant-aware messaging and background jobs
14. Tenant configuration and feature flags
15. Subscription plans and entitlements
16. Usage metering and quotas
17. Tenant administration
18. Tenant lifecycle management
19. Audit logging and compliance controls
20. Security and privacy engineering
21. Resilience and noisy-neighbor protection
22. Horizontal scalability and capacity planning
23. Observability and operational tooling
24. Automated tests, including adversarial isolation tests
25. Docker and Kubernetes deployment
26. Functional SaaS administration and tenant interfaces
27. Complete source code and documentation

**Do not stop after architecture diagrams, pseudocode, partial examples, or theoretical recommendations. Deliver an
executable, maintainable, secure, tested implementation with source code, infrastructure, functional interfaces, and
operational documentation.**

### Mandatory engineering principles

- Use Java 21 and a verified compatible Spring Boot release.
- Follow existing repository instructions, dependencies, architecture, and coding conventions when working in an
  existing codebase.
- Apply DDD, SOLID, Clean Architecture, Hexagonal Architecture, and separation of concerns pragmatically.
- Prefer a modular monolith with strong bounded contexts unless independent deployments are justified.
- Treat tenant isolation as a fundamental security invariant rather than a convenience filter.
- Never trust arbitrary client-supplied tenant identifiers without verifying authenticated membership and authorization.
- Use defense in depth: authentication, authorization, tenant-scoped queries, database-enforced isolation, and automated
  verification.
- Keep tenant context explicit, immutable within a request, and correctly propagated across asynchronous boundaries.
- Never allow tenant context to leak between pooled threads, virtual threads, HTTP requests, scheduled jobs, or Kafka
  consumers.
- Use short database transactions and clearly defined transaction boundaries.
- Use bounded retries, timeouts, queues, connection pools, and execution resources.
- Make external side effects idempotent where required.
- Avoid unnecessary abstractions, service decomposition, and speculative dependencies.
- Never fabricate test results, benchmark results, or production-readiness claims.
- Make routine engineering decisions independently and explain major trade-offs.

---

# PART A — PROJECT OBJECTIVE

## 2. PRIMARY OBJECTIVE

Build a horizontally scalable Multi-Tenant SaaS Platform capable of securely serving many independent customer
organizations using shared application infrastructure.

Each tenant must have isolated:

- Business data
- Users and memberships
- Roles and permissions
- Configuration
- Feature entitlements
- Usage quotas
- API access
- Caching
- Background jobs
- Events
- Audit history
- Storage resources
- Administrative operations

The system must support configurable isolation levels based on tenant requirements.

### Example scenario

Three enterprises subscribe to the SaaS platform:

- Tenant A — Acme Corporation
- Tenant B — Globex Corporation
- Tenant C — Initech Corporation

All three tenants use the same Spring Boot application deployment.

Each tenant manages its own projects, customers, and users.

When User A from Tenant A sends:

`GET /api/v1/projects`

The response must contain only projects belonging to Tenant A.

If User A attempts:

`GET /api/v1/projects/{tenantBProjectId}`

The platform must deny access or return a non-disclosing not-found response according to its security contract.

The same isolation guarantee must apply to:

- SQL queries
- Repository access
- Redis cache entries
- Kafka events
- Background jobs
- Search indexes
- Object storage
- Reports
- Exports
- Logs and observability
- Administrative APIs

### Fundamental security invariant

**No tenant can read, modify, delete, enumerate, or infer another tenant's protected resources through supported
application interfaces.**

Design automated tests that attempt to violate this invariant at every important architectural boundary.

---

# PART B — TENANCY ARCHITECTURE

## 3. COMPARE MULTI-TENANCY MODELS

Evaluate these approaches.

### Option A — Shared Database, Shared Schema

All tenants share the same database tables.

Each tenant-owned record includes a tenant identifier.

Example:

`projects(id, tenant_id, name, status)`

Evaluate:

- Resource efficiency
- Operational simplicity
- PostgreSQL RLS
- Query performance
- Indexing
- Migration complexity
- Security risks
- Noisy-neighbor effects

### Option B — Shared Database, Schema per Tenant

Each tenant has a separate PostgreSQL schema.

Evaluate:

- Schema isolation
- Provisioning
- Schema migrations
- Connection management
- Tenant growth
- Resource contention
- Operational overhead

### Option C — Database per Tenant

Each tenant has a dedicated database.

Evaluate:

- Isolation
- Backup and restore
- Regional placement
- Compliance
- Resource cost
- Connection pools
- Schema migrations
- Tenant lifecycle management

### Option D — Hybrid Isolation

Support multiple isolation tiers.

For example:

- Free/Standard: Shared database with RLS
- Business: Shared infrastructure with stronger operational isolation
- Enterprise: Dedicated database or isolated deployment

Evaluate:

- Tenant routing
- Provisioning
- Data migration
- Consistency
- Cost
- Operational complexity

### Required decision

Recommend an initial architecture and explain why.

**Prefer shared-schema PostgreSQL with correctly enforced RLS for the initial implementation, while designing explicit
extension points for dedicated enterprise tenant databases.**

Do not implement a generic multi-database abstraction unless its need is demonstrated.

---

# PART C — PRODUCT FUNCTIONAL REQUIREMENTS

## 4. TENANT MANAGEMENT

Implement:

- Tenant registration
- Tenant provisioning
- Tenant activation
- Tenant suspension
- Tenant reactivation
- Tenant configuration
- Tenant plan assignment
- Tenant deletion request
- Tenant data export
- Tenant usage summary
- Tenant lifecycle status

Define tenant states:

- PENDING
- PROVISIONING
- ACTIVE
- SUSPENDED
- DEPROVISIONING
- DELETED
- FAILED

Define valid transitions and administrative authorization.

Provisioning must be idempotent and recoverable.

---

## 5. USER AND MEMBERSHIP MANAGEMENT

Support:

- User registration or identity-provider integration
- Invitation to tenant
- Invitation acceptance
- Tenant membership
- Multiple tenant memberships
- Tenant switching
- Role assignment
- Member suspension
- Member removal
- Membership auditing

Distinguish a globally identified user from tenant-specific membership.

A user may legitimately belong to several tenants.

Do not model one immutable tenant ID directly on the global user identity if multi-membership is required.

---

## 6. TENANT ROLES AND PERMISSIONS

Implement roles such as:

- PLATFORM_ADMIN
- TENANT_OWNER
- TENANT_ADMIN
- TENANT_MEMBER
- TENANT_VIEWER

Support:

- Role-based access control
- Resource ownership
- Tenant-scoped permissions
- Least privilege
- Administrative auditing

Evaluate RBAC versus ABAC.

Start with RBAC and add attribute-based rules only where justified.

Ensure platform administrators use explicit privileged administrative flows rather than silently bypassing tenant
restrictions.

---

## 7. SAMPLE BUSINESS DOMAIN

Implement a realistic SaaS business module to demonstrate isolation.

Use a **multi-tenant Project and Task Management application** as the reference domain.

Each tenant can:

- Create projects
- Manage tasks
- Assign users
- Update project status
- Retrieve dashboards
- Search projects
- Export project data
- Review audit history

### Required entities

- Project
- Task
- ProjectMember
- TaskAssignment
- ProjectStatus
- TaskStatus

Demonstrate isolation for:

- CRUD
- Pagination
- Joins
- Aggregations
- Bulk updates
- Search
- Exports
- Background processing
- Events

Do not demonstrate tenant isolation only with a trivial single-table example.

---

# PART D — NON-FUNCTIONAL REQUIREMENTS

## 8. SCALE AND SLO TARGETS

Treat the following as architectural targets requiring validation.

| Dimension             | Target                                            |
|-----------------------|---------------------------------------------------|
| Registered tenants    | 100,000+                                          |
| Active tenant users   | 10 million+                                       |
| Peak API throughput   | 100,000 RPS                                       |
| Data volume           | Billions of tenant-owned records                  |
| Availability          | 99.99% target                                     |
| API latency           | p99 below 200 ms within a region                  |
| Tenant isolation      | No cross-tenant access under defined threat model |
| Deployment            | Multi-AZ, multi-region extension                  |
| Database              | PostgreSQL                                        |
| Cache                 | Redis                                             |
| Background processing | Horizontally scalable                             |
| Audit                 | Durable, tenant-aware                             |
| Observability         | Metrics, traces, structured logs                  |

Clearly distinguish:

- Tenant count
- Active tenant count
- Database connection count
- Concurrent users
- Request throughput
- Background tasks
- Tenant storage requirements

Do not assume that all tenants generate uniform workloads.

Model tenant-size skew and enterprise hot tenants.

---

# PART E — HIGH-LEVEL DESIGN

## 9. ARCHITECTURE OPTIONS

Compare:

### Option A — Modular Monolith

One Spring Boot deployment with:

- Tenant management
- Identity/membership
- Project/task domain
- Plans/entitlements
- Usage metering
- Audit
- Administration

### Option B — Domain-Oriented Services

Separate deployments where independent scaling, ownership, compliance, or fault isolation justify them.

### Option C — Hybrid SaaS Deployment

Shared control plane with tenant workloads routed to shared or dedicated data planes.

Evaluate:

- Operational complexity
- Tenant isolation
- Independent scaling
- Cost
- Deployability
- Data locality
- Failure blast radius

Recommend a modular monolith with explicit tenant boundaries as the initial implementation.

---

## 10. REFERENCE ARCHITECTURE

Consider:

1. API Gateway
2. OAuth2/OIDC Identity Provider
3. Tenant Resolver
4. Membership/Authorization Engine
5. Tenant Context
6. Business Application Modules
7. PostgreSQL with RLS
8. Redis Cache
9. Event Publisher
10. Kafka
11. Background Workers
12. Subscription/Entitlements Module
13. Usage Metering
14. Audit Store
15. Tenant Provisioning
16. Platform Administration
17. Observability

Provide Mermaid diagrams for:

- Overall architecture
- Tenant resolution
- Authenticated request
- RLS-protected query
- Tenant switching
- Background job execution
- Kafka event processing
- Tenant provisioning
- Suspension/deletion
- Hybrid isolation routing

Separate the control plane from tenant-owned data-plane operations.

---

# PART F — TENANT IDENTIFICATION AND CONTEXT

## 11. TENANT RESOLUTION

Evaluate:

1. Subdomain-based tenant resolution
2. URL path-based tenant resolution
3. Request header-based tenant resolution
4. JWT tenant claims
5. OAuth2 organization membership

Define the authoritative resolution mechanism.

### Recommended baseline

Derive the active tenant from authenticated identity plus verified tenant membership.

An incoming tenant selector may identify the requested tenant but must not grant authorization.

For example:

`X-Tenant-ID: tenant-a`

The backend must verify the authenticated principal has permission to operate within `tenant-a`.

Never trust the header alone.

---

## 12. TENANT CONTEXT PROPAGATION

Implement an explicit tenant context.

Consider:

- Immutable TenantContext record
- Request-scoped resolver
- Method arguments
- Security principal
- ThreadLocal where unavoidable
- TaskDecorator
- Kafka headers
- Background job payloads

### Critical rules

- Do not allow a request to overwrite authenticated tenant context arbitrarily.
- Clear ThreadLocal state in finally blocks.
- Avoid context leakage through thread pools.
- Handle Java 21 virtual threads correctly.
- Propagate tenant identity explicitly across async boundaries.
- Revalidate authorization at the receiving boundary.
- Never use inherited tenant context as proof of permission.

Prefer explicit context passing for security-sensitive application operations.

Do not assume ThreadLocal context automatically propagates through CompletableFuture, `@Async`, Reactor, or message
consumers.

---

# PART G — POSTGRESQL ROW-LEVEL SECURITY

## 13. DATABASE-ENFORCED ISOLATION

Implement PostgreSQL Row-Level Security.

Example concept:

```sql
ALTER TABLE projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects FORCE ROW LEVEL SECURITY;
```

Create tenant-scoped policies based on a transaction-local tenant setting.

Illustrative policy:

```sql
CREATE
POLICY projects_tenant_policy
ON projects
USING (
    tenant_id = current_setting('app.current_tenant', true)::uuid
)
WITH CHECK (
    tenant_id = current_setting('app.current_tenant', true)::uuid
);
```

Treat this as a starting point and validate missing-setting behavior, type conversion, database roles, and policy
evaluation.

### Mandatory security controls

- Runtime database role is not a superuser.
- Runtime role does not have BYPASSRLS.
- Table ownership and FORCE RLS are configured correctly.
- Missing tenant context fails closed.
- Tenant setting is transaction-scoped.
- Every tenant-owned table has appropriate RLS.
- Inserts and updates use WITH CHECK.
- Deletes respect tenant ownership.
- Bulk operations respect RLS.
- Database connection pooling cannot leak a previous tenant setting.

Do not rely solely on Hibernate filters or application-side `WHERE tenant_id = ?` conditions.

Use both application authorization and database-enforced isolation.

---

## 14. TRANSACTION-SCOPED TENANT CONTEXT

Implement safe tenant context initialization for every database transaction.

Evaluate PostgreSQL:

`SET LOCAL app.current_tenant = '...'`

Or parameterized equivalents such as `set_config(..., true)`.

Ensure:

1. Authentication and membership resolution happen before tenant-owned data access.
2. A transaction is started.
3. The transaction-local tenant identifier is set.
4. Repository operations execute on the same transaction-bound connection.
5. The setting is automatically discarded at transaction end.
6. Operations without a valid context fail closed.

### Critical concern

Do not assume setting a tenant variable on one JDBC connection protects SQL executed on another connection.

Demonstrate correct Spring transaction and connection binding.

Test connection reuse across Tenant A and Tenant B.

Also test nested transactions and `REQUIRES_NEW` behavior.

Define how read-only transactions, JPA flushes, native SQL queries, and async operations receive the correct context.

---

# PART H — TENANT-AWARE PERSISTENCE

## 15. JPA / JDBC INTEGRATION

Compare:

- Hibernate multi-tenancy capabilities
- Repository-level tenant predicates
- PostgreSQL RLS
- Schema routing
- DataSource routing

Choose RLS as the authoritative shared-schema isolation mechanism.

Use Spring Data JPA or JDBC based on verified compatibility and transaction behavior.

### Required repository scenarios

Test tenant isolation for:

- findById
- findAll
- save
- update
- delete
- pagination
- joins
- aggregations
- native queries
- bulk updates
- scheduled jobs

Avoid unrestricted administrative repository methods in ordinary tenant flows.

---

## 16. DATABASE KEYS AND INDEXES

Use tenant-aware indexing.

Example:

`CREATE INDEX idx_projects_tenant_status ON projects(tenant_id, status);`

Evaluate:

- Composite unique constraints
- Tenant-scoped natural keys
- Foreign keys
- Index selectivity
- Query planner behavior
- Table partitioning
- Large tenant hotspots

Ensure relationships do not accidentally permit cross-tenant foreign-key references.

Use composite tenant-aware foreign keys where required.

Do not assume globally unique IDs alone guarantee tenant relationship integrity.

---

# PART I — CACHE ISOLATION

## 17. REDIS TENANT-AWARE CACHING

Implement a tenant-aware cache namespace.

Example:

`tenant:{tenantId}:project:{projectId}`

### Mandatory requirements

- Tenant identity included in cache keys.
- Cache access occurs after authorization.
- Tenant-scoped invalidation.
- Cache serialization is safe.
- Cache TTL is configurable.
- No cross-tenant cache hits.
- Tenant usage is observable without high-cardinality metric explosions.
- Cache failure falls back safely.

Avoid caching authorization outcomes beyond their permitted freshness window.

### Adversarial scenario

Tenant A requests project ID X.

Tenant B requests project ID X.

Even if project identifiers collide or are predictable, Tenant B must never receive Tenant A's cached value.

Implement explicit integration tests.

---

# PART J — MESSAGING AND BACKGROUND PROCESSING

## 18. TENANT-AWARE EVENTS

Design Kafka events with:

- Event ID
- Tenant ID
- Aggregate ID
- Aggregate version
- Event type
- Schema version
- Correlation ID
- Timestamp

Do not trust a tenant ID in arbitrary inbound events without validating producer identity and authorization boundaries.

Implement:

- Transactional outbox
- Tenant-aware event publishing
- Idempotent consumers
- Context reconstruction
- Tenant authorization
- Retry
- Dead-letter processing
- Replay

### Critical isolation rule

A consumer must not process one tenant's event under another tenant's database or security context.

---

## 19. TENANT-AWARE BACKGROUND JOBS

Support:

- Scheduled project processing
- Data exports
- Usage aggregation
- Tenant cleanup
- Report generation

Define tenant identity in durable job records.

Every job must establish a verified execution context.

Implement:

- Tenant-scoped job queries
- Tenant quotas
- Execution limits
- Safe context cleanup
- Retries
- Idempotency
- Administrative visibility

Do not assume background workers inherit an HTTP request's security context.

---

# PART K — SUBSCRIPTIONS AND ENTITLEMENTS

## 20. SUBSCRIPTION PLANS

Define plans such as:

- FREE
- STARTER
- BUSINESS
- ENTERPRISE

Use configurable entitlements.

Examples:

- Maximum users
- Maximum projects
- API requests per month
- Storage quota
- Available features
- Export access
- Audit retention

Do not hardcode plan-specific rules throughout business services.

Use a centralized entitlement evaluation abstraction.

---

## 21. FEATURE FLAGS

Implement tenant-scoped feature flags.

Support:

- Feature enable/disable
- Plan-based defaults
- Tenant overrides
- Rollout configuration
- Feature versioning
- Cache invalidation

Use a simple configuration service initially.

Evaluate external feature-flag platforms only when justified.

---

## 22. USAGE METERING AND QUOTAS

Implement:

- API request counts
- Project count
- User seat count
- Storage usage
- Background job quota
- Billing-period usage

Distinguish:

- Hard quotas
- Soft quotas
- Rate limits
- Usage reporting

### Correctness

Quota reservations and updates must be concurrency-safe.

Do not rely on eventually consistent analytics to enforce strict hard quotas.

Use atomic database operations or a justified quota coordination mechanism.

---

# PART L — BILLING AND PAYMENT INTEGRATION

## 23. SUBSCRIPTION BILLING

Implement a sandbox billing model supporting:

- Subscription creation
- Plan change
- Trial periods
- Subscription cancellation
- Subscription status
- Payment-provider webhook integration
- Entitlement activation
- Billing history

Define states such as:

- TRIALING
- ACTIVE
- PAST_DUE
- SUSPENDED
- CANCELLED

Support idempotent billing operations and webhook deduplication.

Do not store raw card information.

Avoid claiming full financial/regulatory compliance from a sandbox integration.

---

# PART M — TENANT PROVISIONING

## 24. PROVISIONING WORKFLOW

Implement a durable tenant provisioning workflow:

1. Register tenant.
2. Validate unique tenant identifier/domain.
3. Create tenant control-plane record.
4. Assign plan.
5. Provision required configuration.
6. Initialize tenant-owned resources.
7. Create initial owner membership.
8. Activate tenant.
9. Emit TenantActivated event.

Use idempotent steps and state transitions.

Recover from partial provisioning failure.

Do not mark a tenant ACTIVE before mandatory security and data resources are ready.

---

## 25. TENANT DEPROVISIONING

Implement:

- Deactivation
- Access revocation
- Data export
- Retention period
- Deletion workflow
- Cache invalidation
- Async job cancellation
- Object storage cleanup
- Audit retention

Handle legal hold and backup retention policies where applicable.

Clearly state limits of immediate deletion from backups and immutable audit logs.

Never let deprovisioned tenants continue accessing active resources.

---

# PART N — NOISY NEIGHBOR PROTECTION

## 26. TENANT FAIRNESS AND RESOURCE LIMITS

Design protection against one tenant monopolizing shared resources.

Implement or evaluate:

- Per-tenant rate limiting
- Per-tenant concurrent request limits
- Per-tenant background job quotas
- Database query timeouts
- Connection pool protection
- Cache usage controls
- Bounded message processing
- Storage quotas
- Priority/fairness scheduling

### Example

Tenant A generates 50,000 RPS while Tenant B generates 100 RPS.

Tenant B should continue receiving reasonable service within the documented capacity and isolation model.

Explain infrastructure-level limits and practical fairness guarantees.

Do not promise perfect performance isolation on fully shared databases without workload controls.

---

# PART O — SECURITY

## 27. ENTERPRISE SECURITY REQUIREMENTS

Implement:

1. OAuth2/OIDC authentication
2. Spring Security authorization
3. Tenant membership verification
4. RBAC
5. Resource-level authorization
6. PostgreSQL RLS
7. Tenant-aware caching
8. Tenant-aware messaging
9. Tenant-aware background processing
10. Administrative privilege separation
11. API rate limiting
12. Secure secrets management
13. TLS
14. Audit logging
15. Security headers
16. Input validation
17. Secure file access
18. Tenant suspension enforcement
19. Session revocation
20. Access-token validation
21. Protection against IDOR/BOLA
22. Defense against confused-deputy operations
23. Cross-tenant bulk-operation protection
24. Data export authorization
25. Backup and restore isolation

Include adversarial security tests.

### Mandatory principle

**Tenant isolation must remain effective even when an application developer accidentally omits a tenant predicate from
an ordinary repository query.**

Use database RLS as defense in depth.

---

# PART P — AUDIT AND COMPLIANCE

## 28. AUDIT LOGGING

Implement immutable or append-oriented audit records for:

- Tenant provisioning
- Tenant suspension
- Role changes
- User invitations
- Project changes
- Plan changes
- Administrative access
- Data export
- Tenant deletion

Record:

- Actor
- Tenant
- Action
- Resource reference
- Timestamp
- Correlation ID
- Outcome

Avoid raw secrets, access tokens, and unnecessary personal data.

Evaluate tamper-evident storage as an advanced feature.

---

## 29. COMPLIANCE CONSIDERATIONS

Discuss deployment-specific requirements such as:

- GDPR
- SOC 2
- ISO 27001
- Data residency
- Retention
- Deletion
- Backup restoration
- Customer-managed encryption keys

Do not claim compliance or certification without evidence.

Define how tenant isolation contributes to compliance but does not independently establish it.

---

# PART Q — JAVA 21 + SPRING BOOT IMPLEMENTATION

## 30. TECHNOLOGY STACK

Use:

| Component           | Technology                                      |
|---------------------|-------------------------------------------------|
| Language            | Java 21                                         |
| Framework           | Spring Boot, verified compatible stable version |
| Build               | Maven                                           |
| REST API            | Spring Web MVC                                  |
| Authentication      | Spring Security / OAuth2 Resource Server        |
| Authorization       | RBAC + resource checks                          |
| Database            | PostgreSQL                                      |
| Data isolation      | PostgreSQL RLS                                  |
| Data access         | Spring Data JPA/JDBC as justified               |
| Database migrations | Flyway                                          |
| Cache               | Redis / Lettuce                                 |
| Messaging           | Apache Kafka where justified                    |
| Object storage      | S3-compatible storage where needed              |
| Metrics             | Micrometer                                      |
| Tracing             | OpenTelemetry                                   |
| Testing             | JUnit 5, Mockito, Testcontainers                |
| Performance         | k6 or Gatling                                   |
| Containers          | Docker                                          |
| Deployment          | Kubernetes                                      |
| Monitoring          | Prometheus/Grafana                              |

Verify exact compatible dependency versions using official documentation.

Evaluate Java 21 virtual threads where blocking I/O justifies them.

Do not assume tenant context automatically propagates across virtual threads or asynchronous execution.

---

# PART R — PROJECT STRUCTURE

## 31. SOURCE CODE ORGANIZATION

Use a maintainable structure such as:

```text id="pfdhkb"
multi-tenant-saas/
├── pom.xml
├── README.md
├── docs/
│   ├── architecture.md
│   ├── tenancy-model.md
│   ├── rls-security.md
│   ├── tenant-context.md
│   ├── provisioning.md
│   ├── tenant-lifecycle.md
│   ├── capacity-planning.md
│   ├── security.md
│   ├── runbook.md
│   └── decisions/
├── src/
│   ├── main/
│   │   ├── java/com/example/saas/
│   │   │   ├── identity/
│   │   │   ├── tenant/
│   │   │   ├── membership/
│   │   │   ├── authorization/
│   │   │   ├── project/
│   │   │   ├── task/
│   │   │   ├── subscription/
│   │   │   ├── entitlement/
│   │   │   ├── metering/
│   │   │   ├── audit/
│   │   │   ├── provisioning/
│   │   │   ├── messaging/
│   │   │   ├── security/
│   │   │   └── shared/
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   └── test/
│       └── java/
├── customer-portal/
├── tenant-admin-portal/
├── platform-admin-portal/
├── benchmarks/
├── docker/
├── helm/
└── .github/workflows/
```

Adapt structure to meaningful bounded contexts.

Avoid excessive packages, empty modules, and speculative microservice boundaries.

---

# PART S — REST API DESIGN

## 32. REQUIRED APIS

Implement versioned REST APIs.

### Tenant Management

- `POST /api/v1/tenants`
- `GET /api/v1/tenants/{tenantId}`
- `PATCH /api/v1/tenants/{tenantId}`
- `POST /api/v1/tenants/{tenantId}/suspend`
- `POST /api/v1/tenants/{tenantId}/activate`

### Membership

- `POST /api/v1/tenants/{tenantId}/invitations`
- `POST /api/v1/invitations/{invitationId}/accept`
- `GET /api/v1/tenants/{tenantId}/members`
- `DELETE /api/v1/tenants/{tenantId}/members/{userId}`

### Projects

- `POST /api/v1/projects`
- `GET /api/v1/projects`
- `GET /api/v1/projects/{projectId}`
- `PATCH /api/v1/projects/{projectId}`
- `DELETE /api/v1/projects/{projectId}`

### Tasks

- `POST /api/v1/projects/{projectId}/tasks`
- `GET /api/v1/projects/{projectId}/tasks`
- `PATCH /api/v1/tasks/{taskId}`
- `DELETE /api/v1/tasks/{taskId}`

### Subscription and Entitlements

- `GET /api/v1/tenants/{tenantId}/subscription`
- `POST /api/v1/tenants/{tenantId}/subscription`
- `GET /api/v1/tenants/{tenantId}/entitlements`
- `GET /api/v1/tenants/{tenantId}/usage`

### Administration

- `GET /api/v1/admin/tenants`
- `GET /api/v1/admin/tenants/{tenantId}/health`
- `POST /api/v1/admin/tenants/{tenantId}/provision`
- `POST /api/v1/admin/tenants/{tenantId}/export`

For every endpoint provide:

- Authentication
- Authorization
- Tenant resolution
- Validation
- Idempotency
- HTTP statuses
- JSON request/response
- Pagination
- Problem Details errors
- OpenAPI specification

Do not allow ordinary tenant members to invoke platform administration endpoints.

---

# PART T — RELIABILITY AND FAILURE SCENARIOS

## 33. REQUIRED PRODUCTION SCENARIOS

Analyze and test these 35 scenarios:

1. Tenant A requests Tenant B's project ID.
2. A repository query omits tenant filtering.
3. Redis cache key omits tenant ID.
4. A pooled thread retains the previous tenant context.
5. A JDBC connection retains a previous tenant setting.
6. A Kafka consumer processes an event under the wrong tenant context.
7. A background job starts without tenant context.
8. One user belongs to multiple tenants and switches active tenant.
9. A client forges the X-Tenant-ID header.
10. JWT tenant claim conflicts with selected tenant.
11. A tenant is suspended during an active request.
12. Tenant membership is revoked while access tokens remain valid.
13. A project references another tenant's task.
14. A bulk update affects rows belonging to another tenant.
15. A native SQL query bypasses ordinary ORM filters.
16. A read replica has different RLS configuration.
17. A privileged database role accidentally bypasses RLS.
18. Tenant provisioning crashes halfway through.
19. Duplicate provisioning requests arrive.
20. Tenant deletion races with background processing.
21. Tenant data export includes unauthorized rows.
22. An object storage key is shared across tenants accidentally.
23. Tenant A overloads a shared connection pool.
24. One tenant creates millions of cache entries.
25. A tenant's Kafka events dominate processing.
26. PostgreSQL primary fails.
27. Redis becomes unavailable.
28. Kafka becomes unavailable.
29. Subscription payment webhook is delivered twice.
30. Tenant quota is exceeded under concurrent creation.
31. Plan downgrade occurs while usage exceeds new limits.
32. A database migration affects tenants on different plans.
33. A tenant moves from shared storage to a dedicated database.
34. A regional outage affects tenant routing.
35. Platform administrator performs an emergency tenant operation.

For each provide:

- Failure mechanism
- Cross-tenant leakage risk
- Data integrity impact
- User-visible behavior
- Detection
- Prevention
- Recovery
- Automated test
- Residual risk

Prioritize security and tenant isolation over convenience or availability.

---

# PART U — TESTING

## 34. UNIT TESTS

Cover:

- Tenant resolution
- Membership verification
- Role authorization
- Project/task domain rules
- Entitlement evaluation
- Quota enforcement
- Tenant lifecycle transitions
- Invitation handling
- Context cleanup
- Cache key generation
- Event context propagation

---

## 35. DATABASE RLS INTEGRATION TESTS

Use Testcontainers with real PostgreSQL.

Test:

- Tenant A reads only Tenant A rows.
- Tenant B reads only Tenant B rows.
- Missing tenant context returns no tenant-owned data or fails safely.
- Tenant A cannot insert records under Tenant B.
- Tenant A cannot update Tenant B records.
- Tenant A cannot delete Tenant B records.
- Tenant-scoped joins work.
- Native SQL queries remain isolated.
- Bulk updates remain isolated.
- Connection reuse cannot leak settings.
- Runtime role cannot bypass RLS.
- Read-only transactions are correctly scoped.

Do not use H2 as proof of PostgreSQL RLS behavior.

---

## 36. SECURITY AND ADVERSARIAL TESTS

Implement cross-tenant attack tests for:

- IDOR/BOLA
- Forged tenant headers
- Unauthorized tenant switching
- Stale membership
- Role escalation
- Search leakage
- Cache leakage
- Background job leakage
- Kafka event misrouting
- Export leakage
- Admin privilege abuse

Include tests using two or more tenants with overlapping resource identifiers where possible.

---

## 37. CONCURRENCY TESTS

Verify:

- Concurrent tenant provisioning
- Duplicate invitations
- Concurrent membership changes
- Quota limit enforcement
- Subscription updates
- Background processing context isolation
- Reused thread/connection safety
- Tenant suspension races
- Concurrent tenant deletion

---

## 38. FAILURE TESTS

Simulate:

- PostgreSQL failure
- Redis outage
- Kafka outage
- Identity provider outage
- Provisioning crash
- Duplicate events
- Lost acknowledgment
- Retry storms
- Tenant migration failure
- Pod termination

Record actual outcomes.

---

# PART V — PERFORMANCE ENGINEERING

## 39. CAPACITY PLANNING

Calculate:

1. Tenant count
2. Users per tenant
3. API requests per tenant
4. Database storage per tenant
5. Index growth
6. Connection pool requirements
7. Cache memory
8. Kafka event throughput
9. Background job concurrency
10. Audit-log growth
11. Export bandwidth
12. Multi-region overhead

Use realistic skewed tenant distributions.

Distinguish small, medium, and enterprise tenants.

Do not assume average load represents peak hot-tenant traffic.

---

## 40. LOAD TESTING

Create workloads for:

- 1,000 RPS
- 10,000 RPS
- 100,000 RPS as a scale target

Test:

- Single hot tenant
- Thousands of low-volume tenants
- Mixed workloads
- Tenant switching
- Authorization-heavy APIs
- RLS-protected queries
- Cache hits/misses
- Background events
- Quota contention
- Large tenant exports

Measure:

- Offered/achieved throughput
- p50/p95/p99 latency
- Error rates
- RLS query overhead
- Database connection utilization
- Cache hit ratio
- CPU/heap/GC
- Per-tenant throttling
- Queue lag
- Noisy-neighbor impact
- Cross-tenant leakage violations

Never fabricate benchmark results.

---

# PART W — MULTI-REGION AND DATA RESIDENCY

## 41. GLOBAL SAAS ARCHITECTURE

Compare:

1. Single-region multi-AZ
2. Active-passive multi-region
3. Tenant-home-region partitioning
4. Active-active multi-region

Define:

- Tenant home region
- Data residency
- Tenant routing
- Replication
- Regional failover
- Tenant migration
- RPO/RTO
- Compliance constraints

Prefer explicit tenant ownership by region where global consistency becomes challenging.

Do not assume asynchronous replication provides immediate worldwide visibility for tenant suspension or authorization
changes.

---

# PART X — TENANT MIGRATION

## 42. SHARED-TO-DEDICATED DATABASE MIGRATION

Design a migration procedure for upgrading an enterprise tenant.

Steps may include:

1. Provision target database.
2. Validate schema compatibility.
3. Establish migration epoch.
4. Copy tenant-owned data.
5. Capture changes during migration.
6. Reconcile data.
7. Quiesce or fence writes during cutover.
8. Switch tenant routing.
9. Verify correctness.
10. Retain rollback plan.
11. Clean up old data after safety checks.

Do not assume a one-time bulk copy guarantees consistency if writes continue.

Document operational trade-offs and downtime options.

---

# PART Y — DEPLOYMENT

## 43. DOCKER AND KUBERNETES

Provide:

- Multi-stage Dockerfile
- Docker Compose
- PostgreSQL with RLS migrations
- Redis
- Kafka if implemented
- Spring Boot backend
- Tenant administration UI
- Kubernetes manifests or Helm
- ConfigMaps
- Secret references
- Resource requests/limits
- HPA
- PodDisruptionBudget
- Readiness/liveness probes
- Graceful shutdown
- Rolling deployment
- CI/CD workflow

Ensure a local demonstration can create two tenants and prove data isolation through actual API calls.

---

# PART Z — OBSERVABILITY

## 44. METRICS AND ALERTING

Expose:

- Tenant provisioning failures
- Tenant activation time
- Unauthorized access attempts
- RLS/security failures
- API request throughput
- API latency
- Tenant quota rejections
- Cache hit/miss rate
- Background job failures
- Outbox backlog
- Kafka consumer lag
- Database connection utilization
- Tenant suspension events
- Tenant deletion progress
- Subscription failures

Provide:

- Micrometer metrics
- Prometheus configuration
- Grafana dashboards
- OpenTelemetry tracing
- Structured logging
- SLOs
- Alerts
- Operational runbooks

Avoid unbounded tenant ID labels.

For targeted tenant diagnostics, use secure structured logs or controlled observability mechanisms rather than
unrestricted high-cardinality metrics.

---

# PART AA — FUNCTIONAL FRONTEND

## 45. TENANT APPLICATION

Implement a minimal functional SaaS web application supporting:

- Login
- Tenant selection
- Project list
- Project CRUD
- Task management
- Tenant member management
- Subscription/plan information
- Usage display

---

## 46. TENANT ADMIN PORTAL

Implement:

- Tenant configuration
- User invitations
- Role management
- Feature entitlements
- Usage monitoring
- Tenant audit logs

---

## 47. PLATFORM ADMIN PORTAL

Implement:

- Tenant registration review
- Tenant activation/suspension
- Tenant provisioning status
- Plan administration
- Tenant health inspection
- Audited support operations

Do not create an unrestricted cross-tenant administration interface without explicit authentication, authorization, and
audit controls.

---

# PART AB — END-TO-END IMPLEMENTATION PLAN

## 48. DEVELOPMENT PHASES

Execute the project incrementally.

**Phase 1 — Requirements and Architecture**

Define the tenancy model, security invariants, isolation boundaries, data ownership, and capacity assumptions.

**Phase 2 — Tenant Identity and Membership**

Implement tenant resolution, identity, membership, RBAC, tenant switching, and security validation.

**Phase 3 — Database RLS**

Implement PostgreSQL RLS policies, transaction-local tenant context, connection safety, migrations, and adversarial
tests.

**Phase 4 — Sample Business Domain**

Implement tenant-isolated projects and tasks with secure CRUD, search, pagination, and bulk operations.

**Phase 5 — Caching and Asynchronous Processing**

Implement tenant-aware Redis keys, event handling, outbox, and background-job context propagation.

**Phase 6 — Provisioning and Lifecycle**

Implement tenant registration, provisioning, activation, suspension, export, and deletion workflows.

**Phase 7 — Subscription and Entitlements**

Implement plans, feature flags, usage metering, quota enforcement, and sandbox subscription billing.

**Phase 8 — Noisy-Neighbor Controls**

Implement rate limits, tenant resource limits, fair background execution, and operational safeguards.

**Phase 9 — Functional Frontend**

Implement tenant application, tenant administration, and controlled platform administration interfaces.

**Phase 10 — Reliability and Observability**

Implement retries, timeouts, audit logs, tracing, metrics, failure recovery, and security monitoring.

**Phase 11 — Deployment**

Implement Docker Compose, Kubernetes/Helm, CI/CD, configuration, and operational documentation.

**Phase 12 — Verification and Performance**

Run unit, integration, concurrency, security, adversarial isolation, and load tests. Fix significant defects.

**Phase 13 — Final Architecture Review**

Review data isolation, security, scalability, tenant lifecycle, operational cost, and remaining risks.

For each phase:

- Implement actual source files.
- Keep the application buildable.
- Run available tests.
- Fix discovered defects.
- Preserve unrelated working code.
- Document important decisions.
- Distinguish verified behavior from assumptions.

If repository and terminal tools are available, create files and execute checks directly.

Otherwise, provide complete source files and commands without claiming execution.

---

# PART AC — REQUIRED DELIVERABLES

## 49. COMPLETE PROJECT OUTPUTS

Deliver:

### Deliverable 1 — Requirements

- Functional requirements
- Non-functional requirements
- Tenant isolation invariants
- Threat model
- Acceptance criteria

### Deliverable 2 — High-Level Design

- Architecture diagrams
- Tenancy model comparison
- Control/data plane
- Tenant context flow
- Capacity planning
- Multi-region strategy

### Deliverable 3 — Low-Level Design

- Domain model
- UML class diagrams
- Sequence diagrams
- State machines
- PostgreSQL schema
- RLS policies
- API contracts
- Authorization model

### Deliverable 4 — Backend

- Java 21 Spring Boot application
- Tenant management
- Identity/membership
- Tenant resolver
- RBAC
- PostgreSQL RLS
- Tenant-scoped transactions
- Project/task domain
- Tenant-aware caching
- Tenant-aware messaging
- Provisioning
- Entitlements
- Quotas
- Usage metering
- Auditing
- Subscription sandbox integration

### Deliverable 5 — Functional Frontend

- Tenant application
- Tenant administration portal
- Platform administration portal

### Deliverable 6 — Automated Tests

- Unit tests
- PostgreSQL RLS integration tests
- Security/adversarial isolation tests
- Concurrency tests
- Failure recovery tests

### Deliverable 7 — Infrastructure

- Docker
- Docker Compose
- Kubernetes/Helm
- CI/CD
- Observability

### Deliverable 8 — Performance

- Load-test scripts
- Actual measurements where available
- Capacity estimates
- Noisy-neighbor analysis
- Bottleneck recommendations

### Deliverable 9 — Documentation

- README
- Architecture Decision Records
- Tenancy model specification
- RLS security guide
- Tenant context propagation
- Provisioning/lifecycle
- Tenant migration guide
- Security threat model
- Deployment guide
- Operational runbook
- Known limitations

---

# PART AD — MANDATORY ARCHITECTURE REVIEW

## 50. SYSTEM DESIGN QUESTIONS

Answer these 40 questions.

1. What is multi-tenancy in SaaS?
2. How do shared-schema, schema-per-tenant, and database-per-tenant models differ?
3. When should a hybrid tenancy architecture be used?
4. How do you securely identify the active tenant?
5. Why is X-Tenant-ID alone insufficient for authorization?
6. How do users belong to multiple tenants?
7. How does tenant switching work securely?
8. How do you prevent IDOR/BOLA across tenants?
9. How does PostgreSQL Row-Level Security work?
10. Why use FORCE ROW LEVEL SECURITY?
11. How can database roles bypass RLS?
12. How does SET LOCAL work with transactions?
13. How do you prevent connection-pool tenant context leakage?
14. What happens during REQUIRES_NEW transactions?
15. Why are Hibernate filters insufficient as the only isolation layer?
16. How do you protect native SQL and bulk updates?
17. How do you enforce tenant-aware foreign keys?
18. How do you isolate Redis cache entries?
19. How do you prevent cache-key collisions?
20. How do you propagate tenant context to Kafka consumers?
21. How do you isolate background jobs?
22. How do you prevent ThreadLocal context leakage?
23. How do virtual threads affect context propagation?
24. How do you implement tenant-scoped RBAC?
25. How do you separate platform administrators from tenant administrators?
26. How do you implement tenant provisioning safely?
27. How do you deactivate and delete a tenant?
28. How do you implement tenant quotas under concurrency?
29. How do you prevent noisy-neighbor problems?
30. How do you implement plan-based feature entitlements?
31. How do you support subscription billing?
32. How do you audit privileged tenant operations?
33. How do you perform a tenant-specific data export?
34. How do you restore one tenant without affecting others?
35. How do you move a tenant to a dedicated database?
36. How do you handle multi-region tenant placement?
37. How do you maintain data residency?
38. How do you test cross-tenant isolation?
39. How do you benchmark RLS and tenant routing overhead?
40. What must be proven before a multi-tenant SaaS platform is production-ready?

For each answer, provide concise architectural reasoning, security guarantees, failure analysis, implementation
implications, and trade-offs.

---

# DEFINITION OF DONE

## 51. ACCEPTANCE CRITERIA

The implementation is complete only when:

- Java 21 project builds successfully.
- PostgreSQL migrations execute.
- Tenant registration and provisioning work.
- Tenant activation/suspension is enforced.
- Multiple tenant memberships work.
- Tenant switching is authorized.
- PostgreSQL RLS policies are enabled and verified.
- Runtime database role cannot bypass RLS.
- Missing tenant context fails closed.
- Tenant-local transaction settings do not leak across pooled connections.
- Cross-tenant reads/writes/deletes are denied.
- Native queries and bulk operations remain isolated.
- Cross-tenant foreign-key references are prevented.
- Redis cache entries are tenant-isolated.
- Kafka and background jobs preserve verified tenant context.
- Tenant entitlements and quotas function.
- Quota enforcement is concurrency-safe.
- Tenant lifecycle workflows recover from partial failure.
- Tenant and platform administration roles are separated.
- Audit records cover privileged operations.
- Automated tests cover adversarial cross-tenant attacks.
- Docker Compose supports a two-tenant isolation demonstration.
- Kubernetes configuration is provided.
- Metrics and health checks work.
- Load-test scripts are reproducible.
- Performance goals are measured or marked unverified.
- Known limitations and security risks are documented.

A passing build or successful happy-path demo is insufficient evidence of production readiness.

---

# FINAL EXECUTION INSTRUCTIONS

Build a **real, production-oriented, strongly isolated Multi-Tenant SaaS Platform using Java 21, Spring Boot, and
PostgreSQL RLS**.

Prioritize:

1. **Tenant isolation:** Prevent cross-tenant data access across every supported interface.
2. **Security:** Verified tenant identity, membership, RBAC, and least-privilege database access.
3. **Data integrity:** Tenant-scoped transactions, constraints, and concurrency-safe updates.
4. **Reliability:** Recoverable provisioning, background processing, and lifecycle workflows.
5. **Scalability:** Efficient shared infrastructure with noisy-neighbor protection.
6. **Maintainability:** Clear bounded contexts and pragmatic enterprise architecture patterns.
7. **Operational flexibility:** Support controlled tenant growth, migration, and dedicated enterprise isolation.
8. **Observability:** Diagnose tenant failures without introducing sensitive-data leakage.
9. **Reproducibility:** Runnable source, functional interfaces, automated security tests, infrastructure, and
   benchmarks.

Begin with a concise architectural assessment and select the initial tenancy model.

Then implement the complete application phase by phase.

Use available development tools to create files, compile, run tests, fix defects, and verify multi-tenant behavior.

At completion report:

- Implemented features
- Architecture decisions and trade-offs
- Tenant isolation mechanisms
- Project structure and important files
- Exact build and run commands
- Tests actually executed
- Benchmark results actually measured
- Known limitations
- Remaining security and production risks

**Do not stop after HLD or LLD. Deliver the complete end-to-end Multi-Tenant SaaS Platform with secure backend,
functional frontend, PostgreSQL RLS, tenant-aware caching and messaging, automated isolation verification, deployment
infrastructure, and operational documentation.**