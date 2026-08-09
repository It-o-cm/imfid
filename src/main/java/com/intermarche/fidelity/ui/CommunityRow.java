package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.domain.FidelityCommunity;

import java.math.BigDecimal;

/**
 * One row of the community list (§23.2): code, label, monthly cap, active members and the
 * renewal window.
 * <p>
 * Public fields are resolved by Qute.
 */
public final class CommunityRow {

    /**
     * The community code.
     */
    public String code;

    /**
     * The community label.
     */
    public String label;

    /**
     * The monthly cap, or null.
     */
    public BigDecimal monthlyCap;

    /**
     * The number of currently active members.
     */
    public long activeMembers;

    /**
     * The renewal window start month, or null.
     */
    public Integer renewalStartMonth;

    /**
     * The renewal window end month, or null.
     */
    public Integer renewalEndMonth;

    /**
     * Whether the community is open to new enrollments (§23.2).
     */
    public boolean active;

    /**
     * Builds a community row.
     *
     * @param community     The community entity.
     * @param activeMembers The active member count.
     * @return The community row.
     */
    public static CommunityRow of(FidelityCommunity community, long activeMembers) {
        CommunityRow row = new CommunityRow();
        row.code = community.code;
        row.label = community.label;
        row.monthlyCap = community.monthlyCap;
        row.activeMembers = activeMembers;
        row.renewalStartMonth = community.renewalStartMonth;
        row.renewalEndMonth = community.renewalEndMonth;
        row.active = community.active;
        return row;
    }
}
