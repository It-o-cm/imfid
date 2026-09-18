package com.intermarche.fidelity.seed;

import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.AdvantageCategory;
import com.intermarche.fidelity.domain.AdvantageType;
import com.intermarche.fidelity.domain.BatchRunLog;
import com.intermarche.fidelity.domain.CardHolder;
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
import com.intermarche.fidelity.domain.FidelityRuleTier;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.PendingReturn;
import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.domain.ProductFamily;
import com.intermarche.fidelity.domain.ProductType;
import com.intermarche.fidelity.domain.ReservationState;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Development/test data seeder, in the impos {@code DataInitializer} style.
 * <p>
 * Restricted to the dev and test build profiles: this bean wipes and reloads the
 * loyalty tables at startup, which must never run in production (each reboot would
 * purge the program's database). Production referentials are fed through the CSV
 * imports and the CSV {@code SeedLoader}, which stays prod-only.
 * <p>
 * The dataset is the 2026 program world of the CSV seed (§24.4) — the socle 5-brands
 * rule with its closed 4-brand history, the weekend F&amp;L rule, the four
 * communities, the 40 % hygiene-Labell of the 28th, the CGU program exclusion, an
 * e-coupon and a challenge — enriched so every rule has a live base (products carry
 * the socle, community and challenge brands, and the families the rules reference)
 * and so every account state and movement type of the nomenclature is represented.
 * <p>
 * Demo cards (13 digits, reserved prefix {@code 299}, EAN-13 check digit, §33.1):
 * <ul>
 *   <li>{@code 2990000000019} — rich history: adjustment, 2025 earn expired on
 *       1st March, weekend F&amp;L earn, refund credit, socle earn, burn (with its
 *       CONFIRMED reservation), partial return debit; three recent visits arming the
 *       4th-visit 5 % &rarr; 10 % boost; e-coupon and challenge activations, a
 *       4.50 € challenge running base. Balance 56.70 €.</li>
 *   <li>{@code 2990000000026} — Babies member, community earn. Balance 18.50 €.</li>
 *   <li>{@code 2990000000033} — Students member (Small-Budgets membership expired),
 *       community earn this month. Balance 24.00 €.</li>
 *   <li>{@code 2990000000040} — PENDING_ACTIVATION: earns but cannot burn (§25.3).
 *       Balance 5.00 €.</li>
 *   <li>{@code 2990000000057} — negative balance after a return debit (I7).
 *       Balance -1.70 €.</li>
 *   <li>{@code 2990000000064} — lost card, RESILIATED, balance transferred to
 *       {@code 2990000000071} (§32.2). Balance 0.00 €.</li>
 *   <li>{@code 2990000000071} — successor card, incoming TRANSFER preserving the
 *       earnYear (§34.3). Balance 15.00 €.</li>
 *   <li>{@code 2990000000088} — carries an ACTIVE reservation lease of 8.00 € (I11):
 *       available balance 22.00 € out of 30.00 €.</li>
 *   <li>{@code 2990000000095} — PENDING_ACTIVATION card never activated, voided by
 *       the two-month CGU batch (ACTIVATION_VOID), RESILIATED. Balance 0.00 €.</li>
 * </ul>
 * Month-sensitive data (visits, demo e-coupon/challenge windows and activations, the
 * active lease expiry) is anchored on the boot date so the dataset never goes stale;
 * ledger history keeps fixed 2024-2026 dates. Operator accounts are not seeded here:
 * {@code SecurityBootstrap} creates {@code admin}/{@code admin-password} and
 * {@code pos}/{@code pos-password} in dev (§24.1).
 */
@ApplicationScoped
@IfBuildProfile(anyOf = {"dev", "test"})
public class DataInitializer {

    private static final Logger LOGGER = Logger.getLogger(DataInitializer.class);

    /** The store every seeded ticket and visit points to. */
    private static final String STORE_CODE = "0101";

    /** Rich-history demo card: every ticket-borne movement type, boosted visits. */
    private static final String CARD_RICH = "2990000000019";

    /** Babies-community member card. */
    private static final String CARD_BABIES = "2990000000026";

    /** Students-community member card (expired Small-Budgets membership). */
    private static final String CARD_STUDENT = "2990000000033";

    /** PENDING_ACTIVATION card: earns but cannot burn (§25.3). */
    private static final String CARD_PENDING = "2990000000040";

    /** Card left with a negative balance by a return debit (I7). */
    private static final String CARD_NEGATIVE = "2990000000057";

    /** Lost card whose balance was transferred away (§32.2). */
    private static final String CARD_LOST = "2990000000064";

    /** Successor card of the lost one, holding the transferred balance. */
    private static final String CARD_SUCCESSOR = "2990000000071";

    /** Card holding an ACTIVE reservation lease (I11). */
    private static final String CARD_RESERVED = "2990000000088";

    /** PENDING_ACTIVATION card voided by the two-month CGU batch (§16). */
    private static final String CARD_VOIDED = "2990000000095";

    /**
     * Seeds the advantage referentials the ticket grouping relies on
     * (RFP BO-03-03-25/-33): the closed PRODUCT/TICKET type nomenclature with its
     * display order, and two demo categories.
     */
    private void loadAdvantageReferentials() {
        createAdvantageType("PRODUCT", "Vos avantages produits", 10);
        createAdvantageType("TICKET", "Vos avantages ticket", 20);
        createAdvantageCategory("FRAIS", "Produits frais");
        createAdvantageCategory("MDD", "Marques du magasin");
    }

    /**
     * Creates and persists an advantage type of the closed nomenclature.
     *
     * @param code         the nomenclature code
     * @param label        the printable group label
     * @param displayOrder the order of the group on the ticket
     */
    private void createAdvantageType(String code, String label, int displayOrder) {
        AdvantageType type = new AdvantageType();
        type.code = code;
        type.label = label;
        type.displayOrder = displayOrder;
        type.persist();
    }

    /**
     * Creates and persists an advantage category.
     *
     * @param code  the category code
     * @param label the printable category label
     */
    private void createAdvantageCategory(String code, String label) {
        AdvantageCategory category = new AdvantageCategory();
        category.code = code;
        category.label = label;
        category.persist();
    }

    /**
     * Wipes and reloads the loyalty tables at startup (dev/test only).
     * <p>
     * Runs at priority 2700, after the rule registry (default 2500) has deployed the
     * factories, mirroring the CSV seed ordering (§12). The whole load is one
     * transaction: a failure leaves the database empty rather than half-seeded.
     *
     * @param ev the startup event
     */
    @Transactional
    void onStart(@Observes @Priority(2700) StartupEvent ev) {
        wipe();
        loadProgramSettings();
        loadCommunities();
        loadProductsAndFamilies();
        loadRules();
        loadAccounts();
        loadBatchHistory();
        LOGGER.infof("Dev/test dataset loaded: %d products, %d rules, %d communities, %d accounts, %d movements",
                Product.count(), FidelityRule.count(), FidelityCommunity.count(),
                FidelityAccount.count(), FidelityMovement.count());
    }

    /**
     * Deletes every seeded table, children before parents.
     * <p>
     * Panache {@code deleteAll()} issues bulk JPQL deletes that ignore cascades, so
     * the element-collection and join tables (trace warnings, movement line refs,
     * family-product links, family self-references) are cleared natively first.
     * The {@code app_users} table is left untouched: {@code SecurityBootstrap} owns it.
     */
    private void wipe() {
        Panache.getEntityManager().createNativeQuery("delete from earn_trace_warnings").executeUpdate();
        Panache.getEntityManager().createNativeQuery("delete from fidelity_movement_line_refs").executeUpdate();
        Panache.getEntityManager().createNativeQuery("delete from product_families_products").executeUpdate();
        Panache.getEntityManager().createNativeQuery("update product_families set parent_product_family_id = null").executeUpdate();
        EarnTraceLine.deleteAll();
        EarnTrace.deleteAll();
        FidelityMovement.deleteAll();
        FidelityReservation.deleteAll();
        FidelityActivation.deleteAll();
        FidelityMembership.deleteAll();
        PendingReturn.deleteAll();
        CardHolder.deleteAll();
        FidelityAccount.deleteAll();
        FidelityRuleTier.deleteAll();
        FidelityRule.deleteAll();
        AdvantageType.deleteAll();
        AdvantageCategory.deleteAll();
        FidelityCommunity.deleteAll();
        ProductFamily.deleteAll();
        Product.deleteAll();
        BatchRunLog.deleteAll();
        FidelityProgramSetting.deleteAll();
    }

    /**
     * Seeds the administrable program parameters (§25.1) with their 2026 values, so
     * the "Programme" screen shows explicit rows instead of code fallbacks.
     */
    private void loadProgramSettings() {
        createSetting(FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, "400.00");
        createSetting(FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, "900");
        createSetting(FidelityProgramSetting.KEY_PROGRAM_ZONE, "Europe/Paris");
        createSetting(FidelityProgramSetting.KEY_CARD_PREFIX, "299");
    }

    /**
     * Seeds the four 2026 communities with their caps and renewal windows (§13).
     */
    private void loadCommunities() {
        createCommunity("BABIES", "Intermarché Bébés", "30.00", null, null, null,
                "Personne s'occupant d'un enfant de moins de 2 ans ou à naître");
        createCommunity("LARGE_FAMILIES", "Intermarché Familles Nombreuses", "20.00", null, null, null,
                "3 enfants nés et plus dont un de moins de 18 ans");
        createCommunity("STUDENTS", "Intermarché Étudiants", "20.00", 100000, 10, 10,
                "Étudiants de 18 à 28 ans révolus");
        createCommunity("SMALL_BUDGETS", "Intermarché Petits Budgets", "20.00", 130000, 2, 2,
                "Quotient familial inférieur ou égal à 900 €");
    }

    /**
     * Seeds the product reference and its families.
     * <p>
     * The {@code 33000000000xx} EANs mirror the imvaluation e2e seed (§22) so the
     * reference couple produces earn — fixture and seed are one world. The
     * {@code 34000000000xx} EANs are enrichment: they carry the socle brands
     * (Fiorini, Labell, Mäy), the Large-Families brands (Monique Ranou, Chabrior,
     * Saint Eloi), the challenge brand (Lay's) and populate every family the 2026
     * rules reference (RAYON_BEBE, HYGIENE_FEM, RAYON_ECOUPON, ALCOOL,
     * CARTES_CADEAUX), so each rule has a live base to demonstrate.
     */
    private void loadProductsAndFamilies() {
        ProductFamily fruitsLegumes = createFamily("F_L", "Fruits & Légumes frais");
        ProductFamily rayonBebe = createFamily("RAYON_BEBE", "Rayon Bébé");
        ProductFamily hygieneFem = createFamily("HYGIENE_FEM", "Hygiène féminine");
        ProductFamily rayonEcoupon = createFamily("RAYON_ECOUPON", "Rayon e-coupon (animalerie)");
        ProductFamily alcool = createFamily("ALCOOL", "Alcools (exclus Petits Budgets)");
        ProductFamily cartesCadeaux = createFamily("CARTES_CADEAUX", "Cartes cadeaux (exclusion CGU)");

        // --- imvaluation e2e mirror (§22): the COMPLETE 33-EAN catalog, so no line of
        //     a real register basket ever falls UNKNOWN_EAN. Reference data (name,
        //     weight/volume, type, unit) is verbatim from imvaluation's products.csv;
        //     the brands of the mirror are aligned on the earn world: the socle five
        //     (Pâturages, Paquito, Fiorini, Labell, Mäy) are carried by plausible
        //     mirror products, the other products keep imvaluation's placeholder
        //     brands verbatim. F_L holds the real produce (apples, cucumber, cherry
        //     tomatoes) plus the crisps demo line. ---
        createProduct(fruitsLegumes, "3300000000001", "Pommes Golden", "Pommes fraîches bio", "Brand A",
                "1.000", "2.500", ProductType.WEIGHT, "kg");
        createProduct(null, "3300000000002", "Lait UHT 1L", "Lait demi-écrémé (marque socle)", "Pâturages",
                "1.000", "1.000", ProductType.UNIT, "L");
        createProduct(null, "3300000000003", "Baguette Tradition", "Pain de tradition", "Brand C",
                "0.250", "0.600", ProductType.UNIT, "kg");
        createProduct(null, "3300000000004", "Café Grains 500g", "Café moulu arabica (marque socle)", "Mäy",
                "0.500", "1.250", ProductType.UNIT, "kg");
        createProduct(null, "3300000000005", "Pâtes Penne 500g", "Pâtes alimentaires", "Brand E",
                "0.500", "1.250", ProductType.WEIGHT, "kg");
        createProduct(null, "3300000000006", "Huile d'Olive 1L", "Huile vierge extra", "Brand F",
                "1.000", "1.000", ProductType.UNIT, "L");
        createProduct(null, "3300000000007", "Eau Minérale 1.5L", "Eau de source (marque socle)", "Paquito",
                "1.500", "1.500", ProductType.UNIT, "L");
        createProduct(null, "3300000000008", "Jambon Blanc 100g", "Tranches de jambon", "Brand H",
                "0.100", "0.250", ProductType.WEIGHT, "kg");
        createProduct(null, "3300000000009", "Beurre Doux 250g", "Motte de beurre", "Brand I",
                "0.250", "0.600", ProductType.WEIGHT, "kg");
        createProduct(null, "3300000000010", "Yaourt Nature 4x125g", "Pots de yaourt (marque socle)", "Pâturages",
                "0.500", "1.250", ProductType.UNIT, "kg");
        createProduct(null, "3300000000011", "Coca-Cola 1.5L", "Boisson gazeuse", "Brand K",
                "1.500", "1.500", ProductType.UNIT, "L");
        createProduct(null, "3300000000012", "Orangina 1.25L", "Boisson aux agrumes", "Brand L",
                "1.250", "1.250", ProductType.UNIT, "L");
        createProduct(null, "3300000000013", "Biscuits Chocolat 200g", "Paquet de biscuits (marque socle)", "Fiorini",
                "0.200", "0.500", ProductType.UNIT, "kg");
        createProduct(fruitsLegumes, "3300000000014", "Chips Classiques 150g", "Chips (rayon F&L pour la démo)", "Brand N",
                "0.150", "0.400", ProductType.UNIT, "kg");
        createProduct(null, "3300000000015", "Sauce Tomate 500g", "Sauce bolognaise", "Brand O",
                "0.500", "1.250", ProductType.UNIT, "kg");
        createProduct(null, "3300000000016", "Purée de Pomme de Terre 500g", "Purée instantanée", "Brand P",
                "0.500", "1.250", ProductType.UNIT, "kg");
        createProduct(fruitsLegumes, "3300000000017", "Concombre", "Légume frais", "Brand Q",
                "0.300", "0.750", ProductType.WEIGHT, "kg");
        createProduct(fruitsLegumes, "3300000000018", "Tomates Cerises 500g", "Tomates rondes", "Brand R",
                "0.500", "1.250", ProductType.WEIGHT, "kg");
        createProduct(null, "3300000000019", "Oeufs Bio 6 unités", "Oeufs frais gros", "Brand S",
                "0.360", "0.900", ProductType.UNIT, "kg");
        createProduct(null, "3300000000020", "Poulet Rôti 1.2kg", "Poulet fermier", "Brand T",
                "1.200", "3.000", ProductType.WEIGHT, "kg");
        createProduct(null, "3300000000021", "Saumon Fume 200g", "Tranches de saumon", "Brand U",
                "0.200", "0.500", ProductType.WEIGHT, "kg");
        createProduct(null, "3300000000022", "Riz Basmati 1kg", "Riz long grain", "Brand V",
                "1.000", "2.500", ProductType.UNIT, "kg");
        createProduct(null, "3300000000023", "Lentilles Vertes 500g", "Légumes secs", "Brand W",
                "0.500", "1.250", ProductType.UNIT, "kg");
        createProduct(null, "3300000000024", "Miel d'Acacia 500g", "Pot de miel", "Brand X",
                "0.500", "1.250", ProductType.UNIT, "kg");
        createProduct(null, "3300000000025", "Lessive Liquide 1.5L", "Lessive linge", "Brand Y",
                "1.500", "1.500", ProductType.UNIT, "L");
        createProduct(null, "3300000000026", "Eponge Vaisselle 3 unités", "Eponges abrasives", "Brand Z",
                "0.100", "0.250", ProductType.UNIT, "kg");
        createProduct(hygieneFem, "3300000000027", "Coton Bio 500g", "Disques de coton (marque socle)", "Labell",
                "0.500", "1.250", ProductType.UNIT, "kg");
        createProduct(null, "3300000000028", "Piles AA 4 unités", "Piles alcalines", "Brand B1",
                "0.080", "0.200", ProductType.UNIT, "kg");
        createProduct(null, "3300000000029", "Chewing-Gum Menthe", "Pommes de menthe", "Brand C1",
                "0.050", "0.125", ProductType.UNIT, "kg");
        createProduct(null, "3300000000030", "Dentifrice Menthe 100ml", "Tube dentifrice (marque socle)", "Labell",
                "0.100", "0.100", ProductType.UNIT, "L");
        createProduct(null, "3300000000031", "Poêle Antiadhésive 28cm", "Poêle fonte alum", "Tefal",
                "0.800", "0.000", ProductType.UNIT, "pcs");
        createProduct(null, "3300000000032", "Casserole Inox 20cm", "Casserole acier inox", "Staub",
                "1.200", "0.000", ProductType.UNIT, "pcs");
        createProduct(null, "3300000000033", "Set de Couteaux Chef", "Couteaux acier inox", "Sabatier",
                "0.500", "0.000", ProductType.UNIT, "pcs");

        // --- Enrichment: socle brands (5-brands rule needs 3 eligible items) ---
        createProduct(null, "3400000000001", "Yaourt Nature 4x125g", "Yaourts nature", "Fiorini",
                "0.500", "1.250", ProductType.UNIT, "kg");
        createProduct(null, "3400000000002", "Gel Douche Amande 250ml", "Gel douche surgras", "Labell",
                "0.250", "0.250", ProductType.UNIT, "L");
        createProduct(null, "3400000000003", "Thé Vert Bio 20 sachets", "Thé vert de Chine", "Mäy",
                "0.040", "0.100", ProductType.UNIT, "kg");

        // --- Enrichment: community bases ---
        createProduct(rayonBebe, "3400000000010", "Couches Taille 3 x54", "Couches 4-9 kg", null,
                "1.800", "0.000", ProductType.UNIT, "pcs");
        createProduct(rayonBebe, "3400000000011", "Petit Pot Carotte 2x130g", "Purée de carottes dès 4 mois", null,
                "0.260", "0.650", ProductType.UNIT, "kg");
        createProduct(null, "3400000000020", "Jambon Supérieur x4", "Jambon cuit supérieur", "Monique Ranou",
                "0.160", "0.400", ProductType.UNIT, "kg");
        createProduct(null, "3400000000021", "Brioche Tressée 500g", "Brioche au beurre", "Chabrior",
                "0.500", "1.250", ProductType.UNIT, "kg");
        createProduct(null, "3400000000022", "Torsades 500g", "Pâtes alimentaires", "Saint Eloi",
                "0.500", "1.250", ProductType.UNIT, "kg");
        createProduct(hygieneFem, "3400000000030", "Serviettes Hygiéniques x16", "Serviettes nuit (40 % le 28)", "Labell",
                "0.100", "0.250", ProductType.UNIT, "pcs");

        // --- Enrichment: e-coupon, challenge, exclusions ---
        createProduct(rayonEcoupon, "3400000000040", "Croquettes Chat 1.5kg", "Croquettes au poulet", null,
                "1.500", "3.750", ProductType.UNIT, "kg");
        createProduct(alcool, "3400000000050", "Vin Rouge Bordeaux 75cl", "AOC Bordeaux (exclu Petits Budgets)", null,
                "0.750", "1.200", ProductType.UNIT, "L");
        createProduct(cartesCadeaux, "3400000000060", "Carte Cadeau 50 €", "Carte cadeau (exclusion CGU)", null,
                "0.010", "0.010", ProductType.UNIT, "pcs");
        createProduct(null, "3400000000070", "Chips Lay's Nature 145g", "Chips nature (défi)", "Lay's",
                "0.145", "0.400", ProductType.UNIT, "kg");
    }

    /**
     * Seeds the real, complete 2026 rule set (§24.4): specifications are the exact
     * JSON of the CSV seed, validated by the same JSON Schemas at import time.
     * <p>
     * The two demo-window rules (e-coupon, challenge) are anchored on the current
     * month so they are always in force at boot; the structural rules keep their real
     * fixed windows (the closed 4-brand socle documents rule versioning, §13).
     */
    private void loadRules() {
        LocalDate today = DateTimeProvider.now().toLocalDate();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime nextMonthStart = monthStart.plusMonths(1);

        createRule("SOCLE_5_MARQUES", FidelityRule.TYPE_BRAND_TIERED_EARN,
                "Marques du quotidien (5 marques)",
                LocalDateTime.of(2026, 5, 18, 0, 0), null, 100, true, null,
                "{\"scope\":{\"include\":{\"brands\":[\"Pâturages\",\"Paquito\",\"Fiorini\",\"Labell\",\"Mäy\"]}},"
                        + "\"minEligibleItems\":3,\"baseRate\":0.05,\"boostedRate\":0.10,\"visitThreshold\":4}");
        createRule("SOCLE_4_MARQUES", FidelityRule.TYPE_BRAND_TIERED_EARN,
                "Marques du quotidien (4 marques, historique clos)",
                LocalDateTime.of(2022, 7, 1, 0, 0), LocalDateTime.of(2026, 5, 18, 0, 0), 100, true, null,
                "{\"scope\":{\"include\":{\"brands\":[\"Pâturages\",\"Paquito\",\"Fiorini\",\"Labell\"]}},"
                        + "\"minEligibleItems\":3,\"baseRate\":0.05,\"boostedRate\":0.10,\"visitThreshold\":4}");
        createRule("FL_WEEKEND", FidelityRule.TYPE_CALENDAR_FAMILY_EARN,
                "Fruits & Légumes du week-end",
                LocalDateTime.of(2026, 1, 1, 0, 0), null, 90, true, null,
                "{\"scope\":{\"include\":{\"families\":[\"F_L\"]}},\"rate\":0.10,"
                        + "\"activeDays\":[\"SATURDAY\",\"SUNDAY\"]}");
        createRule("COMMUNITY_BABIES", FidelityRule.TYPE_COMMUNITY_EARN,
                "Avantage Bébés",
                LocalDateTime.of(2026, 1, 1, 0, 0), null, 80, true, "30.00",
                "{\"communityCode\":\"BABIES\",\"scope\":{\"include\":{\"families\":[\"RAYON_BEBE\"]}},\"rate\":0.10}");
        createRule("COMMUNITY_LARGE_FAMILIES", FidelityRule.TYPE_COMMUNITY_EARN,
                "Avantage Familles Nombreuses",
                LocalDateTime.of(2026, 5, 18, 0, 0), null, 80, true, "20.00",
                "{\"communityCode\":\"LARGE_FAMILIES\",\"scope\":{\"include\":{\"brands\":"
                        + "[\"Monique Ranou\",\"Chabrior\",\"Saint Eloi\"]}},\"rate\":0.10}");
        createRule("COMMUNITY_STUDENTS", FidelityRule.TYPE_COMMUNITY_EARN,
                "Avantage Étudiants",
                LocalDateTime.of(2026, 1, 1, 0, 0), null, 80, true, "20.00",
                "{\"communityCode\":\"STUDENTS\",\"scope\":{\"include\":{\"brands\":"
                        + "[\"Labell\",\"Paquito\",\"Pâturages\",\"Fiorini\",\"Mäy\"]},"
                        + "\"exclude\":{\"brands\":[\"Top Budget\",\"Merci\"]}},\"rate\":0.10}");
        createRule("COMMUNITY_SMALL_BUDGETS", FidelityRule.TYPE_COMMUNITY_EARN,
                "Avantage Petits Budgets",
                LocalDateTime.of(2026, 1, 1, 0, 0), null, 70, true, "20.00",
                "{\"communityCode\":\"SMALL_BUDGETS\",\"scope\":{\"wholeStore\":true,"
                        + "\"exclude\":{\"families\":[\"ALCOOL\"]}},\"rate\":0.05}");
        createRule("STUDENTS_HYGIENE_28", FidelityRule.TYPE_MONTHLY_DATE_EARN,
                "40% hygiène féminine Labell le 28 (Étudiants)",
                LocalDateTime.of(2026, 1, 1, 0, 0), null, 110, true, "10.00",
                "{\"dayOfMonth\":28,\"communityCode\":\"STUDENTS\","
                        + "\"scope\":{\"include\":{\"families\":[\"HYGIENE_FEM\"]}},\"rate\":0.40}");
        loadAdvantageReferentials();
        createRule("CGU_EXCLUSION", FidelityRule.TYPE_PROGRAM_EXCLUSION,
                "Exclusions du programme (CGU)",
                LocalDateTime.of(2026, 1, 1, 0, 0), null, 1000, false, null,
                "{\"scope\":{\"include\":{\"families\":"
                        + "[\"CARTES_CADEAUX\",\"LIVRES\",\"PRESSE\",\"GAZ\",\"CARBURANTS\"]}}}");
        createRule("ECOUPON_DEMO", FidelityRule.TYPE_ECOUPON_EARN,
                "E-coupon du mois (animalerie)",
                monthStart, nextMonthStart, 85, true, null,
                "{\"scope\":{\"include\":{\"families\":[\"RAYON_ECOUPON\"]}},\"rate\":0.20}");
        createRule("CHALLENGE_DEMO", FidelityRule.TYPE_CHALLENGE_EARN,
                "Mes défis gagnants (Lay's)",
                monthStart, nextMonthStart, 60, true, null,
                "{\"scope\":{\"include\":{\"brands\":[\"Lay's\"]}},\"tiers\":"
                        + "[{\"threshold\":5.00,\"reward\":1.00},{\"threshold\":6.00,\"reward\":1.50},"
                        + "{\"threshold\":8.00,\"reward\":2.00,\"missionRequired\":true}]}");
    }

    /**
     * Seeds the demo accounts, their ledgers, memberships, activations, visits,
     * reservations, earn traces and a held return — one card per notable state (see
     * the class Javadoc for the roster). Balances are recomputed from the movements
     * at the end, so the "balance = signed sum of movements" invariant (§14) holds by
     * construction.
     */
    private void loadAccounts() {
        LocalDate today = DateTimeProvider.now().toLocalDate();
        LocalDateTime now = DateTimeProvider.now();
        // Three visit days strictly before today: at the 4th pass (today) the socle
        // rate is boosted to 10 % (§24.4). Early in a month some may fall in the
        // previous month; the demo then fully re-arms as days accrue.
        LocalDate visit1 = today.minusDays(6);
        LocalDate visit2 = today.minusDays(4);
        LocalDate visit3 = today.minusDays(2);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        // --- CARD_RICH: every ticket-borne movement type + boosted visits ---
        FidelityAccount rich = createAccount(CARD_RICH, AccountStatus.ACTIVE,
                LocalDateTime.of(2026, 1, 5, 9, 0), now.minusDays(2));
        createMovement(rich, MovementType.EARN, "30.00", LocalDate.of(2025, 11, 15), 2025,
                "SOCLE_4_MARQUES", "0101-2025-000123", null);
        createMovement(rich, MovementType.EXPIRY, "-30.00", LocalDate.of(2026, 3, 1), 2025,
                null, null, "1st-March expiry of the 2025 residual");
        createMovement(rich, MovementType.ADJUSTMENT, "50.00", LocalDate.of(2026, 6, 1), 2026,
                null, null, "Initial demo balance (seed)");
        createMovement(rich, MovementType.EARN, "12.00", LocalDate.of(2026, 7, 19), 2026,
                "FL_WEEKEND", "0101-2026-001234", null);
        createMovement(rich, MovementType.REFUND_CREDIT, "2.50", LocalDate.of(2026, 7, 25), 2026,
                null, "0101-2026-001500R", null);
        createMovement(rich, MovementType.EARN, "3.40", visit1, visit1.getYear(),
                "SOCLE_5_MARQUES", "0101-2026-002001", null);
        createMovement(rich, MovementType.BURN, "-10.00", visit2, visit2.getYear(),
                null, "0101-2026-002088", null);
        createMovement(rich, MovementType.RETURN_DEBIT, "-1.20", visit3, visit3.getYear(),
                "FL_WEEKEND", "0101-2026-002150R", null);
        createVisit(CARD_RICH, LocalDate.of(2026, 7, 19), "0101-2026-001234");
        createEarnTrace(CARD_RICH, visit1, "0101-2026-002001", "3.40");
        createVisit(CARD_RICH, visit2, "0101-2026-002088");
        createVisit(CARD_RICH, visit3, "0101-2026-002150");
        createActivation(rich, "ECOUPON_DEMO", monthStart, monthEnd, false);
        createActivation(rich, "CHALLENGE_DEMO", monthStart, monthEnd, true);
        createReservation(rich, "10.00", ReservationState.CONFIRMED, "0101-2026-002088",
                visit2.atTime(10, 15));
        createHolder(rich, "Durand", "Marie", "06 12 34 56 78", "marie.durand@example.fr");

        // --- CARD_BABIES: community member, community earn ---
        FidelityAccount babies = createAccount(CARD_BABIES, AccountStatus.ACTIVE,
                LocalDateTime.of(2026, 2, 10, 10, 0), LocalDateTime.of(2026, 7, 10, 18, 30));
        createMovement(babies, MovementType.ADJUSTMENT, "12.50", LocalDate.of(2026, 6, 1), 2026,
                null, null, "Initial demo balance (seed)");
        createMovement(babies, MovementType.EARN, "6.00", LocalDate.of(2026, 7, 10), 2026,
                "COMMUNITY_BABIES", "0101-2026-001300", null);
        createVisit(CARD_BABIES, LocalDate.of(2026, 7, 10), "0101-2026-001300");
        createMembership(babies, "BABIES", LocalDate.of(2026, 2, 10), null);
        createHolder(babies, "Nguyen", "Linh", "+33 6 98 76 54 32", "linh.nguyen@example.fr");

        // --- CARD_STUDENT: active + expired memberships, earn this month ---
        FidelityAccount student = createAccount(CARD_STUDENT, AccountStatus.ACTIVE,
                LocalDateTime.of(2026, 3, 1, 11, 0), now.minusDays(2));
        createMovement(student, MovementType.ADJUSTMENT, "20.00", LocalDate.of(2026, 6, 1), 2026,
                null, null, "Initial demo balance (seed)");
        createMovement(student, MovementType.EARN, "4.00", visit3, visit3.getYear(),
                "COMMUNITY_STUDENTS", "0101-2026-002042", null);
        createVisit(CARD_STUDENT, visit3, "0101-2026-002042");
        createMembership(student, "STUDENTS", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 31));
        createMembership(student, "SMALL_BUDGETS", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28));
        createHolder(student, "Lefèvre", "Théo", "07 55 44 33 22", "theo.lefevre@example.fr");

        // --- CARD_PENDING: accrues but cannot burn (§25.3) ---
        FidelityAccount pending = createAccount(CARD_PENDING, AccountStatus.PENDING_ACTIVATION,
                null, now.minusDays(10));
        createMovement(pending, MovementType.ADJUSTMENT, "5.00", LocalDate.of(2026, 7, 20), 2026,
                null, null, "Accrued before activation (seed)");
        createHolder(pending, "Martin", "Paul", "06 00 00 00 01", null);

        // --- CARD_NEGATIVE: return debit beyond the remaining balance (I7) ---
        FidelityAccount negative = createAccount(CARD_NEGATIVE, AccountStatus.ACTIVE,
                LocalDateTime.of(2026, 4, 1, 9, 0), LocalDateTime.of(2026, 7, 22, 17, 0));
        createMovement(negative, MovementType.EARN, "2.50", LocalDate.of(2026, 7, 5), 2026,
                "FL_WEEKEND", "0101-2026-001100", null);
        createMovement(negative, MovementType.BURN, "-2.00", LocalDate.of(2026, 7, 12), 2026,
                null, "0101-2026-001180", null);
        createMovement(negative, MovementType.RETURN_DEBIT, "-2.20", LocalDate.of(2026, 7, 22), 2026,
                "FL_WEEKEND", "0101-2026-001100R", null);
        createVisit(CARD_NEGATIVE, LocalDate.of(2026, 7, 5), "0101-2026-001100");
        createVisit(CARD_NEGATIVE, LocalDate.of(2026, 7, 12), "0101-2026-001180");
        createHolder(negative, "Durand", "Jacques", "06 12 34 56 99", "jacques.durand@example.fr");

        // --- CARD_LOST -> CARD_SUCCESSOR: loss/theft transfer chain (§32.2, §34.3) ---
        FidelityAccount lost = createAccount(CARD_LOST, AccountStatus.RESILIATED,
                LocalDateTime.of(2026, 2, 1, 9, 0), LocalDateTime.of(2026, 5, 10, 12, 0));
        createMovement(lost, MovementType.EARN, "15.00", LocalDate.of(2026, 5, 10), 2026,
                "SOCLE_4_MARQUES", "0101-2026-000900", null);
        createMovement(lost, MovementType.TRANSFER, "-15.00", LocalDate.of(2026, 7, 1), 2026,
                null, "TRANSFER:" + CARD_LOST + "->" + CARD_SUCCESSOR, "Transfer to " + CARD_SUCCESSOR);
        lost.transferredToCard = CARD_SUCCESSOR;
        FidelityAccount successor = createAccount(CARD_SUCCESSOR, AccountStatus.ACTIVE,
                LocalDateTime.of(2026, 7, 1, 9, 0), null);
        createMovement(successor, MovementType.TRANSFER, "15.00", LocalDate.of(2026, 7, 1), 2026,
                null, "TRANSFER:" + CARD_LOST + "->" + CARD_SUCCESSOR + ":2026",
                "Transfer from " + CARD_LOST + " (earnYear 2026)");
        createHolder(successor, "Bernard", "Sophie", "06 77 88 99 00", "sophie.bernard@example.fr");

        // --- CARD_RESERVED: live lease holding part of the balance (I11) ---
        FidelityAccount reserved = createAccount(CARD_RESERVED, AccountStatus.ACTIVE,
                LocalDateTime.of(2026, 5, 1, 9, 0), now.minusDays(1));
        createMovement(reserved, MovementType.ADJUSTMENT, "30.00", LocalDate.of(2026, 7, 1), 2026,
                null, null, "Initial demo balance (seed)");
        createReservation(reserved, "8.00", ReservationState.ACTIVE, "0101-2026-003001",
                now.plusMinutes(30));
        createHolder(reserved, "García", "Ana", "0033 6 11 22 33 44", "ana.garcia@example.fr");

        // --- CARD_VOIDED: never activated, voided by the two-month CGU batch (§16) ---
        FidelityAccount voided = createAccount(CARD_VOIDED, AccountStatus.RESILIATED,
                null, LocalDateTime.of(2026, 4, 5, 10, 0));
        createMovement(voided, MovementType.EARN, "2.40", LocalDate.of(2026, 4, 5), 2026,
                "FL_WEEKEND", "0101-2026-000800", null);
        createMovement(voided, MovementType.ACTIVATION_VOID, "-2.40", LocalDate.of(2026, 6, 1), 2026,
                null, null, "Not activated within two months (CGU)");
        createVisit(CARD_VOIDED, LocalDate.of(2026, 4, 5), "0101-2026-000800");

        // --- A return held before its origin ticket arrived (§29.3) ---
        createPendingReturn("0101-2026-002500R", "0101-2026-000042",
                "{\"returnTicketRef\":\"0101-2026-002500R\",\"originTicketRef\":\"0101-2026-000042\","
                        + "\"fiscalDate\":\"" + today + "\",\"lines\":[{\"lineId\":\"L1\",\"quantity\":1.0}]}");

        // Materialize every balance from the ledger (§14).
        for (FidelityAccount account : FidelityAccount.<FidelityAccount>listAll()) {
            account.balance = FidelityMovement.computeBalance(account);
        }
    }

    /**
     * Seeds the batch run history shown on the "Programme" supervision screen
     * (§23.4), consistent with the seeded EXPIRY and ACTIVATION_VOID movements; the
     * 24-month purge has not run yet in this world.
     */
    private void loadBatchHistory() {
        createBatchRun("EXPIRY", LocalDateTime.of(2026, 3, 1, 2, 0), 1, "30.00");
        createBatchRun("ACTIVATION_VOID", LocalDateTime.of(2026, 6, 1, 2, 0), 1, "2.40");
    }

    // --------------------------------------------------
    // Creation helpers
    // --------------------------------------------------

    /**
     * Creates and persists a program setting.
     *
     * @param key   the setting key (one of the {@code KEY_*} constants)
     * @param value the setting value, as text
     */
    private void createSetting(String key, String value) {
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.key = key;
        setting.value = value;
        setting.persist();
    }

    /**
     * Creates and persists a community.
     *
     * @param code                the unique business code
     * @param label               the human label
     * @param monthlyCap          the per-card monthly cap in euro, or null
     * @param enrollmentCap       the maximum number of memberships, or null
     * @param renewalStartMonth   first month (1-12) of the renewal window, or null
     * @param renewalEndMonth     last month (1-12) of the renewal window, or null
     * @param eligibilityCriteria the descriptive eligibility criterion
     */
    private void createCommunity(String code, String label, String monthlyCap, Integer enrollmentCap,
                                 Integer renewalStartMonth, Integer renewalEndMonth, String eligibilityCriteria) {
        FidelityCommunity community = new FidelityCommunity();
        community.code = code;
        community.label = label;
        community.monthlyCap = monthlyCap != null ? new BigDecimal(monthlyCap) : null;
        community.enrollmentCap = enrollmentCap;
        community.renewalStartMonth = renewalStartMonth;
        community.renewalEndMonth = renewalEndMonth;
        community.eligibilityCriteria = eligibilityCriteria;
        community.persist();
    }

    /**
     * Creates and persists a product family.
     *
     * @param code        the unique business code
     * @param description the display description
     * @return the persisted family
     */
    private ProductFamily createFamily(String code, String description) {
        ProductFamily family = new ProductFamily();
        family.code = code;
        family.description = description;
        family.persist();
        return family;
    }

    /**
     * Creates and persists a product, optionally linking it to a family.
     *
     * @param family          the family to attach the product to, or null
     * @param ean             the unique EAN code
     * @param name            the product name
     * @param description     the product description
     * @param brand           the product brand, or null
     * @param referenceWeight the reference weight in kilograms
     * @param referenceVolume the reference volume in liters
     * @param productType     the product type (UNIT, WEIGHT or VOLUME)
     * @param unitName        the display unit
     * @return the persisted product
     */
    private Product createProduct(ProductFamily family, String ean, String name, String description, String brand,
                                  String referenceWeight, String referenceVolume,
                                  ProductType productType, String unitName) {
        Product product = new Product();
        product.ean = ean;
        product.name = name;
        product.description = description;
        product.brand = brand;
        product.referenceWeight = new BigDecimal(referenceWeight);
        product.referenceVolume = new BigDecimal(referenceVolume);
        product.productType = productType;
        product.unitName = unitName;
        product.active = true;
        product.persist();
        if (family != null) {
            family.products.add(product);
        }
        return product;
    }

    /**
     * Creates and persists an earn rule with its JSON specification (tiers are
     * materialized by the entity's {@code @PrePersist} callback).
     *
     * @param code              the stable business code
     * @param type              the factory type code
     * @param label             the printed label
     * @param validFrom         start of the validity window (inclusive)
     * @param validTo           end of the validity window (exclusive), or null
     * @param priority          the evaluation priority (higher evaluates first)
     * @param exclusive         whether the rule consumes its lines (§15, I2)
     * @param monthlyCapPerCard the per-card monthly cap in euro, or null
     * @param specification     the mechanic-specific JSON specification
     */
    private void createRule(String code, String type, String label, LocalDateTime validFrom, LocalDateTime validTo,
                            int priority, boolean exclusive, String monthlyCapPerCard, String specification) {
        FidelityRule rule = new FidelityRule();
        rule.code = code;
        rule.type = type;
        // Every demo crediting rule prints under the PRODUCT advantage group
        // (RFP BO-03-03-25); the exclusion rule carries none.
        rule.advantageType = FidelityRule.TYPE_PROGRAM_EXCLUSION.equals(type) ? null : "PRODUCT";
        rule.label = label;
        rule.validFrom = validFrom;
        rule.validTo = validTo;
        rule.priority = priority;
        rule.exclusive = exclusive;
        rule.monthlyCapPerCard = monthlyCapPerCard != null ? new BigDecimal(monthlyCapPerCard) : null;
        rule.specification = specification;
        rule.active = true;
        rule.persist();
    }

    /**
     * Creates and persists a loyalty account; the balance is materialized later from
     * the movements.
     *
     * @param cardNumber  the 13-digit card number (reserved prefix 299, §33.1)
     * @param status      the account status
     * @param activatedAt when the account was activated, or null while pending
     * @param lastUsedAt  the last fiscal use, or null
     * @return the persisted account
     */
    private FidelityAccount createAccount(String cardNumber, AccountStatus status,
                                          LocalDateTime activatedAt, LocalDateTime lastUsedAt) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = cardNumber;
        account.status = status;
        account.activatedAt = activatedAt;
        account.lastUsedAt = lastUsedAt;
        account.persist();
        return account;
    }

    /**
     * Creates and persists a holder identity for a demo card — the local fallback
     * directory (§33.3). Two "Durand" rows exercise the multi-match name lookup.
     *
     * @param account   the account the identity belongs to
     * @param lastName  the holder's last name
     * @param firstName the holder's first name
     * @param phone     the holder's phone, in a keyed-in format, or null
     * @param email     the holder's e-mail, or null
     */
    private void createHolder(FidelityAccount account, String lastName, String firstName,
                              String phone, String email) {
        CardHolder holder = new CardHolder();
        holder.account = account;
        holder.lastName = lastName;
        holder.lastNameSearch = CardHolder.searchName(lastName);
        holder.firstName = firstName;
        holder.firstNameSearch = CardHolder.searchName(firstName);
        holder.phone = phone;
        holder.phoneSearch = CardHolder.searchPhone(phone);
        holder.email = CardHolder.normalizeEmail(email);
        holder.persist();
    }

    /**
     * Creates and persists a ledger movement.
     *
     * @param account   the account the movement belongs to
     * @param type      the movement type
     * @param amount    the signed amount in euro (credits positive, debits negative)
     * @param date      the fiscal date of the movement
     * @param earnYear  the civil year of acquisition (preserved by transfers, §34.3)
     * @param ruleCode  the crediting rule code, or null for rule-less types (§29.4)
     * @param ticketRef the originating ticket reference, or null
     * @param reason    the reason shown in the history, or null
     */
    private void createMovement(FidelityAccount account, MovementType type, String amount, LocalDate date,
                                int earnYear, String ruleCode, String ticketRef, String reason) {
        FidelityMovement movement = new FidelityMovement();
        movement.account = account;
        movement.type = type;
        movement.amount = new BigDecimal(amount);
        movement.movementDate = date;
        movement.earnYear = earnYear;
        movement.ruleCode = ruleCode;
        movement.ticketRef = ticketRef;
        movement.reason = reason;
        movement.persist();
    }

    /**
     * Creates and persists a membership window.
     *
     * @param account       the member account
     * @param communityCode the code of the community joined
     * @param validFrom     start of the membership window (inclusive)
     * @param validTo       end of the membership window (inclusive), or null while open
     */
    private void createMembership(FidelityAccount account, String communityCode,
                                  LocalDate validFrom, LocalDate validTo) {
        FidelityMembership membership = new FidelityMembership();
        membership.account = account;
        membership.community = FidelityCommunity.findByCode(communityCode);
        membership.validFrom = validFrom;
        membership.validTo = validTo;
        membership.persist();
    }

    /**
     * Creates and persists a per-card rule activation (e-coupon or challenge, §14).
     *
     * @param account     the activated account
     * @param ruleCode    the code of the rule enabled
     * @param periodStart start of the activation period (inclusive)
     * @param periodEnd   end of the activation period (inclusive), or null
     * @param missionDone whether the optional challenge mission is completed
     */
    private void createActivation(FidelityAccount account, String ruleCode,
                                  LocalDate periodStart, LocalDate periodEnd, boolean missionDone) {
        FidelityActivation activation = new FidelityActivation();
        activation.account = account;
        activation.ruleCode = ruleCode;
        activation.periodStart = periodStart;
        activation.periodEnd = periodEnd;
        activation.missionDone = missionDone;
        activation.persist();
    }

    /**
     * Creates and persists a burn reservation lease.
     *
     * @param account   the account the lease holds balance on
     * @param amount    the reserved amount in euro
     * @param state     the lease state
     * @param ticketRef the ticket the lease is tied to
     * @param expiresAt the lease expiry instant
     */
    private void createReservation(FidelityAccount account, String amount, ReservationState state,
                                   String ticketRef, LocalDateTime expiresAt) {
        FidelityReservation reservation = new FidelityReservation();
        reservation.account = account;
        reservation.amount = new BigDecimal(amount);
        reservation.state = state;
        reservation.ticketRef = ticketRef;
        reservation.expiresAt = expiresAt;
        reservation.persist();
    }

    /**
     * Creates and persists a header-only visit trace ({@code NO_MOVEMENT}): a pass at
     * the register on a civil day, whether it earned or not (§29.1).
     *
     * @param cardNumber the card seen at the register
     * @param fiscalDate the civil day of the visit at the program zone
     * @param ticketRef  the ticket reference (idempotency key, §29.4)
     */
    private void createVisit(String cardNumber, LocalDate fiscalDate, String ticketRef) {
        EarnTrace trace = new EarnTrace();
        trace.ticketRef = ticketRef;
        trace.cardNumber = cardNumber;
        trace.storeCode = STORE_CODE;
        trace.fiscalDate = fiscalDate;
        trace.status = EarnTrace.STATUS_NO_MOVEMENT;
        trace.persist();
    }

    /**
     * Creates and persists the rich card's full SUCCESS earn trace (§24.2): a socle
     * line matching its 3.40 € EARN movement and a Lay's line seeding a 4.50 €
     * challenge running base — 0.50 € short of the first 5.00 € tier, so the next
     * eligible basket demonstrably crosses it.
     *
     * @param cardNumber the card the ticket carried
     * @param fiscalDate the fiscal date of the ticket
     * @param ticketRef  the ticket reference
     * @param earnTotal  the displayed and recalculated earn total in euro
     */
    private void createEarnTrace(String cardNumber, LocalDate fiscalDate, String ticketRef, String earnTotal) {
        EarnTrace trace = new EarnTrace();
        trace.ticketRef = ticketRef;
        trace.cardNumber = cardNumber;
        trace.storeCode = STORE_CODE;
        trace.fiscalDate = fiscalDate;
        trace.status = EarnTrace.STATUS_SUCCESS;
        trace.displayedEarn = new BigDecimal(earnTotal);
        trace.recalculatedEarn = new BigDecimal(earnTotal);
        trace.addLine(createTraceLine("L1", "3300000000002", "SOCLE_5_MARQUES", "68.00", "3.40", "3"));
        trace.addLine(createTraceLine("L2", "3400000000070", "CHALLENGE_DEMO", "4.50", "0.00", "3"));
        trace.persist();
    }

    /**
     * Builds one earn detail line of a trace (persisted by cascade from its header).
     *
     * @param lineId     the valuation line id
     * @param ean        the EAN of the line
     * @param ruleCode   the code of the crediting rule
     * @param baseAmount the eligible net base in euro
     * @param earnAmount the earn granted on the line in euro
     * @param quantity   the eligible quantity of the line
     * @return the built line, not yet persisted
     */
    private EarnTraceLine createTraceLine(String lineId, String ean, String ruleCode,
                                          String baseAmount, String earnAmount, String quantity) {
        EarnTraceLine line = new EarnTraceLine();
        line.lineId = lineId;
        line.ean = ean;
        line.ruleCode = ruleCode;
        line.baseAmount = new BigDecimal(baseAmount);
        line.earnAmount = new BigDecimal(earnAmount);
        line.quantity = new BigDecimal(quantity);
        return line;
    }

    /**
     * Creates and persists a return held until its origin ticket arrives (§29.3).
     *
     * @param returnTicketRef the return ticket reference (idempotency key)
     * @param originTicketRef the origin ticket reference awaited
     * @param payload         the verbatim return event payload, replayed on arrival
     */
    private void createPendingReturn(String returnTicketRef, String originTicketRef, String payload) {
        PendingReturn pendingReturn = new PendingReturn();
        pendingReturn.returnTicketRef = returnTicketRef;
        pendingReturn.originTicketRef = originTicketRef;
        pendingReturn.payload = payload;
        pendingReturn.persist();
    }

    /**
     * Creates and persists a batch run record for the supervision screen (§23.4).
     *
     * @param batchType        the batch type (EXPIRY, PURGE or ACTIVATION_VOID)
     * @param runAt            when the batch ran, at the program zone
     * @param accountsAffected the number of accounts the batch touched
     * @param totalAmount      the total amount moved (positive magnitude), in euro
     */
    private void createBatchRun(String batchType, LocalDateTime runAt, int accountsAffected, String totalAmount) {
        BatchRunLog run = new BatchRunLog();
        run.batchType = batchType;
        run.runAt = runAt;
        run.accountsAffected = accountsAffected;
        run.totalAmount = new BigDecimal(totalAmount);
        run.persist();
    }
}
