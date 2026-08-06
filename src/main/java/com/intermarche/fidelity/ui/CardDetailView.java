package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.account.AccountViews;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityReservation;

import java.util.List;

/**
 * The view model of the card sheet (§23.1): the account summary, the active reservation
 * (if any), the paginated movements, the memberships and activations, plus the community
 * catalogue for the add-membership control and the write-right flag.
 * <p>
 * Getters and public fields are resolved by Qute.
 */
public final class CardDetailView {

    /**
     * The account summary (balance, counters, caps, memberships) (§27.2).
     */
    public final AccountViews.Summary summary;

    /**
     * The active burn reservation, or null (§23.1).
     */
    public final FidelityReservation reservation;

    /**
     * A page of the account's movements, most recent first (§23.1).
     */
    public final AccountViews.MovementPage movements;

    /**
     * The one-based page of the movement history.
     */
    public final int movementsPage;

    /**
     * The number of movement pages.
     */
    public final int movementsPageCount;

    /**
     * The card activations (§24.3).
     */
    public final List<FidelityActivation> activations;

    /**
     * The community catalogue, for the add-membership control.
     */
    public final List<FidelityCommunity> communities;

    /**
     * Whether the user may write (§21.4, §24.1).
     */
    public final boolean canWrite;

    /**
     * A one-shot notice, or empty.
     */
    public final String notice;

    /**
     * Whether the notice reports a success.
     */
    public final boolean noticeOk;

    /**
     * Builds the card sheet view.
     *
     * @param summary            The account summary.
     * @param reservation        The active reservation, or null.
     * @param movements          The movement page.
     * @param movementsPage      The one-based movement page.
     * @param movementsPageCount The number of movement pages.
     * @param activations        The card activations.
     * @param communities        The community catalogue.
     * @param canWrite           Whether the user may write.
     * @param notice             A one-shot notice, or null.
     * @param noticeOk           Whether the notice reports a success.
     */
    public CardDetailView(AccountViews.Summary summary, FidelityReservation reservation,
                          AccountViews.MovementPage movements, int movementsPage, int movementsPageCount,
                          List<FidelityActivation> activations, List<FidelityCommunity> communities,
                          boolean canWrite, String notice, boolean noticeOk) {
        this.summary = summary;
        this.reservation = reservation;
        this.movements = movements;
        this.movementsPage = movementsPage;
        this.movementsPageCount = movementsPageCount;
        this.activations = activations;
        this.communities = communities;
        this.canWrite = canWrite;
        this.notice = notice == null ? "" : notice;
        this.noticeOk = noticeOk;
    }

    /**
     * Indicates whether a notice must be shown.
     *
     * @return true when a notice is present.
     */
    public boolean isHasNotice() {
        return !notice.isEmpty();
    }
}
