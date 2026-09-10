/**
 * Business systems and the Model Context Protocol connection: a static catalogue (ported
 * from {@code src/lib/intel/integrations.ts}) plus each tenant's connection state.
 *
 * <p>{@code GET /integrations}, {@code POST}/{@code DELETE /integrations/{key}/connect},
 * {@code GET /mcp}, {@code POST}/{@code DELETE /mcp/clients/{key}/connect},
 * {@code GET}/{@code PUT /mcp/permissions} - migration V13's {@code integration_connection},
 * {@code mcp_client} and {@code mcp_permission} tables. Webhooks are P2 and out of scope
 * for this pass (see {@code docs/decisions.md}).
 *
 * <p>Nothing here is a stand-in: the catalogue is genuinely static reference data and the
 * connection state is a straightforward per-tenant toggle, so there is no other module's
 * engine to port.
 *
 * <p>Application module. Types in this package root are the public API other
 * modules may depend on; everything under it is internal. This module currently has no
 * public types - no other module needs to read a tenant's integration state.
 */
@org.springframework.modulith.ApplicationModule(displayName = "integrations")
package com.aatlas.integrations;
