# Security and privacy

Execution update: [current durable workflows and controls](implementation-update.md) supersedes the initial
worker/cache/billing limitations below. Current deployment gaps are tracked in [limitations](limitations.md).

Trust boundaries: external bearer -> signature/issuer/audience/expiry -> verified tenant membership -> RBAC -> RLS ->
composite constraints. Keycloak roles grant platform privileges only through explicit platform APIs. Platform admins do
not automatically gain ordinary business membership. Support export is privileged and audited with the requesting
platform subject. Audit rows are append-only to runtime; a DBA/owner can still tamper, so immutable external/WORM export
and hash chaining remain future controls.

TLS must terminate at a trusted gateway; PostgreSQL, Redis and Kafka require TLS and authentication in deployment.
Compose deliberately binds published ports to loopback and uses demo credentials. Never expose it as a production
system. Kubernetes references an externally created saas-secrets Secret and requires real CA/trust configuration, Redis
authentication/TLS and Kafka SASL/TLS Spring properties. Production gateway must constrain bodies, admission, IP limits
and timeouts before allocating virtual threads/database connections. API uses bearer headers only, no ambient cookie
credentials, hence stateless CSRF is disabled. Browser uses PKCE/state and renders values with textContent; token
sessionStorage remains exposed to same-origin XSS. No frontend third-party scripts are loaded. Configure CSP/connect-src
and redirect URLs for the deployed IdP origin. Implement a BFF/HttpOnly session if the threat model requires stronger
browser token protection.

JWT revocation at IdP isn't checked per request; expiry/signature validates tokens, while live membership and state
checks immediately revoke tenant access at transaction boundaries. Global account disable/token compromise needs short
expiry, introspection or a revocation mechanism. Tenant-specific authorization outcomes are not cached. Payment webhook
requires platform-authenticated bearer plus timestamped HMAC and deduplicates event IDs; it is a sandbox adapter, not an
integration with a commercial payment provider. Plan change is deliberately self-service sandbox behavior without
charging money. Subscription state does not independently gate all tenant APIs; product payment enforcement remains a
documented gap.

RLS protects omitted predicates but does not make SQL injection safe against forged settings. Runtime role can change
custom settings; compromised app code, secrets or DB owner is outside that guarantee. Control tables require explicit
subject/tenant filters and privileged review. No unrestricted SQL endpoint exists.

Compliance is deployment/process-specific: GDPR requires lawful basis, privacy rights, retention and real erasure
policy; SOC2/ISO27001 require independent organizational evidence and controls; residency applies to all
replicas/logs/backups. Customer-managed keys need per-tenant envelope encryption and rotation/escrow procedures and are
not implemented. Tenant isolation contributes to these obligations but proves no certification. Logs must exclude bearer
tokens, invitation subjects where unnecessary, payloads and secrets; restrict tenant diagnostic access. Infrastructure
access must be audited separately.
