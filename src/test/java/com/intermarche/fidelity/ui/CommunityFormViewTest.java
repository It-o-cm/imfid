package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityCommunity;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link CommunityFormView}, the community catalog form view model
 * (§23.2). The class carries no clock and no Panache access: {@link CommunityFormView#creation}
 * seeds the creation-mode labels, {@link CommunityFormView#edition} copies an existing
 * community through five null-guarded ternaries (monthly cap, enrollment cap, both renewal
 * months, eligibility criterion), and {@link CommunityFormView#isHasNotice()} is a compound
 * guard {@code notice != null && !notice.isBlank()}. Communities are built in memory (no boot,
 * no H2, no static finder). Both arms of every ternary and each leg of the {@code isHasNotice}
 * guard are exercised, nullities included (§29, §29.6).
 */
class CommunityFormViewTest {

    /**
     * Builds an in-memory community with every optional field populated (non-null arms).
     *
     * @return The fully populated community.
     */
    private static FidelityCommunity fullCommunity() {
        FidelityCommunity community = new FidelityCommunity();
        community.code = "BABIES";
        community.label = "Familles avec bébés";
        community.monthlyCap = new BigDecimal("30.00");
        community.enrollmentCap = 5000;
        community.renewalStartMonth = 10;
        community.renewalEndMonth = 2;
        community.eligibilityCriteria = "Carte + justificatif de naissance";
        return community;
    }

    /**
     * Builds an in-memory community with every optional field left null (empty arms).
     *
     * @return The community carrying only its mandatory code and label.
     */
    private static FidelityCommunity bareCommunity() {
        FidelityCommunity community = new FidelityCommunity();
        community.code = "STUDENTS";
        community.label = "Étudiants";
        community.monthlyCap = null;
        community.enrollmentCap = null;
        community.renewalStartMonth = null;
        community.renewalEndMonth = null;
        community.eligibilityCriteria = null;
        return community;
    }

    /**
     * Asserts that creation mode seeds the create labels, leaves edit mode off and propagates a
     * writable user. Covers the {@code canWrite == true} path of {@link CommunityFormView#creation}.
     */
    @Test
    @DisplayName("creation seeds the create-mode labels for a writable user")
    void creationWritable() {
        CommunityFormView view = CommunityFormView.creation(true);
        assertEquals("Nouvelle communauté", view.title);
        assertEquals("/ui/communities/create", view.action);
        assertEquals("Créer la communauté", view.submitLabel);
        assertFalse(view.editMode);
        assertTrue(view.canWrite);
        assertEquals("", view.code);
        assertEquals("", view.label);
        assertEquals("", view.monthlyCap);
        assertEquals("", view.enrollmentCap);
        assertEquals("", view.renewalStartMonth);
        assertEquals("", view.renewalEndMonth);
        assertEquals("", view.eligibilityCriteria);
    }

    /**
     * Asserts that creation mode propagates a read-only user. Covers the {@code canWrite == false}
     * path of {@link CommunityFormView#creation}.
     */
    @Test
    @DisplayName("creation propagates a read-only user")
    void creationReadOnly() {
        CommunityFormView view = CommunityFormView.creation(false);
        assertFalse(view.canWrite);
        assertFalse(view.editMode);
    }

    /**
     * Asserts that edition mode copies a fully populated community, exercising the non-null arm
     * of every ternary and the {@code canWrite == true} path. The monthly cap is rendered through
     * {@code toPlainString}.
     */
    @Test
    @DisplayName("edition copies a fully populated community (non-null arms)")
    void editionFull() {
        CommunityFormView view = CommunityFormView.edition(fullCommunity(), true);
        assertEquals("Paramètres de BABIES", view.title);
        assertEquals("/ui/communities/BABIES/update", view.action);
        assertEquals("Enregistrer", view.submitLabel);
        assertTrue(view.editMode);
        assertTrue(view.canWrite);
        assertEquals("BABIES", view.code);
        assertEquals("Familles avec bébés", view.label);
        assertEquals("30.00", view.monthlyCap);
        assertEquals("5000", view.enrollmentCap);
        assertEquals("10", view.renewalStartMonth);
        assertEquals("2", view.renewalEndMonth);
        assertEquals("Carte + justificatif de naissance", view.eligibilityCriteria);
    }

    /**
     * Asserts that edition mode renders every null optional field as the empty string, exercising
     * the null arm of every ternary, and propagates a read-only user ({@code canWrite == false}).
     */
    @Test
    @DisplayName("edition renders null optional fields as empty (null arms)")
    void editionBare() {
        CommunityFormView view = CommunityFormView.edition(bareCommunity(), false);
        assertTrue(view.editMode);
        assertFalse(view.canWrite);
        assertEquals("STUDENTS", view.code);
        assertEquals("Étudiants", view.label);
        assertEquals("", view.monthlyCap);
        assertEquals("", view.enrollmentCap);
        assertEquals("", view.renewalStartMonth);
        assertEquals("", view.renewalEndMonth);
        assertEquals("", view.eligibilityCriteria);
    }

    /**
     * Asserts that a null notice yields no notice. Covers the first leg
     * ({@code notice != null} false) of {@link CommunityFormView#isHasNotice()}.
     */
    @Test
    @DisplayName("isHasNotice is false when the notice is null")
    void hasNoticeNull() {
        CommunityFormView view = CommunityFormView.creation(true);
        assertNull(view.notice);
        assertFalse(view.isHasNotice());
    }

    /**
     * Asserts that a blank notice yields no notice. Covers the second leg
     * ({@code !notice.isBlank()} false) of {@link CommunityFormView#isHasNotice()}.
     */
    @Test
    @DisplayName("isHasNotice is false when the notice is blank")
    void hasNoticeBlank() {
        CommunityFormView view = CommunityFormView.creation(true);
        view.notice = "   ";
        assertFalse(view.isHasNotice());
    }

    /**
     * Asserts that a non-blank notice yields a notice. Covers the true path
     * (both legs true) of {@link CommunityFormView#isHasNotice()}.
     */
    @Test
    @DisplayName("isHasNotice is true when the notice is non-blank")
    void hasNoticePresent() {
        CommunityFormView view = CommunityFormView.creation(true);
        view.notice = "Communauté enregistrée";
        assertTrue(view.isHasNotice());
    }
}
