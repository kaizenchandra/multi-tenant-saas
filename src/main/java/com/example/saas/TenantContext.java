package com.example.saas;

import java.util.UUID;

record TenantContext(UUID tenantId, String actor, String role, UUID correlation, long dataEpoch) {
    TenantContext(UUID tenantId, String actor, String role, UUID correlation) {
        this(tenantId, actor, role, correlation, 0);
    }

    boolean admin() {
        return role.equals("TENANT_OWNER") || role.equals("TENANT_ADMIN");
    }

    void requireAdmin() {
        if (!admin()) throw ApiError.forbidden();
    }

    void requireWrite() {
        if (role.equals("TENANT_VIEWER")) throw ApiError.forbidden();
    }
}
