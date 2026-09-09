package com.aatlas.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The seat contract, which is shared with a codebase this build cannot see.
 *
 * <p>The frontend's {@code Role} union, the {@code users_seat_role_ck} constraint and this
 * enum have to agree exactly. Nothing in the compiler can check that, so the wire values
 * are pinned here: changing one without changing the other two fails this test rather than
 * producing an account nobody can sign in to.
 */
class SeatRoleTest {

    /** Copied by hand from src/lib/platform/types.ts. That is the point of the test. */
    private static final Set<String> FRONTEND_ROLE_UNION = Set.of(
            "sales-rep", "seller", "sales-head",
            "purchase-manager", "buyer", "purchase-head",
            "finance", "both");

    @Test
    @DisplayName("wire values match the frontend Role union exactly")
    void wireValuesMatchTheFrontend() {
        Set<String> ours = Arrays.stream(SeatRole.values()).map(SeatRole::wireValue).collect(Collectors.toSet());
        assertThat(ours).isEqualTo(FRONTEND_ROLE_UNION);
    }

    @ParameterizedTest
    @EnumSource(SeatRole.class)
    @DisplayName("round-trips through its wire value")
    void roundTrips(SeatRole role) {
        assertThat(SeatRole.from(role.wireValue())).isSameAs(role);
    }

    @Test
    @DisplayName("an unknown seat fails instead of defaulting")
    void unknownRoleIsRejected() {
        // A default here would be an authorisation decision made by a typo.
        assertThatThrownBy(() -> SeatRole.from("superuser"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("superuser");
    }

    @Test
    @DisplayName("the authority matches what SecurityConfig builds from the role claim")
    void authorityMatchesSecurityConfig() {
        // SecurityConfig does "ROLE_" + jwt.getClaimAsString("role"), and the claim carries
        // the wire value. Any other spelling means @PreAuthorize silently matches nothing.
        for (SeatRole role : SeatRole.values()) {
            assertThat(role.authority()).isEqualTo("ROLE_" + role.wireValue());
        }
    }

    @Test
    @DisplayName("only the two seats with a ceiling carry an approval limit")
    void approvalLimitsMatchThePersonas() {
        assertThat(SeatRole.PURCHASE_MANAGER.approvalLimit()).contains(new BigDecimal("50000"));
        assertThat(SeatRole.BUYER.approvalLimit()).contains(new BigDecimal("150000"));

        // Heads approve without limit; the sell side and finance do not commit orders.
        assertThat(SeatRole.PURCHASE_HEAD.approvalLimit()).isEmpty();
        assertThat(SeatRole.SALES_HEAD.approvalLimit()).isEmpty();
        assertThat(SeatRole.FINANCE.approvalLimit()).isEmpty();
        assertThat(SeatRole.BOTH.approvalLimit()).isEmpty();
    }

    @Test
    @DisplayName("every seat has a title, because signup stores it rather than asking")
    void everySeatHasATitle() {
        for (SeatRole role : SeatRole.values()) {
            assertThat(role.title()).isNotBlank();
        }
    }
}
