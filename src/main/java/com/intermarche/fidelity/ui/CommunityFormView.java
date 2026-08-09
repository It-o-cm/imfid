package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.domain.FidelityCommunity;

/**
 * The view model of the community catalog form (§23.2), serving its two modes:
 * creation of a community and edition of its parameters (code frozen).
 * <p>
 * Public fields and getters are resolved by Qute.
 */
public final class CommunityFormView {

    /**
     * The page title ("Nouvelle communauté" or "Paramètres de X").
     */
    public String title;

    /**
     * The form POST action.
     */
    public String action;

    /**
     * The submit button label.
     */
    public String submitLabel;

    /**
     * Whether the form edits an existing community (code frozen).
     */
    public boolean editMode;

    /**
     * The community code.
     */
    public String code = "";

    /**
     * The human label.
     */
    public String label = "";

    /**
     * The per-card monthly cap in euro, or empty when none.
     */
    public String monthlyCap = "";

    /**
     * The enrollment cap, or empty when unbounded.
     */
    public String enrollmentCap = "";

    /**
     * First month (1-12) of the renewal window, or empty.
     */
    public String renewalStartMonth = "";

    /**
     * Last month (1-12) of the renewal window, or empty.
     */
    public String renewalEndMonth = "";

    /**
     * The descriptive eligibility criterion, or empty.
     */
    public String eligibilityCriteria = "";

    /**
     * Whether the signed-in user may write (§21.4).
     */
    public boolean canWrite;

    /**
     * The one-shot notice, or null.
     */
    public String notice;

    /**
     * Whether the notice reports a success.
     */
    public boolean noticeOk;

    /**
     * Builds the creation-mode view.
     *
     * @param canWrite Whether the user may write.
     * @return The creation-mode view.
     */
    public static CommunityFormView creation(boolean canWrite) {
        CommunityFormView view = new CommunityFormView();
        view.title = "Nouvelle communauté";
        view.action = "/ui/communities/create";
        view.submitLabel = "Créer la communauté";
        view.editMode = false;
        view.canWrite = canWrite;
        return view;
    }

    /**
     * Builds the edition-mode view from an existing community.
     *
     * @param community The community to edit.
     * @param canWrite  Whether the user may write.
     * @return The edition-mode view.
     */
    public static CommunityFormView edition(FidelityCommunity community, boolean canWrite) {
        CommunityFormView view = new CommunityFormView();
        view.title = "Paramètres de " + community.code;
        view.action = "/ui/communities/" + community.code + "/update";
        view.submitLabel = "Enregistrer";
        view.editMode = true;
        view.code = community.code;
        view.label = community.label;
        view.monthlyCap = community.monthlyCap != null ? community.monthlyCap.toPlainString() : "";
        view.enrollmentCap = community.enrollmentCap != null ? String.valueOf(community.enrollmentCap) : "";
        view.renewalStartMonth = community.renewalStartMonth != null ? String.valueOf(community.renewalStartMonth) : "";
        view.renewalEndMonth = community.renewalEndMonth != null ? String.valueOf(community.renewalEndMonth) : "";
        view.eligibilityCriteria = community.eligibilityCriteria != null ? community.eligibilityCriteria : "";
        view.canWrite = canWrite;
        return view;
    }

    /**
     * Returns whether a notice must be displayed.
     *
     * @return true when a notice is present.
     */
    public boolean isHasNotice() {
        return notice != null && !notice.isBlank();
    }
}
