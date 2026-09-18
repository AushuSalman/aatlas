package com.aatlas.identity.internal;

/** Whether an account may be used. Stored as the enum name; see {@code users_status_ck}. */
enum UserStatus {
    ACTIVE,
    /** Created by an admin; no password until the invitation is accepted. */
    INVITED,
    SUSPENDED,
    /** Removed from the workspace. The row stays so their decisions keep an author. */
    DISABLED
}
