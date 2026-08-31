package com.intermarche.fidelity.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.CardHolder;
import com.intermarche.fidelity.domain.FidelityAccount;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link CardLookupResource} — the POS fallback identity search (§33.3).
 * Both arms of the single endpoint guard are exercised: the {@code try} arm mapping a match list
 * to 200 (a non-empty list mapped through {@link CardLookupResource.Match#of(CardHolder)} and an
 * empty list) and the {@code catch} arm turning an {@link AdminException} into a 422
 * {@link CardLookupResource.Rejection}. The {@code Match.of} projection and the {@code Rejection}
 * body are asserted directly.
 * <p>
 * Fully isolated: the {@link HolderService} collaborator is mocked and wired on the
 * package-private field. The 401 without authentication is enforced by the {@code @RolesAllowed}
 * pos guard attached in the security build step (§24.1) and is covered by the e2e tier, not by
 * this plain unit run. No clock is read (§24.6): the endpoint holds no temporal logic.
 */
class CardLookupResourceTest {

    /**
     * The system under test, freshly built per test with a mocked service.
     */
    private CardLookupResource resource;

    /**
     * The mocked holder directory service (§33.3).
     */
    private HolderService holders;

    /**
     * Builds a fresh resource and wires the mocked service before each test.
     */
    @BeforeEach
    void setUp() {
        resource = new CardLookupResource();
        holders = Mockito.mock(HolderService.class);
        resource.holders = holders;
    }

    /**
     * Builds a holder carrying an account and identity, ready for the {@code Match.of} projection.
     *
     * @param card      The card number.
     * @param status    The account status.
     * @param lastName  The holder's last name.
     * @param firstName The holder's first name, or null.
     * @return The holder.
     */
    private CardHolder holder(String card, AccountStatus status, String lastName, String firstName) {
        FidelityAccount account = Mockito.mock(FidelityAccount.class);
        account.cardNumber = card;
        account.status = status;
        CardHolder holder = new CardHolder();
        holder.account = account;
        holder.lastName = lastName;
        holder.firstName = firstName;
        return holder;
    }

    /**
     * A resolvable criterion yields 200 with the matches projected to the wire DTO — the
     * {@code try} arm — carrying the card, status name, last and first names of each holder.
     */
    @Test
    @DisplayName("lookup: matches → 200 with the projected list")
    void lookupReturns200WithMatches() {
        CardHolder marie = holder("2990000000019", AccountStatus.ACTIVE, "Durand", "Marie");
        CardHolder jacques = holder("2990000000057", AccountStatus.RESILIATED, "Durand", "Jacques");
        Mockito.when(holders.lookup("06", null, "durand", null)).thenReturn(List.of(marie, jacques));
        Response response = resource.lookup("06", null, "durand", null);
        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        List<CardLookupResource.Match> body = (List<CardLookupResource.Match>) response.getEntity();
        assertEquals(2, body.size());
        assertEquals("2990000000019", body.get(0).card);
        assertEquals("ACTIVE", body.get(0).status);
        assertEquals("Durand", body.get(0).lastName);
        assertEquals("Marie", body.get(0).firstName);
        assertEquals("2990000000057", body.get(1).card);
        assertEquals("RESILIATED", body.get(1).status);
    }

    /**
     * A resolvable criterion with no match yields 200 with an empty list — the {@code try} arm on
     * an empty result.
     */
    @Test
    @DisplayName("lookup: no match → 200 with an empty list")
    void lookupReturns200WithEmptyList() {
        Mockito.when(holders.lookup(null, "none@example.fr", null, null)).thenReturn(List.of());
        Response response = resource.lookup(null, "none@example.fr", null, null);
        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        List<CardLookupResource.Match> body = (List<CardLookupResource.Match>) response.getEntity();
        assertTrue(body.isEmpty());
    }

    /**
     * A refused lookup (no criterion or CRM-managed) yields 422 carrying the refusal reason — the
     * {@code catch} arm mapping the {@link AdminException} message into a {@link
     * CardLookupResource.Rejection}.
     */
    @Test
    @DisplayName("lookup: refused → 422 with the reason")
    void lookupReturns422OnRejection() {
        Mockito.when(holders.lookup(null, null, null, null))
                .thenThrow(new AdminException("A phone, an e-mail or a name is required (§33.3)"));
        Response response = resource.lookup(null, null, null, null);
        assertEquals(422, response.getStatus());
        CardLookupResource.Rejection body = (CardLookupResource.Rejection) response.getEntity();
        assertEquals("A phone, an e-mail or a name is required (§33.3)", body.reason);
    }

    /**
     * {@code Match.of} projects a holder's card, status name and identity into the wire DTO.
     */
    @Test
    @DisplayName("Match.of: projects the holder identity")
    void matchOfProjectsHolder() {
        CardHolder holder = holder("2990000000019", AccountStatus.ACTIVE, "García", "Ana");
        CardLookupResource.Match match = CardLookupResource.Match.of(holder);
        assertEquals("2990000000019", match.card);
        assertEquals("ACTIVE", match.status);
        assertEquals("García", match.lastName);
        assertEquals("Ana", match.firstName);
    }

    /**
     * {@code Rejection} carries the refusal reason verbatim.
     */
    @Test
    @DisplayName("Rejection: carries the reason")
    void rejectionCarriesReason() {
        CardLookupResource.Rejection rejection = new CardLookupResource.Rejection("Holder identity is managed by the CRM (§33.3)");
        assertEquals("Holder identity is managed by the CRM (§33.3)", rejection.reason);
    }
}
