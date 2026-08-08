package com.intermarche.fidelity.seed;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.imports.FidelityAccountCsvResource;
import com.intermarche.fidelity.imports.FidelityActivationCsvResource;
import com.intermarche.fidelity.imports.FidelityAdjustmentCsvResource;
import com.intermarche.fidelity.imports.FidelityCommunityCsvResource;
import com.intermarche.fidelity.imports.FidelityMembershipCsvResource;
import com.intermarche.fidelity.imports.FidelityRuleCsvResource;
import com.intermarche.fidelity.imports.FidelityVisitCsvResource;
import com.intermarche.fidelity.imports.ProductCsvResource;
import com.intermarche.fidelity.imports.ProductFamilyCsvResource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link SeedLoader}: the production-profile startup observer that loads the
 * 2026 program seed only when the database is empty (§24.4, §33.2). No Quarkus, no H2, no database boot.
 * <p>
 * The observer has nine injected CSV importers and drives the single static {@link Product#count()},
 * intercepted on {@link PanacheEntityBase}. The private {@code load} helper resolves each CSV through
 * {@code getResourceAsStream} and swallows every failure; its three branches (missing resource, applied
 * importer, importer failure) are exercised by direct reflection so the seed classpath resources are not
 * relied upon beyond the one that provably exists. No clock is read by this class, so no
 * {@code DateTimeProvider} is injected (§24.6): there is no temporal logic to freeze.
 */
class SeedLoaderTest {

    /**
     * The system under test, freshly built with mocked importers before each test.
     */
    private SeedLoader loader;

    /**
     * Builds the observer and wires every importer field to a fresh mock before each test.
     */
    @BeforeEach
    void setUp() {
        loader = new SeedLoader();
        loader.products = Mockito.mock(ProductCsvResource.class);
        loader.families = Mockito.mock(ProductFamilyCsvResource.class);
        loader.communities = Mockito.mock(FidelityCommunityCsvResource.class);
        loader.rules = Mockito.mock(FidelityRuleCsvResource.class);
        loader.accounts = Mockito.mock(FidelityAccountCsvResource.class);
        loader.adjustments = Mockito.mock(FidelityAdjustmentCsvResource.class);
        loader.visits = Mockito.mock(FidelityVisitCsvResource.class);
        loader.activations = Mockito.mock(FidelityActivationCsvResource.class);
        loader.memberships = Mockito.mock(FidelityMembershipCsvResource.class);
    }

    /**
     * The {@code count() > 0} true arm: a non-empty product reference short-circuits the observer, which
     * returns before touching a single importer (§24.4).
     */
    @Test
    @DisplayName("onStart(): a non-empty database loads nothing")
    void onStartReturnsEarlyWhenProductsExist() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::count).thenReturn(1L);
            loader.onStart(null);
            Mockito.verifyNoInteractions(loader.products, loader.families, loader.communities, loader.rules,
                    loader.accounts, loader.adjustments, loader.visits, loader.activations, loader.memberships);
        }
    }

    /**
     * The {@code count() > 0} false arm: an empty product reference drives the whole ordered load — every
     * one of the nine importers is applied exactly once with a non-null resolved stream (§24.4). The seed
     * CSV resources exist on the classpath, so each {@code load} takes its {@code in != null} branch.
     */
    @Test
    @DisplayName("onStart(): an empty database loads every seed resource once")
    void onStartLoadsEveryResourceWhenEmpty() {
        Response report = Mockito.mock(Response.class);
        Mockito.when(loader.products.importProducts(ArgumentMatchers.any())).thenReturn(report);
        Mockito.when(loader.families.importProductFamilies(ArgumentMatchers.any())).thenReturn(report);
        Mockito.when(loader.communities.importCommunities(ArgumentMatchers.any())).thenReturn(report);
        Mockito.when(loader.rules.importRules(ArgumentMatchers.any())).thenReturn(report);
        Mockito.when(loader.accounts.importAccounts(ArgumentMatchers.any())).thenReturn(report);
        Mockito.when(loader.adjustments.importAdjustments(ArgumentMatchers.any())).thenReturn(report);
        Mockito.when(loader.visits.importVisits(ArgumentMatchers.any())).thenReturn(report);
        Mockito.when(loader.activations.importActivations(ArgumentMatchers.any())).thenReturn(report);
        Mockito.when(loader.memberships.importMemberships(ArgumentMatchers.any())).thenReturn(report);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::count).thenReturn(0L);
            loader.onStart(null);
            Mockito.verify(loader.products).importProducts(ArgumentMatchers.notNull());
            Mockito.verify(loader.families).importProductFamilies(ArgumentMatchers.notNull());
            Mockito.verify(loader.communities).importCommunities(ArgumentMatchers.notNull());
            Mockito.verify(loader.rules).importRules(ArgumentMatchers.notNull());
            Mockito.verify(loader.accounts).importAccounts(ArgumentMatchers.notNull());
            Mockito.verify(loader.adjustments).importAdjustments(ArgumentMatchers.notNull());
            Mockito.verify(loader.visits).importVisits(ArgumentMatchers.notNull());
            Mockito.verify(loader.activations).importActivations(ArgumentMatchers.notNull());
            Mockito.verify(loader.memberships).importMemberships(ArgumentMatchers.notNull());
        }
    }

    /**
     * The {@code in == null} true arm of {@code load}: a resource that resolves to no stream logs the miss
     * and returns without ever applying the importer.
     *
     * @throws Exception never, in the nominal path
     */
    @Test
    @DisplayName("load(): a missing resource never applies its importer")
    @SuppressWarnings("unchecked")
    void loadSkipsMissingResource() throws Exception {
        Function<InputStream, Response> importer = Mockito.mock(Function.class);
        invokeLoad("/seed/does-not-exist.csv", importer);
        Mockito.verifyNoInteractions(importer);
    }

    /**
     * The {@code in == null} false arm of {@code load}: an existing resource is applied exactly once with a
     * non-null stream and its report is read.
     *
     * @throws Exception never, in the nominal path
     */
    @Test
    @DisplayName("load(): an existing resource is applied once with a live stream")
    @SuppressWarnings("unchecked")
    void loadAppliesImporterForExistingResource() throws Exception {
        Function<InputStream, Response> importer = Mockito.mock(Function.class);
        Mockito.when(importer.apply(ArgumentMatchers.any())).thenReturn(Mockito.mock(Response.class));
        invokeLoad("/seed/01-products.csv", importer);
        Mockito.verify(importer).apply(ArgumentMatchers.notNull());
    }

    /**
     * The {@code catch} branch of {@code load}: an importer that throws on an existing resource has its
     * failure swallowed — {@code load} never propagates the exception.
     *
     * @throws Exception never, in the nominal path
     */
    @Test
    @DisplayName("load(): an importer failure is swallowed")
    @SuppressWarnings("unchecked")
    void loadSwallowsImporterException() throws Exception {
        Function<InputStream, Response> importer = Mockito.mock(Function.class);
        Mockito.when(importer.apply(ArgumentMatchers.any())).thenThrow(new IllegalStateException("boom"));
        assertDoesNotThrow(() -> invokeLoad("/seed/01-products.csv", importer));
        Mockito.verify(importer).apply(ArgumentMatchers.notNull());
    }

    /**
     * Reflects and invokes the private {@code load} helper against the system under test.
     *
     * @param resource the classpath resource path
     * @param importer the importer function to drive
     * @throws Exception if the reflective invocation fails
     */
    private void invokeLoad(String resource, Function<InputStream, Response> importer) throws Exception {
        Method method = SeedLoader.class.getDeclaredMethod("load", String.class, Function.class);
        method.setAccessible(true);
        assertNotNull(method);
        method.invoke(loader, resource, importer);
    }
}
