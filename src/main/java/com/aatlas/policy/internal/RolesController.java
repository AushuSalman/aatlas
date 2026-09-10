package com.aatlas.policy.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The eight personas, as this tenant sees them.
 *
 * <p>Not paged: the set is fixed at eight and the seat picker renders all of them at
 * once, so {@code limit} and {@code cursor} would only be ceremony here.
 */
@RestController
@RequestMapping(path = "/api/v1/roles", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Roles", description = "The eight seats and what each may do.")
class RolesController {

    private final PolicyReader policy;

    RolesController(PolicyReader policy) {
        this.policy = policy;
    }

    @Operation(summary = "The eight personas with modules and limits for this tenant")
    @GetMapping
    List<Persona> roles() {
        return policy.personasFor(TenantContext.requireTenantId());
    }
}
