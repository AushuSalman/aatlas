/**
 * The shared kernel: tenant context, errors, money, the clock, domain-event contracts,
 * cache names and the JPA base classes.
 *
 * <p>A shared module, and deliberately thin. Anything that knows what a price or a
 * supplier is belongs in a business module, not here.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "common",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.aatlas.common;
