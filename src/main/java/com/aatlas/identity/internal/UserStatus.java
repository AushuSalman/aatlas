package com.aatlas.identity.internal;

/** Whether an account may be used. Stored as the enum name; see {@code users_status_ck}. */
public enum UserStatus {

    /** Normal. The only state signup produces. */
    ACTIVE,

    /** Temporarily barred by an administrator. Reversible. */
    SUSPENDED,

    /** Deactivated for good. Kept so past decisions still name their author. */
    DISABLED
}
