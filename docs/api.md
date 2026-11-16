# API contract

[OpenAPI](openapi.json) enumerates every supported route, request shape, selector, role and response status. It
intentionally describes dynamic JDBC JSON results as maps; response schemas are not a generated typed SDK contract. All
requests require a valid signed JWT except static UI/probes. `X-Tenant-ID` is required for projects/tasks/dashboard/jobs
and invitation acceptance. Tenant path ID governs tenant management. A conflicting header cannot override that path.
PLATFORM_ADMIN is a realm role; ordinary tenant roles are live database memberships.

```sh
curl http://localhost:8088/api/v1/projects \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-ID: $TENANT_ID"
curl -X POST http://localhost:8088/api/v1/projects \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-ID: $TENANT_ID" \
  -H 'Content-Type: application/json' -d '{"name":"Launch","status":"OPEN"}'
```

Examples:

* Registration: `{"slug":"acme","name":"Acme"}` -> 201 `{"id":"uuid","state":"PENDING"}`. Platform provisioning -> 200
  `{"state":"ACTIVE"}`.
* Project creation: `{"name":"Launch","status":"OPEN"}` -> 201
  `{"id":"uuid","name":"Launch","status":"OPEN","version":0}`.
* Listing: `?q=launch&page=0&size=25` -> 200
  `{"items":[{"id":"uuid","name":"Launch","status":"OPEN","version":0,"tasks":2}],"page":0,"size":25}`. Stable UUID
  ordering; max size100 and bounded offset. Count is scoped; no total count is promised. Task lists use page and fixed
  size100; member/audit/admin lists have explicit caps. The UI supports project/task pagination.
* Task creation/update: `{"title":"Review","status":"TODO"}` -> 201 task summary; update ->200 `{"id":"uuid"}`.
  Assignments/project members: `{"subject":"oidc-subject"}` ->200 added/assigned boolean. Subject must be active in
  selected tenant.
* Invite: `{"subject":"oidc-subject","role":"TENANT_MEMBER"}` ->201 invitation ID/subject/role. Acceptance requires
  matching authenticated subject, tenant selector, unexpired unaccepted UUID. Replayed acceptance returns404. Role PATCH
  currently uses the same subject/role shape; path subject controls target.
* Configuration: `{"name":"Acme","exportEnabled":true}`. Plans: `{"plan":"STARTER"}` ->200 sandbox marker. Entitlements
  return centralized plan limits plus tenant config. Usage returns current calendar-month api/jobs plus project/seat
  counts. Storage usage measures logical project/task/result bytes. API usage reports api_confirmed (confirmed accepted
  attempts), api/api_reserved (hard-cap reservations), and api_assumed (settled uncertain credits), and
  api_inflight_or_indeterminate (reserved minus confirmed and assumed); never invoice the reserved field as confirmed
  calls.
* Export: POST ->202 `{"id":"job-uuid"}`. GET jobs/id ->state/result. New EXPORT job results contain a small manifest
  (download path, SHA-256, compressed bytes, counts and chunk count). GET `/jobs/{id}/content` streams gzip under live
  admin authorization; expired artifacts return410. Reports retain aggregate JSON results.
* Delete returns200 `{"deleted":true}`; absent foreign objects yield404 after tenant authorization.
  Suspension/activation ->200 state; invalid lifecycle ->409. Owner deletion request ->200 DEPROVISIONING.
* Webhook body: `{"eventId":"provider-1","tenantId":"uuid","status":"ACTIVE","occurredAt":"2026-11-15T00:00:00Z"}` plus
  X-Timestamp and X-Signature; platform bearer required. Response200 for first/identical duplicate delivery; conflicting
  semantic payload under the same eventId returns 409. Older occurredAt cannot overwrite a newer billing state. Omit
  occurredAt only for legacy senders; delivery time supplies ordering, while deduplication stays stable across
  re-signing.

