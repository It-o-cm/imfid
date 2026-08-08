package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityCommunity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link CommunityRow}, the community-list row view model (§23.2). The
 * class carries no clock and no Panache access: {@link CommunityRow#of} copies the community's
 * business fields verbatim into a flat row resolved by Qute. Communities are built in memory (no
 * boot, no H2, no static finder). The mapper has no guard nor ternary, so coverage is driven by
 * two contexts: a fully populated, active community and a bare, inactive one whose nullable fields
 * (monthly cap and both renewal months) are null.
 */
class CommunityRowTest {

    /**
     * Builds an in-memory community with the supplied nullable fields and active flag.
     *
     * @param monthlyCap        The monthly cap, or null.
     * @param renewalStartMonth The renewal window start month, or null.
     * @param renewalEndMonth   The renewal window end month, or null.
     * @param active            Whether the community is open to new enrollments.
     * @return The community, its business fields populated.
     */
    private static FidelityCommunity community(BigDecimal monthlyCap, Integer renewalStartMonth,
            Integer renewalEndMonth, boolean active) {
        FidelityCommunity community = new FidelityCommunity();
        community.code = "LARGE_FAMILIES";
        community.label = "Large Families";
        community.monthlyCap = monthlyCap;
        community.renewalStartMonth = renewalStartMonth;
        community.renewalEndMonth = renewalEndMonth;
        community.active = active;
        return community;
    }

    /**
     * Asserts that {@link CommunityRow#of} copies every business field of a fully populated,
     * active community and stores the supplied active-member count. The monthly cap is compared by
     * {@code compareTo} so scale does not distort the assertion.
     */
    @Test
    @DisplayName("of copies a populated active community verbatim")
    void ofCopiesPopulatedCommunity() {
        FidelityCommunity community =
                community(new BigDecimal("30.00").setScale(2, RoundingMode.HALF_UP), 10, 2, true);
        CommunityRow row = CommunityRow.of(community, 42L);
        assertEquals("LARGE_FAMILIES", row.code);
        assertEquals("Large Families", row.label);
        assertEquals(0, new BigDecimal("30.00").compareTo(row.monthlyCap));
        assertEquals(42L, row.activeMembers);
        assertEquals(10, row.renewalStartMonth);
        assertEquals(2, row.renewalEndMonth);
        assertTrue(row.active);
    }

    /**
     * Asserts that {@link CommunityRow#of} preserves the null nullable fields of a bare, inactive
     * community and stores a zero active-member count, the absent values the template renders as em
     * dashes (§23.2).
     */
    @Test
    @DisplayName("of preserves null cap and renewal window on an inactive community")
    void ofPreservesNullFields() {
        FidelityCommunity community = community(null, null, null, false);
        CommunityRow row = CommunityRow.of(community, 0L);
        assertEquals("LARGE_FAMILIES", row.code);
        assertEquals("Large Families", row.label);
        assertNull(row.monthlyCap);
        assertEquals(0L, row.activeMembers);
        assertNull(row.renewalStartMonth);
        assertNull(row.renewalEndMonth);
        assertFalse(row.active);
    }
}
