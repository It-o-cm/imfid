package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link FidelityMovement}: one entry of the loyalty ledger (§14). The
 * class holds no clock read of its own — {@code movementDate} and {@code earnYear} are stored
 * fiscal fields, not {@code DateTimeProvider} calls — so no time is injected here; every temporal
 * bound is passed to the finders as a fixed argument. The Panache active-record aggregates are
 * driven through {@link PanacheEntityBase} static mocks in a try-with-resources per the imfid unit
 * bench: the projection/scalar finders through {@code find}/{@code count}, and the grouped
 * aggregates through a mocked {@link EntityManager} obtained from {@code getEntityManager()}. Every
 * null/non-null coalescing arm is exercised, the {@code ruleCode is null} split of the natural-key
 * finder is covered on both arms, the compound {@code ticketRef != null && count > 0} guard is
 * exercised leg by leg (§29.6), all sums are asserted at scale 2 by {@code compareTo} (§30.5), and
 * the checksum is asserted as the pure function of the business fields it is.
 */
class FidelityMovementTest {

    // --------------------------------------------------
    // earnYearOf()
    // --------------------------------------------------

    /**
     * The non-null arm of the ternary derives the civil year from the fiscal date (§30.3).
     */
    @Test
    @DisplayName("earnYearOf(): a fiscal date yields its civil year")
    void earnYearOfNonNull() {
        assertEquals(2026, FidelityMovement.earnYearOf(LocalDate.of(2026, 3, 1)));
    }

    /**
     * The null arm returns zero, so a missing fiscal date never throws (§31.2).
     */
    @Test
    @DisplayName("earnYearOf(): a null fiscal date yields zero")
    void earnYearOfNull() {
        assertEquals(0, FidelityMovement.earnYearOf(null));
    }

    /**
     * The two instants straddling the fiscal-year border resolve to distinct civil years: 31 Dec
     * belongs to the closing year, 1 Jan to the next (§30.3, §25.1).
     */
    @Test
    @DisplayName("earnYearOf(): the 31 Dec / 1 Jan border splits the civil year")
    void earnYearOfFiscalBorder() {
        assertEquals(2025, FidelityMovement.earnYearOf(LocalDate.of(2025, 12, 31)));
        assertEquals(2026, FidelityMovement.earnYearOf(LocalDate.of(2026, 1, 1)));
    }

    // --------------------------------------------------
    // computeBalance()
    // --------------------------------------------------

