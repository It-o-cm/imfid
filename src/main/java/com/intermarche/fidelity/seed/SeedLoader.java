package com.intermarche.fidelity.seed;

import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.imports.*;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.function.Function;

/**
 * Loads the 2026 program seed at startup when the database is empty (§24.4, §33.2) — the
 * real complete rule set in CSV (socle 5 brands with its closed 4-brand history, weekend
 * F&L, the four communities, the 40 % hygiene-Labell of the 28th, the CGU program
 * exclusion, an e-coupon and a challenge), the product reference taken from the
 * imvaluation e2e seed (so the §22 reference couple produces earn — fixture and seed are
 * one world), and the demo cards with their ADJUSTMENT balances, activations, memberships
 * and the three FIDELITY_VISITS that arm the 4th-visit demonstration (§24.4).
 * <p>
 * The observer runs after the rule registry has initialized ({@code @Priority} above the
 * default) so the FIDELITY_RULES import validates against the deployed factories (§12).
 * <p>
 * Excluded from the dev and test build profiles, where the programmatic
 * {@link DataInitializer} wipes and reloads its own enriched dataset at every startup —
 * running both would double-seed.
 */
@ApplicationScoped
@UnlessBuildProfile(anyOf = {"dev", "test"})
public class SeedLoader {

    private static final Logger LOGGER = Logger.getLogger(SeedLoader.class);

    /**
     * The product importer.
     */
    @Inject
    ProductCsvResource products;
    /**
     * The product family importer.
     */
    @Inject
    ProductFamilyCsvResource families;
    /**
     * The community importer.
     */
    @Inject
    FidelityCommunityCsvResource communities;
    /**
     * The rule importer.
     */
    @Inject
    FidelityRuleCsvResource rules;
    /**
     * The account importer.
     */
    @Inject
    FidelityAccountCsvResource accounts;
    /**
     * The adjustment importer.
     */
    @Inject
    FidelityAdjustmentCsvResource adjustments;
    /**
     * The visit importer.
     */
    @Inject
    FidelityVisitCsvResource visits;
    /**
     * The activation importer.
     */
    @Inject
    FidelityActivationCsvResource activations;
    /**
     * The membership importer.
     */
    @Inject
    FidelityMembershipCsvResource memberships;

    /**
     * Loads the seed at startup when the product reference is empty (§24.4).
     *
     * @param event The Quarkus startup event.
     */
    void onStart(@Observes @Priority(2600) StartupEvent event) {
        if (Product.count() > 0) {
            return;
        }
        LOGGER.info("Empty database detected — loading the 2026 program seed (§24.4)");
        load("/seed/01-products.csv", products::importProducts);
        load("/seed/02-product-families.csv", families::importProductFamilies);
        load("/seed/03-communities.csv", communities::importCommunities);
        load("/seed/04-rules.csv", rules::importRules);
        load("/seed/05-accounts.csv", accounts::importAccounts);
        load("/seed/06-adjustments.csv", adjustments::importAdjustments);
        load("/seed/07-visits.csv", visits::importVisits);
        load("/seed/08-activations.csv", activations::importActivations);
        load("/seed/09-memberships.csv", memberships::importMemberships);
        LOGGER.info("2026 program seed loaded");
    }

    /**
     * Loads one seed CSV resource through its importer, logging the report.
     *
     * @param resource The classpath resource path.
     * @param importer The importer function.
     */
    private void load(String resource, Function<InputStream, Response> importer) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.errorf("Seed resource missing: %s", resource);
                return;
            }
            Response report = importer.apply(in);
            LOGGER.infof("Seed %s -> %s", resource, report.getEntity());
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to load seed resource %s", resource);
        }
    }
}
