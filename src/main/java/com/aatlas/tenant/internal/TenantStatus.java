package com.aatlas.tenant.internal;

/** Whether a company may be used. Stored as the enum name; see {@code tenants_status_ck}. */
public enum TenantStatus {

    /** Normal. The only state signup produces. */
    ACTIVE,

    /** Billing or compliance hold: reads allowed, writes refused. */
    SUSPENDED,

    /** Gone. Retained only for the audit trail. */
    CLOSED
}
