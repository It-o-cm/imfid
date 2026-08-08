package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link FidelityCommunity}: the loyalty community reference carrying
 * the monthly cap, enrollment cap and renewal window that govern its memberships (§13). The
 * class holds no temporal logic, so nothing here reads a {@code DateTimeProvider}; the two
 * Panache active-record finders are mocked through {@link PanacheEntityBase} in a
 * try-with-resources per the imfid unit bench, and {@link FidelityCommunity#getChecksum()} is
 * asserted as a pure function of the business fields, each field driving detection on its own.
 */
class FidelityCommunityTest {

    // --------------------------------------------------
    // findByCode()
    // --------------------------------------------------

    /**
     * The code finder delegates to the Panache query and returns its first result.
     */
    @Test
    @DisplayName("findByCode(): returns the matching community")
    void findByCodeFound() {
        FidelityCommunity found = new FidelityCommunity();
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityCommunity> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "BABIES")).thenReturn(query);
            assertSame(found, FidelityCommunity.findByCode("BABIES"));
        }
    }

    /**
     * The code finder returns null when the query yields nothing.
     */
    @Test
    @DisplayName("findByCode(): returns null when absent")
    void findByCodeAbsent() {
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityCommunity> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "GHOST")).thenReturn(query);
            assertNull(FidelityCommunity.findByCode("GHOST"));
        }
    }

    // --------------------------------------------------
    // listAllByCode()
    // --------------------------------------------------

    /**
     * The listing delegates to the ordered Panache list and returns it verbatim.
     */
    @Test
    @DisplayName("listAllByCode(): delegates to the ordered Panache list")
    void listAllByCodeDelegates() {
        List<FidelityCommunity> communities = List.of(new FidelityCommunity(), new FidelityCommunity());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by code")).thenReturn(communities);
            assertSame(communities, FidelityCommunity.listAllByCode());
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The checksum is a pure function of the business fields: two communities with identical
     * attributes share it.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * A different code changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a code change alters the checksum")
    void checksumChangesWithCode() {
        FidelityCommunity other = sample();
        other.code = "STUDENTS";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different label changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a label change alters the checksum")
    void checksumChangesWithLabel() {
        FidelityCommunity other = sample();
        other.label = "Large Families";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different monthly cap changes the checksum; the {@link BigDecimal} field participates in
     * the hash like any other business attribute.
     */
    @Test
    @DisplayName("getChecksum(): a monthly-cap change alters the checksum")
    void checksumChangesWithMonthlyCap() {
        FidelityCommunity other = sample();
        other.monthlyCap = new BigDecimal("20.00");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different enrollment cap changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): an enrollment-cap change alters the checksum")
    void checksumChangesWithEnrollmentCap() {
        FidelityCommunity other = sample();
        other.enrollmentCap = 5000;
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different renewal start month changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a renewal-start-month change alters the checksum")
    void checksumChangesWithRenewalStartMonth() {
        FidelityCommunity other = sample();
        other.renewalStartMonth = 2;
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different renewal end month changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a renewal-end-month change alters the checksum")
    void checksumChangesWithRenewalEndMonth() {
        FidelityCommunity other = sample();
        other.renewalEndMonth = 3;
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different eligibility criterion changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): an eligibility-criteria change alters the checksum")
    void checksumChangesWithEligibilityCriteria() {
        FidelityCommunity other = sample();
        other.eligibilityCriteria = "Enrolled students with a valid card";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Toggling the active flag changes the checksum, so an enrollment closure is detected like
     * any other modification.
     */
    @Test
    @DisplayName("getChecksum(): an active-flag change alters the checksum")
    void checksumChangesWithActive() {
        FidelityCommunity other = sample();
        other.active = false;
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a fully-populated community with fixed business fields for checksum assertions.
     *
     * @return A sample community with deterministic attributes.
     */
    private FidelityCommunity sample() {
        FidelityCommunity community = new FidelityCommunity();
        community.code = "BABIES";
        community.label = "Babies";
        community.monthlyCap = new BigDecimal("30.00");
        community.enrollmentCap = 10000;
        community.renewalStartMonth = 10;
        community.renewalEndMonth = 1;
        community.eligibilityCriteria = "Parents of a child under three";
        community.active = true;
        return community;
    }
}
