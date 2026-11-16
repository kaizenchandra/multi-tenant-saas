# Tenant lifecycle contract

Registration (authenticated identity) -> PENDING; platform provisioning transaction initializes required resources then
ACTIVE. Active tenant owner can request DEPROVISIONING; platform administrator can ACTIVE -> SUSPENDED -> ACTIVE.
DEPROVISIONING -> DELETED occurs after retention and absent legal hold. FAILED/PROVISIONING permit controlled
provisioning retry but intermediate failure persistence is not required for the all-database atomic initial
implementation.

[Provisioning and deletion](provisioning.md) contains state/sequence diagrams and crash semantics. [Runbook](runbook.md)
defines incident handling, legal holds and backup limits. A successful suspension/deletion commit denies future tenant
boundaries, including jobs and message processing. Prior committed results cannot be recalled. Deletion purges business
rows after 30 days and retains append-only audit/control tombstones; it is not full personal-data erasure from backups
or audit.
