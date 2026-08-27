package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.intermarche.fidelity.account.AccountViews;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityReservation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link CardDetailView}, the card-sheet view model (§23.1). The
 * class carries no clock and no Panache access: it only stores its constructor arguments and
 * normalises the notice. Every collaborator (the account summary, the movement page, the
 * reservation, the entity lists) is mocked or supplied in memory, so no boot, no H2 and no
 * static finder is involved. The two branch-bearing members are exercised on both arms: the
 * {@code notice == null ? "" : notice} ternary of the constructor (null arm and non-null
 * arm) and the {@code !notice.isEmpty()} guard of {@link CardDetailView#isHasNotice()}
 * (empty string and non-empty string).
 */
class CardDetailViewTest {

    /**
     * Asserts that a null notice is normalised to the empty string and that
     * {@link CardDetailView#isHasNotice()} then reports the absence of a notice, while every
     * other constructor argument is stored verbatim. Covers the null arm of the notice
     * ternary and the false arm (empty string) of {@code !notice.isEmpty()}.
     */
    @Test
    @DisplayName("null notice is normalised to empty and hasNotice is false")
    void nullNoticeIsNormalisedToEmpty() {
        AccountViews.Summary summary = mock(AccountViews.Summary.class);
        FidelityReservation reservation = mock(FidelityReservation.class);
        AccountViews.MovementPage movements = mock(AccountViews.MovementPage.class);
        List<FidelityActivation> activations = List.of(mock(FidelityActivation.class));
        List<FidelityCommunity> communities = List.of(mock(FidelityCommunity.class));
        CardDetailView view = new CardDetailView(summary, reservation, movements, 2, 5,
                activations, communities, null, true, true, null, false);
        assertSame(summary, view.summary);
        assertSame(reservation, view.reservation);
        assertSame(movements, view.movements);
        assertEquals(2, view.movementsPage);
        assertEquals(5, view.movementsPageCount);
        assertSame(activations, view.activations);
        assertSame(communities, view.communities);
        assertNull(view.holder);
        assertTrue(view.holderLocal);
        assertTrue(view.canWrite);
        assertEquals("", view.notice);
        assertFalse(view.noticeOk);
        assertFalse(view.isHasNotice());
    }

    /**
     * Asserts that an explicit empty-string notice is stored as-is and that
     * {@link CardDetailView#isHasNotice()} reports no notice. Covers the non-null arm of the
     * notice ternary paired with the false arm (empty string) of {@code !notice.isEmpty()}.
     */
    @Test
    @DisplayName("empty notice is kept and hasNotice is false")
    void emptyNoticeIsKeptAndHasNoticeFalse() {
        CardDetailView view = new CardDetailView(null, null, null, 0, 0,
                List.of(), List.of(), null, false, false, "", false);
        assertEquals("", view.notice);
        assertFalse(view.isHasNotice());
    }

    /**
     * Asserts that a non-empty notice is preserved and that
     * {@link CardDetailView#isHasNotice()} reports a notice, with the success flag carried
     * through. Covers the non-null arm of the notice ternary and the true arm (non-empty
     * string) of {@code !notice.isEmpty()}.
     */
    @Test
    @DisplayName("non-empty notice is preserved and hasNotice is true")
    void nonEmptyNoticeIsPreservedAndHasNoticeTrue() {
        CardDetailView view = new CardDetailView(null, null, null, 1, 1,
                List.of(), List.of(), null, false, true, "saved", true);
        assertEquals("saved", view.notice);
        assertTrue(view.noticeOk);
        assertTrue(view.isHasNotice());
    }
}
