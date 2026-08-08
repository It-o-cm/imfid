package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link EarnTraceLine}: one line of the earn detail (§24.2, §29.4).
 * The class carries no clock read — {@code trace.fiscalDate} range bounds are passed-in stored
 * dates, not a {@code DateTimeProvider} call — so nothing here fixes an instant; the JPA query
 * of {@code sumBaseForRule} is driven through a mocked {@link EntityManager} obtained from the
 * Panache static {@code getEntityManager()} in a try-with-resources per the imfid unit bench,
 * and the checksum is asserted as the pure function of the business fields it is.
 */
class EarnTraceLineTest {

    // --------------------------------------------------
    // sumBaseForRule() — the sum != null ? sum : ZERO ternary, both arms
    // --------------------------------------------------

    /**
     * The non-null arm: when the aggregate query yields a sum, it is scaled to two decimals and
     * returned. Asserted by {@code compareTo} at scale 2, HALF_UP (§30.5).
     */
    @Test
    @DisplayName("sumBaseForRule(): a non-null aggregate is returned at scale 2")
    void sumBaseForRuleNonNull() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        @SuppressWarnings("unchecked")
        TypedQuery<BigDecimal> query = Mockito.mock(TypedQuery.class);
        EntityManager em = Mockito.mock(EntityManager.class);
        Mockito.when(em.createQuery(Mockito.anyString(), Mockito.eq(BigDecimal.class))).thenReturn(query);
        Mockito.when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(query);
        Mockito.when(query.getSingleResult()).thenReturn(new BigDecimal("123.4"));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::getEntityManager).thenReturn(em);
            BigDecimal result = EarnTraceLine.sumBaseForRule("CARD-1", "CHALLENGE", from, to);
            assertEquals(0, new BigDecimal("123.40").compareTo(result));
            assertEquals(2, result.scale());
        }
    }

    /**
     * The null arm: a null aggregate (a defensive belt beyond the query's {@code coalesce})
     * falls back to zero, still scaled to two decimals (§31.2, §30.5).
     */
    @Test
    @DisplayName("sumBaseForRule(): a null aggregate falls back to zero at scale 2")
    void sumBaseForRuleNull() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        @SuppressWarnings("unchecked")
        TypedQuery<BigDecimal> query = Mockito.mock(TypedQuery.class);
        EntityManager em = Mockito.mock(EntityManager.class);
        Mockito.when(em.createQuery(Mockito.anyString(), Mockito.eq(BigDecimal.class))).thenReturn(query);
        Mockito.when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(query);
        Mockito.when(query.getSingleResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::getEntityManager).thenReturn(em);
            BigDecimal result = EarnTraceLine.sumBaseForRule("CARD-1", "CHALLENGE", from, to);
            assertEquals(0, BigDecimal.ZERO.compareTo(result));
            assertEquals(2, result.scale());
        }
    }

    // --------------------------------------------------
    // getChecksum() — pure function of the five business fields
    // --------------------------------------------------

    /**
     * The checksum is a pure function of the business fields: two lines with identical
     * attributes share it.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * Changing the line id — a business field — changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a different line id alters the checksum")
    void checksumChangesWithLineId() {
        EarnTraceLine other = sample();
        other.lineId = "LINE-99";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Changing the EAN — a nullable business field — changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a different EAN alters the checksum")
    void checksumChangesWithEan() {
        EarnTraceLine other = sample();
        other.ean = "3260000000000";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A null EAN — the unresolved-line arm — still yields a stable checksum shared by two lines
     * that agree on every other field.
     */
    @Test
    @DisplayName("getChecksum(): a null EAN yields a stable checksum")
    void checksumStableWithNullEan() {
        EarnTraceLine one = sample();
        one.ean = null;
        EarnTraceLine two = sample();
        two.ean = null;
        assertEquals(one.getChecksum(), two.getChecksum());
    }

    /**
     * Changing the rule code — a business field — changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a different rule code alters the checksum")
    void checksumChangesWithRuleCode() {
        EarnTraceLine other = sample();
        other.ruleCode = "OTHER_RULE";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Changing the eligible net base — a business field — changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a different base amount alters the checksum")
    void checksumChangesWithBaseAmount() {
        EarnTraceLine other = sample();
        other.baseAmount = new BigDecimal("99.99");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Changing the earn granted — a business field — changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a different earn amount alters the checksum")
    void checksumChangesWithEarnAmount() {
        EarnTraceLine other = sample();
        other.earnAmount = new BigDecimal("8.88");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Field initializers
    // --------------------------------------------------

    /**
     * A freshly constructed line starts its returned tallies at zero: the returned earn scaled
     * to two decimals and the returned quantity at zero, so the §29.4 partial-return bound starts
     * from nothing debited.
     */
    @Test
    @DisplayName("new: returned amount and quantity default to zero")
    void returnedTalliesDefaultToZero() {
        EarnTraceLine line = new EarnTraceLine();
        assertEquals(0, BigDecimal.ZERO.compareTo(line.returnedAmount));
        assertEquals(2, line.returnedAmount.scale());
        assertEquals(0, BigDecimal.ZERO.compareTo(line.returnedQuantity));
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a fully-populated line with fixed business fields for checksum assertions.
     *
     * @return A sample line with deterministic attributes.
     */
    private EarnTraceLine sample() {
        EarnTraceLine line = new EarnTraceLine();
        line.lineId = "LINE-1";
        line.ean = "3250000000000";
        line.ruleCode = "BASE_EARN";
        line.baseAmount = new BigDecimal("12.34");
        line.earnAmount = new BigDecimal("1.23");
        return line;
    }
}
