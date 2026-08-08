package com.intermarche.fidelity.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.account.AccountViews.CapView;
import com.intermarche.fidelity.account.AccountViews.MembershipView;
import com.intermarche.fidelity.account.AccountViews.MovementPage;
import com.intermarche.fidelity.account.AccountViews.MovementView;
import com.intermarche.fidelity.account.AccountViews.Summary;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link AccountViews}: the §27.2 read DTOs of the account API.
 * The class carries no conditional logic — it is a non-instantiable holder of plain
 * carriers — so coverage exercises the private holder constructor (via reflection), the
 * two value constructors ({@link CapView}, {@link MembershipView}), the never-null
 * collection initializers of {@link Summary} and {@link MovementPage}, and the mutable
 * fields of {@link Summary} and {@link MovementView}. Every {@link BigDecimal} is asserted
 * by {@code compareTo} at scale 2 (§30.5). No clock, no Panache: pure in-memory carriers.
 */
class AccountViewsTest {

    /**
     * The private holder constructor is invocable by reflection and yields an instance,
     * covering the non-instantiable {@code AccountViews()} line.
     *
     * @throws Exception If the reflective instantiation fails.
     */
    @Test
    @DisplayName("Private holder constructor is reachable and private")
    void privateHolderConstructor() throws Exception {
        Constructor<AccountViews> constructor = AccountViews.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        AccountViews instance = constructor.newInstance();
        assertNotNull(instance);
    }

    /**
     * A fresh {@link Summary} exposes non-null empty collections and default scalars,
     * and accepts assignment on every mutable field.
     */
    @Test
    @DisplayName("Summary defaults are non-null collections and mutable fields carry values")
    void summaryDefaultsAndFields() {
        Summary summary = new Summary();
        assertNotNull(summary.monthlyCaps);
        assertNotNull(summary.memberships);
        assertTrue(summary.monthlyCaps.isEmpty());
        assertTrue(summary.memberships.isEmpty());
        assertEquals(0, summary.monthVisits);
        summary.cardNumber = "CARD-1";
        summary.status = "ACTIVE";
        summary.balance = new BigDecimal("12.50");
        summary.availableBalance = new BigDecimal("10.00");
        summary.monthVisits = 3;
        CapView cap = new CapView("GLOBAL", new BigDecimal("50.00"), new BigDecimal("5.00"));
        MembershipView membership = new MembershipView("C1", LocalDate.of(2026, 1, 1), null);
        summary.monthlyCaps.add(cap);
        summary.memberships.add(membership);
        assertEquals("CARD-1", summary.cardNumber);
        assertEquals("ACTIVE", summary.status);
        assertEquals(0, new BigDecimal("12.50").compareTo(summary.balance));
        assertEquals(0, new BigDecimal("10.00").compareTo(summary.availableBalance));
        assertEquals(3, summary.monthVisits);
        assertSame(cap, summary.monthlyCaps.get(0));
        assertSame(membership, summary.memberships.get(0));
    }

    /**
     * The {@link CapView} constructor stores scope and both amounts verbatim, amounts
     * compared at scale 2.
     */
    @Test
    @DisplayName("CapView constructor carries scope, cap and used")
    void capViewConstructor() {
        CapView cap = new CapView("RULE:R1", new BigDecimal("40.00"), new BigDecimal("13.37"));
        assertEquals("RULE:R1", cap.scope);
        assertEquals(0, new BigDecimal("40.00").compareTo(cap.cap));
        assertEquals(0, new BigDecimal("13.37").compareTo(cap.used));
    }

    /**
     * The {@link MembershipView} constructor stores the community and both dates verbatim,
     * with a non-null end date.
     */
    @Test
    @DisplayName("MembershipView constructor carries community and both dates when closed")
    void membershipViewClosed() {
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        MembershipView membership = new MembershipView("COMMUNITY:C9", from, to);
        assertEquals("COMMUNITY:C9", membership.community);
        assertSame(from, membership.validFrom);
        assertSame(to, membership.validTo);
    }

    /**
     * The {@link MembershipView} constructor accepts a null end date while the membership
     * is open.
     */
    @Test
    @DisplayName("MembershipView constructor accepts a null end date when open")
    void membershipViewOpen() {
        LocalDate from = LocalDate.of(2026, 3, 1);
        MembershipView membership = new MembershipView("C2", from, null);
        assertEquals("C2", membership.community);
        assertSame(from, membership.validFrom);
        assertNull(membership.validTo);
    }

    /**
     * A fresh {@link MovementPage} exposes a non-null empty item list and a default total
     * count, and accepts assignment of the total and rows.
     */
    @Test
    @DisplayName("MovementPage defaults to zero count and non-null empty items")
    void movementPageDefaultsAndFields() {
        MovementPage page = new MovementPage();
        assertNotNull(page.items);
        assertTrue(page.items.isEmpty());
        assertEquals(0L, page.totalCount);
        MovementView row = new MovementView();
        page.totalCount = 7L;
        page.items.add(row);
        assertEquals(7L, page.totalCount);
        assertSame(row, page.items.get(0));
    }

    /**
     * A {@link MovementView} carries every mutable field, amount compared at scale 2, with
     * the optional ticket, rule and reason set.
     */
    @Test
    @DisplayName("MovementView carries date, type, amount and optional fields when set")
    void movementViewFieldsSet() {
        MovementView row = new MovementView();
        LocalDate date = LocalDate.of(2026, 8, 8);
        row.date = date;
        row.type = "EARN";
        row.amount = new BigDecimal("2.00");
        row.ticketRef = "T-1";
        row.ruleCode = "R1";
        row.reason = "ADJ";
        assertSame(date, row.date);
        assertEquals("EARN", row.type);
        assertEquals(0, new BigDecimal("2.00").compareTo(row.amount));
        assertEquals("T-1", row.ticketRef);
        assertEquals("R1", row.ruleCode);
        assertEquals("ADJ", row.reason);
    }

    /**
     * A {@link MovementView} leaves the optional ticket, rule and reason null when unset
     * (§29.4, §32.1), and a negative signed amount is carried verbatim.
     */
    @Test
    @DisplayName("MovementView leaves optional fields null and carries a signed amount")
    void movementViewOptionalNull() {
        MovementView row = new MovementView();
        row.type = "ADJUSTMENT";
        row.amount = new BigDecimal("-3.50");
        assertNull(row.date);
        assertNull(row.ticketRef);
        assertNull(row.ruleCode);
        assertNull(row.reason);
        assertEquals(0, new BigDecimal("-3.50").compareTo(row.amount));
    }
}
