package com.intermarche.fidelity.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.intermarche.fidelity.domain.BatchRunLog;
import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.EarnTraceLine;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.domain.ProductFamily;
import com.intermarche.fidelity.domain.PendingReturn;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link DataInitializer}: the dev/test loyalty seeder that wipes and
 * reloads the whole 2026 program world at startup (§24.4). No Quarkus, no H2, no database boot.
 * <p>
 * The seeder has no injected collaborator — it drives static Panache operations and constructs
 * every domain entity directly. Every {@code new <Entity>()} is neutralized with
 * {@code mockConstruction} so no {@code persist()} ever reaches an absent session (the
 * {@link ProductFamily} initializer keeps its {@code products} set live because the seeder
 * writes through it), the inherited static finders ({@code count}/{@code deleteAll}/{@code
 * listAll}) are intercepted on {@link PanacheEntityBase}, the native wipe queries through a
 * mocked {@link Panache#getEntityManager()}, and the two custom statics ({@link
 * FidelityCommunity#findByCode(String)}, {@link FidelityMovement#computeBalance(FidelityAccount)})
 * on their own classes. The clock is frozen through {@link DateTimeProvider} (§24.6) so the
 * month-anchored windows resolve to a fixed instant, never the campaign day.
 * <p>
 * The single {@code onStart} run asserts the exact cardinality of the seeded world (settings,
 * communities, families, products, rules, accounts, movements, memberships, activations,
 * reservations, traces, lines, held return, batch runs) and the ledger materialization loop
 * (§14). Both arms of the sole reachable ternary that {@code onStart} cannot exercise — the
 * null monthly cap of {@code createCommunity} — are covered by direct reflection.
 */
class DataInitializerTest {

    /**
     * The frozen program instant every clock read resolves to (§24.6); the demo-window rules,
     * visits and the live lease anchor on it, never on the day the campaign runs.
     */
    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 8, 8, 10, 0);

    /**
     * The system under test, freshly built per test.
     */
    private DataInitializer initializer;

    /**
     * Freezes the clock and builds the seeder before each test.
     */
    @BeforeEach
    void setUp() {
        DateTimeProvider.setFixedDateTime(FIXED);
        initializer = new DataInitializer();
    }

    /**
     * Clears the frozen clock so no other campaign inherits it.
     */
    @AfterEach
    void tearDown() {
        DateTimeProvider.clear();
    }

    /**
     * Full {@code onStart} run: wipes, seeds and materializes the whole world. Asserts the exact
     * cardinality of every seeded entity, that the four native wipe queries ran, and that the
     * balance loop (§14) recomputed the single listed account from the movements.
     */
    @Test
    @DisplayName("onStart(): wipes then seeds the exact 2026 world and materializes balances")
    void onStartSeedsTheWholeWorld() {
        EntityManager em = Mockito.mock(EntityManager.class);
        Query query = Mockito.mock(Query.class);
        Mockito.when(em.createNativeQuery(Mockito.anyString())).thenReturn(query);
        FidelityAccount loopAccount = Mockito.mock(FidelityAccount.class);
        BigDecimal recomputed = new BigDecimal("42.00");
        try (MockedStatic<Panache> msPanache = Mockito.mockStatic(Panache.class);
                MockedStatic<PanacheEntityBase> msBase = Mockito.mockStatic(PanacheEntityBase.class);
                MockedStatic<FidelityCommunity> msCommunity = Mockito.mockStatic(FidelityCommunity.class);
                MockedStatic<FidelityMovement> msMovement = Mockito.mockStatic(FidelityMovement.class);
                MockedConstruction<FidelityProgramSetting> mcSetting =
                        Mockito.mockConstruction(FidelityProgramSetting.class);
                MockedConstruction<FidelityCommunity> mcCommunity =
                        Mockito.mockConstruction(FidelityCommunity.class);
                MockedConstruction<ProductFamily> mcFamily =
                        Mockito.mockConstruction(ProductFamily.class,
                                (mock, context) -> mock.products = new HashSet<>());
                MockedConstruction<Product> mcProduct = Mockito.mockConstruction(Product.class);
                MockedConstruction<FidelityRule> mcRule = Mockito.mockConstruction(FidelityRule.class);
                MockedConstruction<FidelityAccount> mcAccount =
                        Mockito.mockConstruction(FidelityAccount.class);
                MockedConstruction<FidelityMovement> mcMovement =
                        Mockito.mockConstruction(FidelityMovement.class);
                MockedConstruction<FidelityMembership> mcMembership =
                        Mockito.mockConstruction(FidelityMembership.class);
                MockedConstruction<FidelityActivation> mcActivation =
                        Mockito.mockConstruction(FidelityActivation.class);
                MockedConstruction<FidelityReservation> mcReservation =
                        Mockito.mockConstruction(FidelityReservation.class);
                MockedConstruction<EarnTrace> mcTrace = Mockito.mockConstruction(EarnTrace.class);
                MockedConstruction<EarnTraceLine> mcTraceLine =
                        Mockito.mockConstruction(EarnTraceLine.class);
                MockedConstruction<PendingReturn> mcPendingReturn =
                        Mockito.mockConstruction(PendingReturn.class);
                MockedConstruction<BatchRunLog> mcBatch = Mockito.mockConstruction(BatchRunLog.class)) {
            msPanache.when(Panache::getEntityManager).thenReturn(em);
            msBase.when(() -> PanacheEntityBase.listAll()).thenReturn(List.of(loopAccount));
            msMovement.when(() -> FidelityMovement.computeBalance(Mockito.any())).thenReturn(recomputed);
            initializer.onStart(null);
            assertEquals(4, mcSetting.constructed().size());
            assertEquals(4, mcCommunity.constructed().size());
            assertEquals(6, mcFamily.constructed().size());
            assertEquals(46, mcProduct.constructed().size());
            assertEquals(11, mcRule.constructed().size());
            assertEquals(9, mcAccount.constructed().size());
            assertEquals(22, mcMovement.constructed().size());
            assertEquals(3, mcMembership.constructed().size());
            assertEquals(2, mcActivation.constructed().size());
            assertEquals(2, mcReservation.constructed().size());
            assertEquals(9, mcTrace.constructed().size());
            assertEquals(2, mcTraceLine.constructed().size());
            assertEquals(1, mcPendingReturn.constructed().size());
            assertEquals(2, mcBatch.constructed().size());
            Mockito.verify(em, Mockito.times(4)).createNativeQuery(Mockito.anyString());
            msMovement.verify(() -> FidelityMovement.computeBalance(loopAccount));
            assertEquals(0, loopAccount.balance.compareTo(recomputed));
        }
    }

    /**
     * The else arm of {@code createCommunity}'s monthly-cap ternary: a null text leaves the cap
     * null. Reflection is required because {@code onStart} only ever seeds capped communities.
     *
     * @throws Exception never, in the nominal path
     */
    @Test
    @DisplayName("createCommunity(): a null monthly cap leaves the cap null")
    void createCommunityNullMonthlyCap() throws Exception {
        Method method = communityFactory();
        try (MockedConstruction<FidelityCommunity> mc = Mockito.mockConstruction(FidelityCommunity.class)) {
            method.invoke(initializer, "X", "Label", null, null, null, null, "criterion");
            FidelityCommunity created = mc.constructed().get(0);
            assertEquals("X", created.code);
            assertNull(created.monthlyCap);
        }
    }

    /**
     * The then arm of {@code createCommunity}'s monthly-cap ternary: a non-null text becomes a
     * scale-2 {@link BigDecimal} compared by {@code compareTo}.
     *
     * @throws Exception never, in the nominal path
     */
    @Test
    @DisplayName("createCommunity(): a non-null monthly cap becomes a BigDecimal")
    void createCommunityNonNullMonthlyCap() throws Exception {
        Method method = communityFactory();
        try (MockedConstruction<FidelityCommunity> mc = Mockito.mockConstruction(FidelityCommunity.class)) {
            method.invoke(initializer, "Y", "Label", "30.00", 100000, 10, 10, "criterion");
            FidelityCommunity created = mc.constructed().get(0);
            assertEquals("Y", created.code);
            assertEquals(0, created.monthlyCap.compareTo(new BigDecimal("30.00")));
        }
    }

    /**
     * Reflects the private {@code createCommunity} factory, made accessible.
     *
     * @return the accessible {@code createCommunity} method
     * @throws NoSuchMethodException if the signature ever changes
     */
    private Method communityFactory() throws NoSuchMethodException {
        Method method = DataInitializer.class.getDeclaredMethod("createCommunity", String.class, String.class,
                String.class, Integer.class, Integer.class, Integer.class, String.class);
        method.setAccessible(true);
        return method;
    }
}