    /**
     * The non-null arm: a projected sum is scaled to two decimals and returned, asserted by
     * {@code compareTo} at scale 2 (§30.5).
     */
    @Test
    @DisplayName("computeBalance(): a non-null sum is returned at scale 2")
    void computeBalanceNonNull() {
        FidelityAccount account = new FidelityAccount();
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityMovement> raw = Mockito.mock(PanacheQuery.class);
        @SuppressWarnings("unchecked")
        PanacheQuery<BigDecimal> projected = Mockito.mock(PanacheQuery.class);
        Mockito.when(raw.project(BigDecimal.class)).thenReturn(projected);
        Mockito.when(projected.firstResult()).thenReturn(new BigDecimal("12.3"));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "select coalesce(sum(m.amount), 0) from FidelityMovement m where m.account = ?1",
                    account)).thenReturn(raw);
            BigDecimal balance = FidelityMovement.computeBalance(account);
            assertEquals(0, new BigDecimal("12.30").compareTo(balance));
            assertEquals(2, balance.scale());
        }
    }

    /**
     * The null arm: a null projection (an account with no movement) falls back to zero at scale 2
     * (§31.2, §30.5).
     */
    @Test
    @DisplayName("computeBalance(): a null sum falls back to zero at scale 2")
    void computeBalanceNull() {
        FidelityAccount account = new FidelityAccount();
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityMovement> raw = Mockito.mock(PanacheQuery.class);
        @SuppressWarnings("unchecked")
        PanacheQuery<BigDecimal> projected = Mockito.mock(PanacheQuery.class);
        Mockito.when(raw.project(BigDecimal.class)).thenReturn(projected);
        Mockito.when(projected.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "select coalesce(sum(m.amount), 0) from FidelityMovement m where m.account = ?1",
                    account)).thenReturn(raw);
            BigDecimal balance = FidelityMovement.computeBalance(account);
            assertEquals(0, BigDecimal.ZERO.compareTo(balance));
            assertEquals(2, balance.scale());
        }
    }

    // --------------------------------------------------
    // creditsByEarnYear()
    // --------------------------------------------------

    /**
     * Credits are grouped by earnYear at scale 2: a row with a non-null sum takes the non-null arm
     * and a row with a null sum takes the zero fallback (§31.2). Both are asserted by
     * {@code compareTo}.
     */
    @Test
    @DisplayName("creditsByEarnYear(): buckets credits per year, null sum falling back to zero")
    void creditsByEarnYearMixed() {
        FidelityAccount account = new FidelityAccount();
        List<Object[]> rows = List.of(
                new Object[]{Integer.valueOf(2024), new BigDecimal("10")},
                new Object[]{Integer.valueOf(2025), null});
        @SuppressWarnings("unchecked")
        TypedQuery<Object[]> query = Mockito.mock(TypedQuery.class);
        EntityManager em = Mockito.mock(EntityManager.class);
        Mockito.when(em.createQuery(Mockito.anyString(), ArgumentMatchers.eq(Object[].class)))
                .thenReturn(query);
        Mockito.when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(query);
        Mockito.when(query.getResultList()).thenReturn(rows);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::getEntityManager).thenReturn(em);
            Map<Integer, BigDecimal> map = FidelityMovement.creditsByEarnYear(account);
            assertEquals(2, map.size());
            assertEquals(0, new BigDecimal("10.00").compareTo(map.get(2024)));
            assertEquals(2, map.get(2024).scale());
            assertEquals(0, BigDecimal.ZERO.compareTo(map.get(2025)));
            assertEquals(2, map.get(2025).scale());
        }
    }

    // --------------------------------------------------
    // totalDebits()
    // --------------------------------------------------

    /**
     * The non-null arm: a negative debit sum is negated into a positive total at scale 2 (§28.3),
     * asserted by {@code compareTo}.
     */
    @Test
    @DisplayName("totalDebits(): a non-null sum is negated to a positive total at scale 2")
    void totalDebitsNonNull() {
        FidelityAccount account = new FidelityAccount();
        @SuppressWarnings("unchecked")
        TypedQuery<BigDecimal> query = Mockito.mock(TypedQuery.class);
        EntityManager em = Mockito.mock(EntityManager.class);
        Mockito.when(em.createQuery(Mockito.anyString(), ArgumentMatchers.eq(BigDecimal.class)))
                .thenReturn(query);
        Mockito.when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(query);
        Mockito.when(query.getSingleResult()).thenReturn(new BigDecimal("-7.5"));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::getEntityManager).thenReturn(em);
            BigDecimal debits = FidelityMovement.totalDebits(account);
            assertEquals(0, new BigDecimal("7.50").compareTo(debits));
            assertEquals(2, debits.scale());
        }
    }

    /**
     * The null arm: a null sum (no debit) falls back to zero at scale 2 (§31.2, §30.5).
     */
    @Test
    @DisplayName("totalDebits(): a null sum falls back to zero at scale 2")
    void totalDebitsNull() {
        FidelityAccount account = new FidelityAccount();
        @SuppressWarnings("unchecked")
        TypedQuery<BigDecimal> query = Mockito.mock(TypedQuery.class);
        EntityManager em = Mockito.mock(EntityManager.class);
        Mockito.when(em.createQuery(Mockito.anyString(), ArgumentMatchers.eq(BigDecimal.class)))
                .thenReturn(query);
        Mockito.when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(query);
        Mockito.when(query.getSingleResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::getEntityManager).thenReturn(em);
            BigDecimal debits = FidelityMovement.totalDebits(account);
            assertEquals(0, BigDecimal.ZERO.compareTo(debits));
            assertEquals(2, debits.scale());
        }
    }

    // --------------------------------------------------
    // findByNaturalKey()
    // --------------------------------------------------

    /**
     * The null-ruleCode arm queries the {@code ruleCode is null} predicate and returns its first
     * result (§29.4).
     */
    @Test
    @DisplayName("findByNaturalKey(): a null rule code matches the null-rule predicate")
    void findByNaturalKeyNullRule() {
        FidelityMovement found = new FidelityMovement();
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityMovement> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "ticketRef = ?1 and type = ?2 and ruleCode is null",
                    "T-1", MovementType.EXPIRY)).thenReturn(query);
            assertSame(found, FidelityMovement.findByNaturalKey("T-1", MovementType.EXPIRY, null));
        }
    }

    /**
     * The non-null-ruleCode arm queries the {@code ruleCode = ?3} predicate and returns its first
     * result; here the card has no such movement yet, so it returns null (§29.4).
     */
    @Test
    @DisplayName("findByNaturalKey(): a rule code matches the rule predicate")
    void findByNaturalKeyWithRule() {
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityMovement> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "ticketRef = ?1 and type = ?2 and ruleCode = ?3",
                    "T-1", MovementType.EARN, "RULE_A")).thenReturn(query);
            assertNull(FidelityMovement.findByNaturalKey("T-1", MovementType.EARN, "RULE_A"));
        }
    }

    // --------------------------------------------------
    // monthlyEarnByRule()
    // --------------------------------------------------

    /**
     * The per-rule cap cumulative: a null rule code is skipped by the {@code continue}, a non-null
     * sum takes the non-null arm and a null sum the zero fallback, all at scale 2 (§15, I5, §31.2).
     */
    @Test
    @DisplayName("monthlyEarnByRule(): skips null codes, buckets earn per rule at scale 2")
    void monthlyEarnByRuleMixed() {
        FidelityAccount account = new FidelityAccount();
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);
        List<Object[]> rows = List.of(
                new Object[]{null, new BigDecimal("5")},
                new Object[]{"RULE_A", new BigDecimal("12.34")},
                new Object[]{"RULE_B", null});
        @SuppressWarnings("unchecked")
        TypedQuery<Object[]> query = Mockito.mock(TypedQuery.class);
        EntityManager em = Mockito.mock(EntityManager.class);
        Mockito.when(em.createQuery(Mockito.anyString(), ArgumentMatchers.eq(Object[].class)))
                .thenReturn(query);
        Mockito.when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(query);
        Mockito.when(query.getResultList()).thenReturn(rows);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::getEntityManager).thenReturn(em);
            Map<String, BigDecimal> map = FidelityMovement.monthlyEarnByRule(account, from, to);
            assertEquals(2, map.size());
            assertFalse(map.containsKey(null));
            assertEquals(0, new BigDecimal("12.34").compareTo(map.get("RULE_A")));
            assertEquals(2, map.get("RULE_A").scale());
            assertEquals(0, BigDecimal.ZERO.compareTo(map.get("RULE_B")));
            assertEquals(2, map.get("RULE_B").scale());
        }
    }

    // --------------------------------------------------
    // monthlyEarnTotal()
    // --------------------------------------------------

    /**
     * The non-null arm: the global cap cumulative is returned at scale 2 (§15, I5), asserted by
     * {@code compareTo}.
     */
    @Test
    @DisplayName("monthlyEarnTotal(): a non-null sum is returned at scale 2")
    void monthlyEarnTotalNonNull() {
        FidelityAccount account = new FidelityAccount();
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);
        @SuppressWarnings("unchecked")
        TypedQuery<BigDecimal> query = Mockito.mock(TypedQuery.class);
        EntityManager em = Mockito.mock(EntityManager.class);
        Mockito.when(em.createQuery(Mockito.anyString(), ArgumentMatchers.eq(BigDecimal.class)))
                .thenReturn(query);
        Mockito.when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(query);
        Mockito.when(query.getSingleResult()).thenReturn(new BigDecimal("400"));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::getEntityManager).thenReturn(em);
            BigDecimal total = FidelityMovement.monthlyEarnTotal(account, from, to);
            assertEquals(0, new BigDecimal("400.00").compareTo(total));
            assertEquals(2, total.scale());
        }
    }

    /**
     * The null arm: a null sum (no earn that month) falls back to zero at scale 2 (§31.2, §30.5).
     */
    @Test
    @DisplayName("monthlyEarnTotal(): a null sum falls back to zero at scale 2")
    void monthlyEarnTotalNull() {
        FidelityAccount account = new FidelityAccount();
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);
        @SuppressWarnings("unchecked")
        TypedQuery<BigDecimal> query = Mockito.mock(TypedQuery.class);
        EntityManager em = Mockito.mock(EntityManager.class);
        Mockito.when(em.createQuery(Mockito.anyString(), ArgumentMatchers.eq(BigDecimal.class)))
                .thenReturn(query);
        Mockito.when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(query);
        Mockito.when(query.getSingleResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::getEntityManager).thenReturn(em);
            BigDecimal total = FidelityMovement.monthlyEarnTotal(account, from, to);
            assertEquals(0, BigDecimal.ZERO.compareTo(total));
            assertEquals(2, total.scale());
        }
    }

    // --------------------------------------------------
    // hasMovementForTicket()
    // --------------------------------------------------

    /**
     * The first leg false: a null ticket reference short-circuits to false without a count query
     * (§29.4).
     */
    @Test
    @DisplayName("hasMovementForTicket(): a null ticket short-circuits to false")
    void hasMovementForTicketNullRef() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            assertFalse(FidelityMovement.hasMovementForTicket(null, MovementType.RETURN_DEBIT));
            panache.verifyNoInteractions();
        }
    }

    /**
     * The first leg true, second leg false: a known ticket with no such movement counts zero and
     * returns false (§29.4).
     */
    @Test
    @DisplayName("hasMovementForTicket(): a ticket with no movement returns false")
    void hasMovementForTicketZeroCount() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(
                    "ticketRef = ?1 and type = ?2", "R-1", MovementType.RETURN_DEBIT)).thenReturn(0L);
            assertFalse(FidelityMovement.hasMovementForTicket("R-1", MovementType.RETURN_DEBIT));
        }
    }

    /**
     * Both legs true: a known ticket that already carries the movement counts positive and returns
     * true, the whole-ticket idempotency guard firing (§29.4).
     */
    @Test
    @DisplayName("hasMovementForTicket(): an existing movement returns true")
    void hasMovementForTicketPositiveCount() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(
                    "ticketRef = ?1 and type = ?2", "R-1", MovementType.RETURN_DEBIT)).thenReturn(1L);
            assertTrue(FidelityMovement.hasMovementForTicket("R-1", MovementType.RETURN_DEBIT));
        }
    }

    // --------------------------------------------------
    // hasBurnOn()
    // --------------------------------------------------

    /**
     * The false arm: no confirmed burn that day counts zero and returns false (§16, §25.5).
     */
    @Test
    @DisplayName("hasBurnOn(): no burn that day returns false")
    void hasBurnOnAbsent() {
        FidelityAccount account = new FidelityAccount();
        LocalDate date = LocalDate.of(2026, 3, 1);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(
                    "account = ?1 and type = ?2 and movementDate = ?3",
                    account, MovementType.BURN, date)).thenReturn(0L);
            assertFalse(FidelityMovement.hasBurnOn(account, date));
        }
    }

    /**
     * The true arm: a confirmed BURN that day counts positive and returns true, the once-per-day
     * rule firing (§16, §25.5).
     */
    @Test
    @DisplayName("hasBurnOn(): an existing burn that day returns true")
    void hasBurnOnPresent() {
        FidelityAccount account = new FidelityAccount();
        LocalDate date = LocalDate.of(2026, 3, 1);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(
                    "account = ?1 and type = ?2 and movementDate = ?3",
                    account, MovementType.BURN, date)).thenReturn(2L);
            assertTrue(FidelityMovement.hasBurnOn(account, date));
        }
    }

    // --------------------------------------------------
    // pageForAccount()
    // --------------------------------------------------

    /**
     * The paging finder orders newest-first, pages to the requested size and returns its list.
     */
    @Test
    @DisplayName("pageForAccount(): pages the newest-first query and returns its list")
    void pageForAccountReturnsList() {
        FidelityAccount account = new FidelityAccount();
        List<FidelityMovement> movements = List.of(new FidelityMovement(), new FidelityMovement());
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityMovement> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.page(ArgumentMatchers.any(Page.class))).thenReturn(query);
        Mockito.when(query.list()).thenReturn(movements);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 order by movementDate desc, id desc", account)).thenReturn(query);
            assertEquals(movements, FidelityMovement.pageForAccount(account, 1, 20));
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The non-null-account arm folds the account card number into the checksum: two movements with
     * identical business fields share it.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * Changing any business field — here the amount — changes the checksum, so any movement
     * divergence is detected. Different {@code BigDecimal} scales are distinct business states.
     */
    @Test
    @DisplayName("getChecksum(): a different amount alters the checksum")
    void checksumChangesWithAmount() {
        FidelityMovement other = sample();
        other.amount = new BigDecimal("99.99");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * The null-account arm folds a null card number: a movement whose account is not yet attached
     * still computes a checksum without throwing, and it differs from the attached sample.
     */
    @Test
    @DisplayName("getChecksum(): a null account folds a null card number")
    void checksumNullAccount() {
        FidelityMovement detached = sample();
        detached.account = null;
        FidelityMovement detachedTwin = sample();
        detachedTwin.account = null;
        assertEquals(detached.getChecksum(), detachedTwin.getChecksum());
        assertNotEquals(sample().getChecksum(), detached.getChecksum());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a fully-populated movement with fixed business fields for checksum assertions.
     *
     * @return A sample movement with deterministic attributes and an attached account.
     */
    private FidelityMovement sample() {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = "3245070000001";
        FidelityMovement movement = new FidelityMovement();
        movement.account = account;
        movement.type = MovementType.EARN;
        movement.amount = new BigDecimal("12.34");
        movement.movementDate = LocalDate.of(2026, 3, 1);
        movement.earnYear = 2026;
        movement.ruleCode = "RULE_A";
        movement.ticketRef = "T-1";
        movement.reason = null;
        return movement;
    }
}
