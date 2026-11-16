# ADR 001: modular monolith / shared-schema RLS / explicit JDBC

Accepted: one deployment, shared PostgreSQL with forced RLS and separate migration/runtime roles. Explicit
TenantTransactions sets transaction-local context on the bound JDBC connection. Immutable context arguments replace
application ThreadLocals. Composite tenant FKs protect relationships; no generic multi-database routing layer.

Consequences: efficient provisioning/migrations and testable omitted-predicate isolation. Control-plane metadata
requires reviewed explicit scoping. Shared transaction advisory read fences, exclusive mutation/control fences and short
independent quota reservations replace serializing every read. Optional durable request budgets reduce WAL commits with
conservative indeterminate reservations. Dedicated placement/routing epochs and fleet-wide pool fairness remain future
work with concrete scale/compliance triggers. This tradeoff is documented rather than claiming the reference
implementation reaches global scale targets.