Errors: application validation 400, JWT failure401, membership/role/state/flag denial 403, scoped absent resource404,
integrity/project-seat-job quota/lifecycle conflict409, monthly API/rate limit429, query timeout503. Application errors
use Problem Details `{"type":"about:blank","title":"Forbidden","status":403,"detail":"Operation not permitted"}`. Spring
Security's pre-controller 401/403 return a minimal Problem Details body without sensitive validation details. Database
connection/acquisition/timeout failures return503; no tenant cache response bypasses fresh authorization, and no SQL
details are returned.

Idempotency: registration (same owner/slug), provisioning and membership/assignment upserts converge; invitation
acceptance rejects replay; billing event IDs deduplicate. Project/task/report creates accept an optional
tenant/actor/route/canonical-payload-bound Idempotency-Key for seven days; differing payload returns 409. Optional
project If-Match rejects stale edits412; task edits remain last-write-wins. No universal retry safety is promised. All
business mutations append audit atomically; task changes do not currently publish separate domain events. API quotas
count accepted authorized attempts, including subsequent business errors/rollbacks. With request budgets, unconfirmed
grants remain conservatively reserved after an owner crash; see [metering protocol](throughput-hardening.md).

## Extended operations

* `POST /tenants/{id}/reports` ->202 job ID; `Idempotency-Key` optional; tenant admin/owner, monthly job quota and
  reports flag.
* `GET /tenants/{id}/jobs`, `POST /tenants/{id}/jobs/{job}/retry`: tenant admin/owner, retry only FAILED; all queries
  RLS scoped.
* `GET /tenants/{id}/events`: delivery status/projection/deferred counts, tenant admin/owner.
  `POST /admin/tenants/{id}/events/{event}/replay`: PLATFORM_ADMIN, actor audit, reject a live lease.
* `GET /tenants/{id}/features`, `PUT /tenants/{id}/features/{export|reports}` with
  `{enabled:true,rollout:100,version:0}`; zero creates, subsequent version compare-and-swap protects updates.
  Admin/owner mutates.
* `PATCH /tenants/{id}/members/{subject}/state` with `{active:false}` or true; admin/owner, cannot suspend owner,
  reactivation obeys seats.
* `POST /tenants/{id}/owner` with `{subject:"active-member-subject"}`; current owner only, previous owner becomes admin.
* `GET /tenants/{id}/billing-history`: admin/owner, immutable tenant-scoped rows.
  `POST /tenants/{id}/subscription/cancel`: owner, schedules calendar-period cancellation. Expired/non-active
  subscriptions reject new ordinary mutations402; owners can recover through sandbox plan subscription.
* Project/task/report POST supports actor/route/payload-bound Idempotency-Key. A differing replay gives409. Project
  PATCH accepts optional numeric/quoted If-Match and gives412 for a stale version. Omitting it preserves last-write-wins
  behavior. Job results are parsed JSON. Storage usage is logical business data bytes, not physical DB size.

## Reconciliation and streaming operations

* `GET` / `POST /admin/tenants/{id}/quota/reconciliation`: PLATFORM_ADMIN only, preview / bounded conservative
  settlement with actor audit. Unknown credits remain reserved; proven unused credits return only through trusted
  internal ledger closure.
* `GET /jobs/{id}/content`: X-Tenant-ID plus current tenant admin/owner, fresh checks per chunk, gzip/SHA-256, no-store,
  24-hour artifact expiry. Two process downloads / one per tenant; 429 on capacity,410 on expiry. A midstream revocation
  truncates the response; verify length, gzip CRC and the manifest digest. No Range support.
* Support explicitly uses `GET /admin/tenants/{tenant}/exports/{job}` for status and `/content` for download.
  PLATFORM_ADMIN is audited and tenant-scoped; it does not gain ordinary membership. The support enqueue response
  supplies these paths.

See [protocol](reconciliation-and-streaming.md) for resource bounds, retries and accounting semantics.
