package com.intermarche.e2e;

import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group H — the Cards administration screen (§23.1, e2escenarios-imfid.md
 * "## H. UI Cartes"): card creation with a generated EAN-13 number (H1), the card sheet with
 * its scannable barcode, month counters, active reservation and history (H2 [W]), the
 * ADJUSTMENT gesture with its mandatory reason and amount (H3), the loss/theft transfer
 * preserving earnYears and migrating dependents (H4), and the resiliation with its active-lease
 * guard (H5). Every scenario boots the real application under {@link QuarkusTest} against the
 * DataInitializer-rebuilt world (wipe + reload at each boot): the seeded facts are asserted,
 * never re-seeded, and every destructive gesture is isolated onto out-of-range {@code 999…}
 * test cards removed in a {@code finally}, so the nine seeded cards are never perturbed.
 * <p>
 * The {@code /ui/*} POST → 303 → notice cycle (§21.3) is driven over a form session
 * ({@code j_security_check} + {@code quarkus-credential} cookie): the redirect {@code Location}
 * is read without being followed (following it would drop the session cookie), so both the
 * one-shot notice and the target path are asserted from the header. Card sheets are read as
 * HTML over the same session; H2 alone drives a real headless browser so {@code fidelity.js}
 * renders the EAN-13 barcode. Admin notices are the English literals the catalog quotes;
 * BigDecimal amounts are compared by {@code compareTo} (§30.5); DB assertions go through
 * Panache under {@link QuarkusTransaction}, always by natural key, never by absolute id.
 */
@QuarkusTest
@WithPlaywright
class GroupHIT {

    /**
     * Bootstrap administrator login name (SecurityBootstrap default), role {@code fid-admin}.
     */
    private static final String ADMIN_USER = "admin";

    /**
     * Bootstrap administrator password (SecurityBootstrap default in dev/test).
     */
    private static final String ADMIN_PASSWORD = "admin-password";

    /**
     * The form-session cookie set by {@code j_security_check} for the admin UI.
     */
    private static final String SESSION_COOKIE = "quarkus-credential";

    /**
     * The reserved card prefix imfid opens every generated number with (§33.1).
     */
    private static final String CARD_PREFIX = "299";

    /**
     * The seeded card carrying an ACTIVE reservation lease of 8.00 € (I11), used by H2 (the
     * sheet showing the live reservation) and H5 (the resiliation refused on an active lease).
     */
    private static final String CARD_RESERVED = "2990000000088";

    /**
     * Out-of-range test card of H3 — a plain ACTIVE account the ADJUSTMENT gesture writes onto,
     * isolated from the seeded world by a {@code 999} prefix.
     */
    private static final String CARD_ADJUST = "9990000000101";

    /**
     * Out-of-range test card of H4 — the transfer source, seeded with two earnYears, a
     * membership, an activation and a visit so the migration can be proven end to end.
     */
    private static final String CARD_TRANSFER = "9990000000201";

    /**
     * Out-of-range test card of H5 — the resiliation-success source, seeded with a known balance
     * so its confirmation dialog is deterministic.
     */
    private static final String CARD_RESILIATE = "9990000000301";

    /**
     * The seeded community a migrated membership references (§23.2).
     */
    private static final String COMMUNITY_BABIES = "BABIES";

    /**
     * The instant H3 freezes the clock at so the ADJUSTMENT's {@code earnYear} is the civil year
     * of the gesture (§30.3), deterministic whatever day the campaign runs.
     */
    private static final LocalDateTime ADJUST_FROZEN_AT = LocalDateTime.of(2026, 8, 22, 10, 0);

    /**
     * The Playwright browser context injected by quarkus-playwright (headless Chromium).
     */
    @InjectPlaywright
    BrowserContext browser;

    /**
     * The test HTTP root of the booted application, used to build absolute URLs for H2.
     */
    @TestHTTPResource("/")
    URL baseUrl;

    // --------------------------------------------------
    // H1 — creation, generated EAN-13 number (§33.1)
    // --------------------------------------------------

    /**
     * H1 — the number is generated, never keyed in: creating a card redirects to its sheet with
     * the {@code Card <n> created} notice and a fresh {@code 299}-prefixed 13-digit number whose
     * EAN-13 check digit is valid and which collides with no seeded card; a second creation
     * yields yet another distinct number (collisions resolved by walking forward, §33.1).
     */
    @Test
    void h1_creationGeneratesAValidPrefixedEan13NumberNeverKeyedIn() {
        String first = null;
        String second = null;
        try {
            String firstLocation = postForm("/ui/cards/create", "status", "ACTIVE").header("Location");
            first = cardOf(firstLocation);
            assertEquals("Card " + first + " created", noticeOf(firstLocation), "the success notice names the new card (H1)");
            assertTrue(first.startsWith(CARD_PREFIX), "the number opens with the reserved 299 prefix (§33.1)");
            assertEquals(13, first.length(), "the number is a 13-digit EAN-13 (§33.1)");
            assertTrue(first.chars().allMatch(Character::isDigit), "the number is all digits (§33.1)");
            assertTrue(isValidEan13(first), "the last digit is a valid EAN-13 check key (§33.1)");
            assertEquals(AccountStatus.ACTIVE, statusOf(first), "the created card is persisted ACTIVE");
            String secondLocation = postForm("/ui/cards/create", "status", "ACTIVE").header("Location");
            second = cardOf(secondLocation);
            assertTrue(isValidEan13(second), "the second number is a valid EAN-13 too (§33.1)");
            assertFalse(second.equals(first), "a second creation never repeats the first number (marche avant, §33.1)");
        } finally {
            purgeTestCard(first);
            purgeTestCard(second);
        }
    }

    // --------------------------------------------------
    // H2 — the card sheet in a real browser [W] (§23.1, §33.1)
    // --------------------------------------------------

    /**
     * H2 [W] — the sheet of the reserved card {@code …088} rendered in a real headless browser:
     * {@code fidelity.js} fills the {@code <svg class="ean13" data-ean=…>} with exactly the bars
     * of the number's EAN-13 encoding (a genuinely scannable barcode), the month counters are
     * shown, the ACTIVE 8.00 € reservation lease is displayed (not the empty placeholder), and
     * the movement history carries the card's seeded ADJUSTMENT.
     */
    @Test
    void h2_sheetRendersScannableBarcodeCountersReservationAndHistory() {
        Page page = login();
        try {
            page.navigate(url("ui/cards/" + CARD_RESERVED));
            assertEquals(CARD_RESERVED, page.locator("svg.ean13").getAttribute("data-ean"),
                    "the barcode carries the card number as its data-ean (§33.1)");
            int bars = page.locator("svg.ean13 rect").count();
            assertTrue(bars > 0, "fidelity.js drew the barcode bars (a real, scannable render)");
            assertEquals(ean13BarCount(CARD_RESERVED), bars,
                    "the drawn bars are exactly the EAN-13 module encoding of the number (scannable)");
            String content = page.content();
            assertTrue(content.contains("Compteurs du mois"), "the month counters section is shown (§23.1)");
            assertTrue(content.contains("Visites"), "the month visit counter is labelled (§25.1)");
            assertTrue(content.contains("Réservation active"), "the active-reservation section is present (§23.1)");
            assertFalse(content.contains("Aucune réservation à bail active sur cette carte."),
                    "the live lease replaces the empty-reservation placeholder (I11)");
            assertTrue(content.contains("8.00 €"), "the ACTIVE 8.00 € lease amount is displayed (I11)");
            assertTrue(content.contains("Mouvements"), "the movement history section is present (§23.1)");
            assertTrue(content.contains("ADJUSTMENT"), "the card's seeded ADJUSTMENT movement is listed (§14)");
        } finally {
            page.close();
        }
    }

    // --------------------------------------------------
    // H3 — the ADJUSTMENT gesture (§32.1)
    // --------------------------------------------------

    /**
     * H3 — the ADJUSTMENT gesture: a blank reason is refused with the literal
     * {@code An adjustment reason is mandatory (§32.1)}, a zero amount with
     * {@code An adjustment amount is mandatory}, and a signed amount with a reason succeeds with
     * the {@code Adjustment posted} notice, writing an ADJUSTMENT movement out of every cap and
     * expirable like earn — its {@code earnYear} is the civil year of the gesture (§30.3).
     */
    @Test
    void h3_adjustmentRequiresReasonAndAmountThenPostsOutOfCaps() {
        seedPlainCard(CARD_ADJUST);
        try {
            DateTimeProvider.setFixedDateTime(ADJUST_FROZEN_AT);
            String missingReason = noticeOf(postForm("/ui/cards/" + CARD_ADJUST + "/adjust",
                    "amount", "5.00", "reason", "").header("Location"));
            assertEquals("An adjustment reason is mandatory (§32.1)", missingReason,
                    "a blank reason is refused with the literal (§32.1)");
            assertNull(movementOf(CARD_ADJUST, MovementType.ADJUSTMENT), "a refused adjustment writes nothing (§30.2)");
            String missingAmount = noticeOf(postForm("/ui/cards/" + CARD_ADJUST + "/adjust",
                    "amount", "0", "reason", "Compensation").header("Location"));
            assertEquals("An adjustment amount is mandatory", missingAmount,
                    "a zero amount is refused with the literal (§32.1)");
            assertNull(movementOf(CARD_ADJUST, MovementType.ADJUSTMENT), "a refused adjustment still writes nothing (§30.2)");
            String ok = noticeOf(postForm("/ui/cards/" + CARD_ADJUST + "/adjust",
                    "amount", "5.55", "reason", "Goodwill gesture").header("Location"));
            assertEquals("Adjustment posted", ok, "a valid adjustment reports success (§32.1)");
            FidelityMovement posted = movementOf(CARD_ADJUST, MovementType.ADJUSTMENT);
            assertNotNull(posted, "the adjustment writes one ADJUSTMENT movement (§32.1)");
            assertEquals(0, posted.amount.compareTo(new BigDecimal("5.55")), "the ADJUSTMENT carries the signed amount");
            assertEquals("Goodwill gesture", posted.reason, "the ADJUSTMENT carries the mandatory reason (§32.1)");
            assertEquals(2026, posted.earnYear, "the ADJUSTMENT is expirable — earnYear is the year of the gesture (§30.3)");
            assertEquals(0, balanceOf(CARD_ADJUST).compareTo(new BigDecimal("5.55")), "the balance follows the adjustment (§14)");
        } finally {
            DateTimeProvider.clear();
            purgeTestCard(CARD_ADJUST);
        }
    }

    // --------------------------------------------------
    // H4 — loss/theft transfer (§28.1, §34.3)
    // --------------------------------------------------

    /**
     * H4 — the loss/theft transfer: the sheet raises the literal confirmation dialog, the POST
     * mints a fresh card carrying a TRANSFER-in per preserved earnYear (2024 and 2025, never
     * rejuvenated), a TRANSFER-out on the source, and migrates the membership, the activation and
     * the visit while resiliating the source and stamping its {@code transferredToCard};
     * re-transferring the now-resiliated card is refused with {@code A resiliated card cannot be
     * transferred} (§28.1).
     */
    @Test
    void h4_transferPreservesEarnYearsMigratesDependentsAndResiliatesSource() {
        seedTransferSource();
        String target = null;
        try {
            String sheet = cardSheetHtml(CARD_TRANSFER);
            assertTrue(sheet.contains("Transférer la carte " + CARD_TRANSFER
                            + " (solde 12.00 €) vers une nouvelle carte ? L\\'ancienne carte sera résiliée."),
                    "the sheet raises the literal transfer confirmation (§23.1)");
            String location = postForm("/ui/cards/" + CARD_TRANSFER + "/transfer").header("Location");
            target = cardOf(location);
            assertEquals("Transferred from " + CARD_TRANSFER + " to " + target, noticeOf(location),
                    "the transfer reports the source and the minted target (§28.1)");
            assertTrue(target.startsWith(CARD_PREFIX) && target.length() == 13, "the target is a freshly generated card (§33.1)");
            assertEquals(AccountStatus.RESILIATED, statusOf(CARD_TRANSFER), "the source is resiliated (§28.1)");
            assertEquals(target, transferredToCardOf(CARD_TRANSFER), "the source points at its successor (§34.3)");
            assertEquals(AccountStatus.ACTIVE, statusOf(target), "the target is ACTIVE (§28.1)");
            assertEquals(0, balanceOf(target).compareTo(new BigDecimal("12.00")), "the whole balance moves to the target (§34.3)");
            assertEquals(0, transferInAmount(target, 2024).compareTo(new BigDecimal("5.00")),
                    "the 2024 residual is carried in on its preserved earnYear (§34.3)");
            assertEquals(0, transferInAmount(target, 2025).compareTo(new BigDecimal("7.00")),
                    "the 2025 residual is carried in on its preserved earnYear (§34.3)");
            assertEquals(Set.of(2024, 2025), transferInYears(target), "no earnYear is rejuvenated to the transfer year (§34.3)");
            assertEquals(0, movementOf(CARD_TRANSFER, MovementType.TRANSFER).amount.compareTo(new BigDecimal("-12.00")),
                    "the source carries the TRANSFER-out of the full balance (§34.3)");
            assertTrue(holdsMembership(target, COMMUNITY_BABIES), "the membership is migrated to the target (§28.1)");
            assertTrue(membershipsOf(CARD_TRANSFER).isEmpty(), "the source keeps no membership after the transfer (§28.1)");
            assertFalse(activationsOf(target).isEmpty(), "the activation is migrated to the target (§28.1)");
            assertTrue(activationsOf(CARD_TRANSFER).isEmpty(), "the source keeps no activation after the transfer (§28.1)");
            assertTrue(visitCount(target) >= 1, "the visit trace is re-attached to the target (§28.1)");
            assertEquals(0, visitCount(CARD_TRANSFER), "the source keeps no visit after the transfer (§28.1)");
            String refused = noticeOf(postForm("/ui/cards/" + CARD_TRANSFER + "/transfer").header("Location"));
            assertEquals("A resiliated card cannot be transferred", refused,
                    "re-transferring the resiliated card is refused with the literal (§28.1)");
        } finally {
            purgeTestCard(target);
            purgeTestCard(CARD_TRANSFER);
        }
    }

    // --------------------------------------------------
    // H5 — resiliation (§28.1)
    // --------------------------------------------------

    /**
     * H5 — the resiliation: the sheet raises the literal confirmation dialog, resiliating a
     * lease-free card reports {@code Card resiliated} and moves it to RESILIATED, while
     * resiliating the seeded {@code …088} that holds an ACTIVE lease is refused with
     * {@code Refused: the card holds an active burn reservation (§28.1)} and leaves it ACTIVE.
     */
    @Test
    void h5_resiliationConfirmsSucceedsWithoutLeaseAndIsRefusedOnAnActiveLease() {
        seedCardWithCredit(CARD_RESILIATE, "3.00");
        try {
            String sheet = cardSheetHtml(CARD_RESILIATE);
            assertTrue(sheet.contains("Résilier la carte " + CARD_RESILIATE
                            + " (solde 3.00 €) ? Le solde ne bougera plus jamais."),
                    "the sheet raises the literal resiliation confirmation (§23.1)");
            String ok = noticeOf(postForm("/ui/cards/" + CARD_RESILIATE + "/resiliate").header("Location"));
            assertEquals("Card resiliated", ok, "a lease-free resiliation reports success (§28.1)");
            assertEquals(AccountStatus.RESILIATED, statusOf(CARD_RESILIATE), "the resiliated card moves to RESILIATED (§28.1)");
            String refused = noticeOf(postForm("/ui/cards/" + CARD_RESERVED + "/resiliate").header("Location"));
            assertEquals("Refused: the card holds an active burn reservation (§28.1)", refused,
                    "resiliating a card holding an active lease is refused with the literal (§28.1)");
            assertEquals(AccountStatus.ACTIVE, statusOf(CARD_RESERVED), "the refused resiliation leaves …088 ACTIVE (§30.2)");
        } finally {
            purgeTestCard(CARD_RESILIATE);
        }
    }

    // --------------------------------------------------
    // Helpers — HTTP (form session)
    // --------------------------------------------------

    /**
     * Logs in over {@code j_security_check} as the admin and returns the session cookie value.
     *
     * @return The {@code quarkus-credential} cookie value.
     */
    private static String adminSession() {
        String cookie = RestAssured.given().redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("j_username", ADMIN_USER).formParam("j_password", ADMIN_PASSWORD)
                .post("/j_security_check").cookie(SESSION_COOKIE);
        assertNotNull(cookie, "the admin login must set the session cookie");
        return cookie;
    }

    /**
     * Posts a form over a fresh admin session without following the 303, so both the notice and
     * the target path stay readable on the {@code Location} header (§21.3).
     *
     * @param path       The POST path.
     * @param formParams The alternating form parameter name/value pairs.
     * @return The un-followed HTTP response.
     */
    private static Response postForm(String path, String... formParams) {
        io.restassured.specification.RequestSpecification request = RestAssured.given().redirects().follow(false)
                .cookie(SESSION_COOKIE, adminSession()).contentType(ContentType.URLENC);
        for (int i = 0; i + 1 < formParams.length; i += 2) {
            request = request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.post(path);
    }

    /**
     * Reads a card sheet as HTML over a fresh admin session.
     *
     * @param card The card number.
     * @return The rendered sheet body.
     */
    private static String cardSheetHtml(String card) {
        return RestAssured.given().cookie(SESSION_COOKIE, adminSession())
                .get("/ui/cards/" + card).then().statusCode(200).extract().asString();
    }

    /**
     * Extracts the decoded {@code notice} query parameter from a redirect {@code Location}.
     *
     * @param location The redirect Location header.
     * @return The decoded notice, or the empty string when absent.
     */
    private static String noticeOf(String location) {
        assertNotNull(location, "the POST must redirect with a notice (§21.3)");
        String query = location.substring(location.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            if (pair.startsWith("notice=")) {
                return URLDecoder.decode(pair.substring("notice=".length()), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /**
     * Extracts the card number from a redirect {@code Location} of the form
     * {@code /ui/cards/<card>?notice=…}.
     *
     * @param location The redirect Location header.
     * @return The card number segment.
     */
    private static String cardOf(String location) {
        assertNotNull(location, "the POST must redirect to a card sheet (§21.3)");
        String path = location.contains("?") ? location.substring(0, location.indexOf('?')) : location;
        return path.substring(path.lastIndexOf('/') + 1);
    }

    // --------------------------------------------------
    // Helpers — Playwright
    // --------------------------------------------------

    /**
     * Builds an absolute URL under the booted application's test root.
     *
     * @param path The path without a leading slash.
     * @return The absolute URL string.
     */
    private String url(String path) {
        return baseUrl.toString() + path;
    }

    /**
     * Opens a browser page and signs in as the administrator through the real login form.
     *
     * @return A page authenticated for the admin UI.
     */
    private Page login() {
        Page page = browser.newPage();
        page.navigate(url("ui/login"));
        page.fill("input[name='j_username']", ADMIN_USER);
        page.fill("input[name='j_password']", ADMIN_PASSWORD);
        page.locator("button[type='submit']").click();
        page.waitForURL("**/ui/cards");
        return page;
    }

    // --------------------------------------------------
    // Helpers — EAN-13 (mirrors CardNumberGenerator and fidelity.js)
    // --------------------------------------------------

    /**
     * Recomputes the EAN-13 check digit of a 13-digit number's first twelve digits and confirms
     * it matches the thirteenth (the algorithm of {@code CardNumberGenerator}, §33.1).
     *
     * @param ean The 13-digit number.
     * @return true when the check digit is valid.
     */
    private static boolean isValidEan13(String ean) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = ean.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int check = (10 - (sum % 10)) % 10;
        return check == ean.charAt(12) - '0';
    }

    /**
     * Counts the black bars of the EAN-13 barcode of a number — the number of {@code 1} modules
     * in its 95-module encoding, mirroring {@code fidelity.js} so H2 can assert the drawn bars
     * are exactly the scannable encoding.
     *
     * @param ean The 13-digit number.
     * @return The count of black-bar rects the barcode draws.
     */
    private static int ean13BarCount(String ean) {
        String[] first = {"LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
                "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"};
        String[] l = {"0001101", "0011001", "0010011", "0111101", "0100011",
                "0110001", "0101111", "0111011", "0110111", "0001011"};
        String[] g = {"0100111", "0110011", "0011011", "0100001", "0011101",
                "0111001", "0000101", "0010001", "0001001", "0010111"};
        String[] r = {"1110010", "1100110", "1101100", "1000010", "1011100",
                "1001110", "1010000", "1000100", "1001000", "1110100"};
        int[] digits = new int[13];
        for (int i = 0; i < 13; i++) {
            digits[i] = ean.charAt(i) - '0';
        }
        StringBuilder bits = new StringBuilder("101");
        String pattern = first[digits[0]];
        for (int i = 1; i <= 6; i++) {
            bits.append((pattern.charAt(i - 1) == 'L' ? l : g)[digits[i]]);
        }
        bits.append("01010");
        for (int j = 7; j <= 12; j++) {
            bits.append(r[digits[j]]);
        }
        bits.append("101");
        int ones = 0;
        for (int i = 0; i < bits.length(); i++) {
            if (bits.charAt(i) == '1') {
                ones++;
            }
        }
        return ones;
    }

    // --------------------------------------------------
    // Helpers — database seeding (isolated test cards)
    // --------------------------------------------------

    /**
     * Inserts a plain ACTIVE test account with a zero balance and no movement.
     *
     * @param card The card number.
     */
    private static void seedPlainCard(String card) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = new FidelityAccount();
            account.cardNumber = card;
            account.status = AccountStatus.ACTIVE;
            account.balance = BigDecimal.ZERO;
            account.persist();
        });
    }

    /**
     * Inserts an ACTIVE test account carrying a single EARN credit, materializing its balance.
     *
     * @param card   The card number.
     * @param amount The credit amount, as text.
     */
    private static void seedCardWithCredit(String card, String amount) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = new FidelityAccount();
            account.cardNumber = card;
            account.status = AccountStatus.ACTIVE;
            account.balance = BigDecimal.ZERO;
            account.persist();
            postMovement(account, MovementType.EARN, amount, LocalDate.of(2026, 3, 1), 2026, card + "-EARN");
            account.balance = FidelityMovement.computeBalance(account);
            account.persist();
        });
    }

    /**
     * Inserts the H4 transfer source: an ACTIVE account with a 2024 credit of 5.00 € and a 2025
     * credit of 7.00 € (balance 12.00 €), a BABIES membership, a completed activation and one
     * visit trace — every dependent the transfer must carry over (§28.1, §34.3).
     */
    private static void seedTransferSource() {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = new FidelityAccount();
            account.cardNumber = CARD_TRANSFER;
            account.status = AccountStatus.ACTIVE;
            account.balance = BigDecimal.ZERO;
            account.persist();
            postMovement(account, MovementType.EARN, "5.00", LocalDate.of(2024, 6, 1), 2024, CARD_TRANSFER + "-2024");
            postMovement(account, MovementType.EARN, "7.00", LocalDate.of(2025, 6, 1), 2025, CARD_TRANSFER + "-2025");
            FidelityMembership membership = new FidelityMembership();
            membership.account = account;
            membership.community = FidelityCommunity.findByCode(COMMUNITY_BABIES);
            membership.validFrom = LocalDate.of(2026, 1, 1);
            membership.persist();
            FidelityActivation activation = new FidelityActivation();
            activation.account = account;
            activation.ruleCode = "SOCLE_5_MARQUES";
            activation.periodStart = LocalDate.of(2026, 1, 1);
            activation.missionDone = true;
            activation.persist();
            EarnTrace trace = new EarnTrace();
            trace.ticketRef = CARD_TRANSFER + "-VISIT";
            trace.cardNumber = CARD_TRANSFER;
            trace.fiscalDate = LocalDate.of(2026, 8, 10);
            trace.status = EarnTrace.STATUS_SUCCESS;
            trace.persist();
            account.balance = FidelityMovement.computeBalance(account);
            account.persist();
        });
    }

    /**
     * Persists a movement on an already-persisted account within the current transaction.
     *
     * @param account   The owning account.
     * @param type      The movement type.
     * @param amount    The signed amount, as text.
     * @param date      The fiscal movement date.
     * @param earnYear  The civil year of acquisition.
     * @param ticketRef The unique ticket reference.
     */
    private static void postMovement(FidelityAccount account, MovementType type, String amount,
                                     LocalDate date, int earnYear, String ticketRef) {
        FidelityMovement movement = new FidelityMovement();
        movement.account = account;
        movement.type = type;
        movement.amount = new BigDecimal(amount);
        movement.movementDate = date;
        movement.earnYear = earnYear;
        movement.ruleCode = null;
        movement.ticketRef = ticketRef;
        movement.reason = null;
        movement.persist();
    }

    /**
     * Removes a test card and everything hanging off it (reservations, movements, memberships,
     * activations, visit traces, then the account) in a fresh transaction, restoring the seeded
     * world; a null or unknown card is a no-op.
     *
     * @param card The test card number, or null.
     */
    private static void purgeTestCard(String card) {
        if (card == null) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            if (account != null) {
                FidelityReservation.delete("account", account);
                FidelityMovement.delete("account", account);
                FidelityMembership.delete("account", account);
                FidelityActivation.delete("account", account);
                EarnTrace.delete("cardNumber", card);
                account.delete();
            }
        });
    }

    // --------------------------------------------------
    // Helpers — database reads (no absolute ids)
    // --------------------------------------------------

    /**
     * Reads a card's status in a fresh transaction.
     *
     * @param card The card number.
     * @return The account status.
     */
    private static AccountStatus statusOf(String card) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityAccount.findByCardNumber(card).status);
    }

    /**
     * Reads a card's materialized balance in a fresh transaction.
     *
     * @param card The card number.
     * @return The balance.
     */
    private static BigDecimal balanceOf(String card) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityAccount.findByCardNumber(card).balance);
    }

    /**
     * Reads a card's {@code transferredToCard} pointer in a fresh transaction.
     *
     * @param card The card number.
     * @return The successor card number, or null.
     */
    private static String transferredToCardOf(String card) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityAccount.findByCardNumber(card).transferredToCard);
    }

    /**
     * Finds the single movement of a type on a card, or null, in a fresh transaction.
     *
     * @param card The card number.
     * @param type The movement type.
     * @return The movement, or null.
     */
    private static FidelityMovement movementOf(String card, MovementType type) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            return account == null ? null
                    : FidelityMovement.<FidelityMovement>find("account = ?1 and type = ?2", account, type).firstResult();
        });
    }

    /**
     * Reads the amount of the TRANSFER-in movement of a target card for a preserved earnYear.
     *
     * @param card     The target card number.
     * @param earnYear The preserved earnYear.
     * @return The TRANSFER-in amount.
     */
    private static BigDecimal transferInAmount(String card, int earnYear) {
        FidelityMovement movement = QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            return FidelityMovement.<FidelityMovement>find(
                    "account = ?1 and type = ?2 and earnYear = ?3", account, MovementType.TRANSFER, earnYear).firstResult();
        });
        assertNotNull(movement, card + " must carry a TRANSFER-in for earnYear " + earnYear);
        return movement.amount;
    }

    /**
     * Returns the set of earnYears the positive TRANSFER-in movements of a target card carry.
     *
     * @param card The target card number.
     * @return The earnYear set.
     */
    private static Set<Integer> transferInYears(String card) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            java.util.Set<Integer> years = new java.util.HashSet<>();
            for (FidelityMovement movement : FidelityMovement.<FidelityMovement>list(
                    "account = ?1 and type = ?2", account, MovementType.TRANSFER)) {
                if (movement.amount.signum() > 0) {
                    years.add(movement.earnYear);
                }
            }
            return years;
        });
    }

    /**
     * Lists the memberships of a card in a fresh transaction.
     *
     * @param card The card number.
     * @return The memberships, never null.
     */
    private static List<FidelityMembership> membershipsOf(String card) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            return account == null ? List.of() : FidelityMembership.listForAccount(account);
        });
    }

    /**
     * Indicates whether a card holds a membership in a community, in a fresh transaction.
     *
     * @param card          The card number.
     * @param communityCode The community code.
     * @return true when the card holds a membership in that community.
     */
    private static boolean holdsMembership(String card, String communityCode) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            if (account == null) {
                return false;
            }
            for (FidelityMembership membership : FidelityMembership.listForAccount(account)) {
                if (membership.community != null && communityCode.equals(membership.community.code)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Lists the activations of a card in a fresh transaction.
     *
     * @param card The card number.
     * @return The activations, never null.
     */
    private static List<FidelityActivation> activationsOf(String card) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            return account == null ? List.of() : FidelityActivation.listForAccount(account);
        });
    }

    /**
     * Counts the visit traces attached to a card in a fresh transaction.
     *
     * @param card The card number.
     * @return The visit-trace count.
     */
    private static long visitCount(String card) {
        return QuarkusTransaction.requiringNew().call(() -> EarnTrace.count("cardNumber", card));
    }
}
