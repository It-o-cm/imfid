package com.intermarche.e2e;

import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.PasswordResetToken;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group B — authentication &amp; forgot-password (e2escenarios-imfid.md
 * "## B"). All B scenarios are plain HTTP: RestAssured over the real application booted
 * by {@link QuarkusTest} on the test port, with the DataInitializer-seeded world. No
 * scenario in this group is [W] or [P], so every one is implemented at the RestAssured
 * tier. Form session for {@code /ui/*}, Basic {@code pos}/{@code admin} for the API and
 * imports. DB assertions go through Panache under {@link QuarkusTransaction}; created
 * rows carry unique literal usernames and are cleaned up in the same test.
 */
@QuarkusTest
class GroupBIT {

    /**
     * Bootstrap administrator login name (SecurityBootstrap default), role {@code fid-admin}.
     */
    private static final String ADMIN_USER = "admin";

    /**
     * Bootstrap administrator password (SecurityBootstrap default in dev/test).
     */
    private static final String ADMIN_PASSWORD = "admin-password";

    /**
     * Bootstrap machine login name (SecurityBootstrap default), role {@code pos}, no e-mail.
     */
    private static final String POS_USER = "pos";

    /**
     * Bootstrap machine password (SecurityBootstrap default in dev/test).
     */
    private static final String POS_PASSWORD = "pos-password";

    /**
     * Seeded administrator e-mail address (SecurityBootstrap default), a known reset target.
     */
    private static final String ADMIN_EMAIL = "admin@imfid.local";

    /**
     * The Quarkus form-authentication session cookie name (default, cleared on logout).
     */
    private static final String SESSION_COOKIE = "quarkus-credential";

    // --------------------------------------------------
    // B1 — nominal login chain
    // --------------------------------------------------

    /**
     * B1 — nominal chain: {@code GET /} 303 to {@code /ui/cards}; the anonymous landing
     * redirects to {@code /ui/login}; {@code POST /j_security_check} with valid credentials
     * lands 302 on {@code /ui/cards} (the corrected landing, no longer {@code /ui}) and sets
     * the {@code quarkus-credential} cookie, which then opens {@code /ui/cards} at 200.
     */
    @Test
    void b1_nominalLoginChainLandsOnCards() {
        Response root = RestAssured.given().redirects().follow(false).get("/");
        assertEquals(303, root.statusCode(), "GET / must See-Other");
        assertTrue(locationOf(root).endsWith("/ui/cards"), "GET / must redirect to /ui/cards");
        Response anon = RestAssured.given().redirects().follow(false).get("/ui/cards");
        assertEquals(302, anon.statusCode(), "anonymous /ui/cards must redirect to the login page");
        assertTrue(locationOf(anon).contains("/ui/login"), "anonymous /ui/cards must go to /ui/login");
        Response ok = RestAssured.given().redirects().follow(false)
                .formParam("j_username", ADMIN_USER).formParam("j_password", ADMIN_PASSWORD)
                .post("/j_security_check");
        assertEquals(302, ok.statusCode(), "successful login must redirect");
        assertTrue(locationOf(ok).contains("/ui/cards"), "login landing must be /ui/cards, not /ui");
        String cookie = ok.getCookie(SESSION_COOKIE);
        assertNotNull(cookie, "successful login must set the quarkus-credential cookie");
        assertFalse(cookie.isBlank(), "the credential cookie must not be empty");
        Response authed = RestAssured.given().redirects().follow(false)
                .cookie(SESSION_COOKIE, cookie).get("/ui/cards");
        assertEquals(200, authed.statusCode(), "the session cookie must open /ui/cards");
    }

    // --------------------------------------------------
    // B2 — refused login, presence-based error banner
    // --------------------------------------------------

    /**
     * B2 — refused login: bad credentials redirect to {@code /ui/login?error=true}; the
     * banner {@code Identifiants invalides.} shows for ANY presence of the {@code error}
     * parameter (asymmetry vs imvaluation's strict {@code error=true}), and is absent when
     * the parameter is missing.
     */
    @Test
    void b2_refusedLoginShowsBannerForAnyErrorParam() {
        Response bad = RestAssured.given().redirects().follow(false)
                .formParam("j_username", ADMIN_USER).formParam("j_password", "wrong-password")
                .post("/j_security_check");
        assertEquals(302, bad.statusCode(), "failed login must redirect");
        assertTrue(locationOf(bad).contains("/ui/login"), "failed login must return to /ui/login");
        assertTrue(locationOf(bad).contains("error"), "failed login must carry the error marker");
        Response present = RestAssured.given().get("/ui/login?error=false");
        assertEquals(200, present.statusCode(), "the login page is public");
        assertTrue(present.asString().contains("Identifiants invalides."),
                "the banner must show for any presence of error, even error=false");
        Response absent = RestAssured.given().get("/ui/login");
        assertEquals(200, absent.statusCode(), "the login page is public");
        assertFalse(absent.asString().contains("Identifiants invalides."),
                "the banner must be absent when the error parameter is missing");
    }

    // --------------------------------------------------
    // B3 — logout clears the cookie, no notice
    // --------------------------------------------------

    /**
     * B3 — logout: {@code POST /ui/logout} clears the {@code quarkus-credential} cookie
     * (maxAge 0, path /) and redirects 303 to a bare {@code /ui/login} with no "signed out"
     * notice (difference with imvaluation).
     */
    @Test
    void b3_logoutClearsCookieWithoutNotice() {
        String cookie = loginAndGetCookie(ADMIN_USER, ADMIN_PASSWORD);
        Response out = RestAssured.given().redirects().follow(false)
                .cookie(SESSION_COOKIE, cookie).post("/ui/logout");
        assertEquals(303, out.statusCode(), "logout must See-Other");
        assertTrue(locationOf(out).endsWith("/ui/login"), "logout must land on a bare /ui/login");
        assertFalse(locationOf(out).contains("?"), "logout must carry no query, hence no signed-out notice");
        String setCookie = out.getHeader("Set-Cookie");
        assertNotNull(setCookie, "logout must send a Set-Cookie clearing the credential");
        assertTrue(setCookie.contains(SESSION_COOKIE), "logout must clear the quarkus-credential cookie");
        assertTrue(setCookie.replace(" ", "").contains("Max-Age=0"), "the cleared cookie must have Max-Age 0");
        assertTrue(setCookie.contains("Path=/"), "the cleared cookie must be scoped to path /");
    }

    // --------------------------------------------------
    // B4 — public paths and the default-permit hole
    // --------------------------------------------------

    /**
     * B4 — public paths AND the hole: the explicit permit list ({@code /q/health},
     * {@code /ui/login}, {@code /ui/forgot}, {@code /ui/reset}, {@code /ui/base.css}) serves
     * anonymously; and paths not covered by any policy are permitted by default — the canary
     * {@code GET /ui/fidelity.js} anonymous returns 200 (invert this if a catch-all policy is
     * ever added).
     */
    @Test
    void b4_publicPathsAndDefaultPermitHole() {
        assertEquals(200, RestAssured.given().get("/q/health").statusCode(), "/q/health is permitted");
        assertEquals(200, RestAssured.given().get("/ui/login").statusCode(), "/ui/login is permitted");
        assertEquals(200, RestAssured.given().get("/ui/forgot").statusCode(), "/ui/forgot is permitted");
        assertEquals(200, RestAssured.given().get("/ui/reset").statusCode(), "/ui/reset is permitted");
        assertEquals(200, RestAssured.given().get("/ui/base.css").statusCode(), "/ui/base.css is permitted");
        assertEquals(200, RestAssured.given().get("/ui/fidelity.js").statusCode(),
                "CANARY: /ui/fidelity.js is not covered by any policy and defaults to permit — invert if a catch-all is added");
        assertEquals(200, RestAssured.given().get("/ui/auth.css").statusCode(),
                "/ui/auth.css also defaults to permit (styles the login page)");
        assertEquals(200, RestAssured.given().get("/ui/fidelity.css").statusCode(),
                "/ui/fidelity.css also defaults to permit");
    }

    // --------------------------------------------------
    // B5 — Basic forced on the API and imports
    // --------------------------------------------------

    /**
     * B5 — Basic forced: {@code /api/*} and {@code /graphql} answer a 401 Basic challenge
     * (never an HTML redirect) without credentials; the imports require {@code fid-admin}
     * (named policy), so {@code pos} on {@code /products/import} gets 403, not 401.
     */
    @Test
    void b5_basicChallengeAndImportRolePolicy() {
        Response earn = RestAssured.given().redirects().follow(false)
                .contentType("application/json").body("{}").post("/api/earn");
        assertEquals(401, earn.statusCode(), "unauthenticated /api/earn must challenge, not redirect");
        assertNull(earn.getHeader("Location"), "a Basic challenge must not be an HTML redirect");
        Response gql = RestAssured.given().redirects().follow(false)
                .contentType("application/json").body("{\"query\":\"{__typename}\"}").post("/graphql");
        assertEquals(401, gql.statusCode(), "unauthenticated /graphql must challenge with 401");
        Response imp = RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD).post("/products/import");
        assertEquals(403, imp.statusCode(), "pos is authenticated but lacks fid-admin on the import policy");
    }

    // --------------------------------------------------
    // B6 — sealed roles across surfaces
    // --------------------------------------------------

    /**
     * B6 — sealed roles: crossing the account × surface matrix. {@code pos} on the admin UI
     * {@code /ui/cards} is refused (403), {@code admin} opens it (200); {@code admin} on
     * {@code /api/earn} is refused by {@code @RolesAllowed(pos)} (403) while {@code pos}
     * clears authentication/authorization there (never 401/403).
     */
    @Test
    void b6_rolesAreSealedAcrossSurfaces() {
        int posOnCards = RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD).get("/ui/cards").statusCode();
        assertEquals(403, posOnCards, "pos must be refused on the fid-admin UI");
        int adminOnCards = RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(ADMIN_USER, ADMIN_PASSWORD).get("/ui/cards").statusCode();
        assertEquals(200, adminOnCards, "admin must open the fid-admin UI");
        int adminOnEarn = RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(ADMIN_USER, ADMIN_PASSWORD)
                .contentType("application/json").body("{}").post("/api/earn").statusCode();
        assertEquals(403, adminOnEarn, "admin must be refused on the pos-only /api/earn");
        int posOnEarn = RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .contentType("application/json").body("{}").post("/api/earn").statusCode();
        assertNotEquals(401, posOnEarn, "pos must pass authentication on /api/earn");
        assertNotEquals(403, posOnEarn, "pos must pass authorization on /api/earn");
    }

    // --------------------------------------------------
    // B7 — a disabled account can still sign in (negative)
    // --------------------------------------------------

    /**
     * B7 — documentary negative: {@code quarkus-security-jpa} does not read the active flag,
     * so a disabled account still logs in successfully (same symptom as imvaluation B6). We
     * create a disabled user, prove the login lands on {@code /ui/cards}, then remove it.
     */
    @Test
    void b7_disabledAccountCanStillLogIn() {
        String user = "b7-disabled";
        createUser(user, "b7-secret1", AppUser.ROLE_POS, false, false, null);
        try {
            Response r = RestAssured.given().redirects().follow(false)
                    .formParam("j_username", user).formParam("j_password", "b7-secret1")
                    .post("/j_security_check");
            assertEquals(302, r.statusCode(), "a disabled account must still be authenticated");
            assertTrue(locationOf(r).contains("/ui/cards"), "the disabled login must reach the success landing");
            assertNotNull(r.getCookie(SESSION_COOKIE), "the disabled login must set a credential cookie");
        } finally {
            deleteUser(user);
        }
    }

    // --------------------------------------------------
    // B8 — mustChangePassword is inert (negative)
    // --------------------------------------------------

    /**
     * B8 — documentary negative: the {@code mustChangePassword} flag exists (the seeded admin
     * carries it) but no filter enforces it in imfid, so a flagged account navigates freely —
     * {@code /ui/cards} answers 200 for admin without any forced password-change redirect.
     */
    @Test
    void b8_mustChangePasswordIsInert() {
        boolean flagged = QuarkusTransaction.requiringNew()
                .call(() -> AppUser.findByUsername(ADMIN_USER).mustChangePassword);
        assertTrue(flagged, "the seeded admin must carry mustChangePassword=true for this negative to bite");
        String cookie = loginAndGetCookie(ADMIN_USER, ADMIN_PASSWORD);
        Response cards = RestAssured.given().redirects().follow(false)
                .cookie(SESSION_COOKIE, cookie).get("/ui/cards");
        assertEquals(200, cards.statusCode(), "a flagged account must navigate freely — the flag is inert");
    }

    // --------------------------------------------------
    // B9 — forgot-password request is neutral
    // --------------------------------------------------

    /**
     * B9 — forgot request: {@code POST /ui/forgot} always redirects 303 to
     * {@code /ui/forgot?sent=true} and shows the same neutral banner, whether the address is
     * known, unknown or empty (anti-enumeration). A known address stores exactly one hashed
     * token for the account; an unknown one stores nothing.
     */
    @Test
    void b9_forgotRequestIsNeutralAndStoresTokenOnlyForKnownAddress() {
        deleteTokensFor(ADMIN_USER);
        try {
            Response known = postForgot(ADMIN_EMAIL);
            assertEquals(303, known.statusCode(), "forgot must See-Other");
            assertTrue(locationOf(known).endsWith("/ui/forgot?sent=true"), "forgot must land on ?sent=true");
            assertEquals(1L, countTokensFor(ADMIN_USER), "a known address must store exactly one token");
            Response page = RestAssured.given().get("/ui/forgot?sent=true");
            assertTrue(collapse(page.asString()).contains("Si un compte correspond à cette adresse, un lien de "
                            + "réinitialisation vient de lui être envoyé. Il est valable 30 minutes et à usage unique."),
                    "the neutral banner literal must render");
            long before = totalTokens();
            Response unknown = postForgot("nobody@example.org");
            assertEquals(303, unknown.statusCode(), "an unknown address must answer identically");
            assertTrue(locationOf(unknown).endsWith("/ui/forgot?sent=true"), "unknown address must land on ?sent=true");
            assertEquals(before, totalTokens(), "an unknown address must store no token");
            Response empty = postForgot("");
            assertEquals(303, empty.statusCode(), "an empty address must answer identically");
            assertTrue(locationOf(empty).endsWith("/ui/forgot?sent=true"), "empty address must land on ?sent=true");
        } finally {
            deleteTokensFor(ADMIN_USER);
        }
    }

    // --------------------------------------------------
    // B10 — reset refusals, in order, then success
    // --------------------------------------------------

    /**
     * B10 — reset refusals in order: confirmation mismatch first
     * ({@code Les deux saisies ne correspondent pas.}), then an unknown token
     * ({@code Ce lien de réinitialisation est invalide, déjà utilisé ou expiré. Refaites une
     * demande.}), then the English-in-a-French-page policy messages
     * ({@code The password is mandatory.} / {@code The password must be at least 8 characters
     * long.}), and finally a valid reset landing 303 on {@code /ui/login?reset=true}.
     */
    @Test
    void b10_resetRefusalsInOrderThenSuccess() {
        String user = "b10-user";
        String raw = "b10rawtoken0000000000000000000000000000000000000000000000000000";
        createUser(user, "b10-initial1", AppUser.ROLE_FID_ADMIN, true, false, null);
        mintToken(user, raw, DateTimeProvider.now().plusMinutes(30));
        try {
            Response mismatch = postReset(raw, "abcdefgh", "different");
            assertRedirectContains(mismatch, "/ui/reset", "Les deux saisies ne correspondent pas.");
            Response unknown = postReset("no-such-token", "abcdefgh", "abcdefgh");
            assertRedirectContains(unknown, "/ui/reset",
                    "Ce lien de réinitialisation est invalide, déjà utilisé ou expiré. Refaites une demande.");
            Response mandatory = postReset(raw, "", "");
            assertRedirectContains(mandatory, "/ui/reset", "The password is mandatory.");
            Response tooShort = postReset(raw, "1234567", "1234567");
            assertRedirectContains(tooShort, "/ui/reset", "The password must be at least 8 characters long.");
            Response ok = postReset(raw, "brandnew1", "brandnew1");
            assertEquals(303, ok.statusCode(), "a valid reset must See-Other");
            assertTrue(locationOf(ok).endsWith("/ui/login?reset=true"), "a valid reset must land on /ui/login?reset=true");
            Response banner = RestAssured.given().get("/ui/login?reset=true");
            assertTrue(banner.asString().contains("Votre mot de passe a été modifié. Vous pouvez vous connecter."),
                    "the green success banner literal must render");
        } finally {
            deleteTokensFor(user);
            deleteUser(user);
        }
    }

    // --------------------------------------------------
    // B11 — token hygiene
    // --------------------------------------------------

    /**
     * B11 — token hygiene: the stored {@code token_hash} is 64 hex (SHA-256), never the raw;
     * a fresh request deletes the account's pending tokens (a single live link); the TTL is
     * driven by {@code imfid.reset.token-ttl-minutes} (30); consuming a token stamps
     * {@code used_at} and a second consumption is refused.
     */
    @Test
    void b11_tokenHygiene() {
        deleteTokensFor(ADMIN_USER);
        try {
            LocalDateTime before = DateTimeProvider.now();
            postForgot(ADMIN_EMAIL);
            LocalDateTime after = DateTimeProvider.now();
            String hash = QuarkusTransaction.requiringNew()
                    .call(() -> PasswordResetToken.<PasswordResetToken>find("user.username", ADMIN_USER).firstResult().tokenHash);
            assertTrue(hash.matches("[0-9a-f]{64}"), "the stored token hash must be 64 lowercase hex chars");
            LocalDateTime expiresAt = QuarkusTransaction.requiringNew()
                    .call(() -> PasswordResetToken.<PasswordResetToken>find("user.username", ADMIN_USER).firstResult().expiresAt);
            assertTrue(expiresAt.isAfter(before.plusMinutes(29)), "TTL must be about 30 minutes (lower bound)");
            assertTrue(expiresAt.isBefore(after.plusMinutes(31)), "TTL must be about 30 minutes (upper bound)");
            postForgot(ADMIN_EMAIL);
            assertEquals(1L, countTokensFor(ADMIN_USER), "a fresh request must leave a single live token");
        } finally {
            deleteTokensFor(ADMIN_USER);
        }
        String user = "b11-user";
        String raw = "b11rawtoken1111111111111111111111111111111111111111111111111111";
        createUser(user, "b11-initial1", AppUser.ROLE_FID_ADMIN, true, false, null);
        mintToken(user, raw, DateTimeProvider.now().plusMinutes(30));
        try {
            Response ok = postReset(raw, "freshpass1", "freshpass1");
            assertEquals(303, ok.statusCode(), "the first consumption must succeed");
            assertTrue(locationOf(ok).endsWith("/ui/login?reset=true"), "the first consumption must land on success");
            LocalDateTime usedAt = QuarkusTransaction.requiringNew()
                    .call(() -> PasswordResetToken.findByHash(sha256Hex(raw)).usedAt);
            assertNotNull(usedAt, "consuming a token must stamp used_at");
            Response replay = postReset(raw, "another12", "another12");
            assertRedirectContains(replay, "/ui/reset",
                    "Ce lien de réinitialisation est invalide, déjà utilisé ou expiré. Refaites une demande.");
        } finally {
            deleteTokensFor(user);
            deleteUser(user);
        }
    }

    // --------------------------------------------------
    // B12 — machine account has no self-service reset
    // --------------------------------------------------

    /**
     * B12 — machine account: {@code pos} has no e-mail, so it can never be a reset target —
     * a forgot request answers the same neutral message and stores nothing for it, freezing
     * the "machines without self-service" contract.
     */
    @Test
    void b12_machineAccountHasNoSelfServiceReset() {
        deleteTokensFor(POS_USER);
        Response empty = postForgot("");
        assertEquals(303, empty.statusCode(), "the request must answer neutrally");
        assertTrue(locationOf(empty).endsWith("/ui/forgot?sent=true"), "the request must land on ?sent=true");
        Response made = postForgot("pos@nowhere.local");
        assertEquals(303, made.statusCode(), "any address must answer identically");
        assertTrue(locationOf(made).endsWith("/ui/forgot?sent=true"), "the request must land on ?sent=true");
        assertEquals(0L, countTokensFor(POS_USER), "the e-mail-less machine account must never get a token");
    }

    // --------------------------------------------------
    // B13 — health probe is the degraded-mode discriminant
    // --------------------------------------------------

    /**
     * B13 — health probe: {@code GET /q/health} without authentication answers 200 with
     * {@code {"status":"UP",...}} — the discriminant impos uses to detect the degraded mode.
     * The "imfid stopped → connection refused" half lives on the impos side of the seam and
     * is not reproducible in-process.
     */
    @Test
    void b13_healthProbeIsPublicAndUp() {
        Response health = RestAssured.given().redirects().follow(false).get("/q/health");
        assertEquals(200, health.statusCode(), "/q/health must answer anonymously");
        assertEquals("UP", health.jsonPath().getString("status"), "a healthy imfid must report status UP");
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Collapses every run of whitespace to a single space and trims, so an assertion on a
     * literal screen text is robust to the source template wrapping it across lines while
     * still asserting the exact wording.
     *
     * @param html The raw HTML body.
     * @return The body with whitespace runs collapsed to single spaces.
     */
    private static String collapse(String html) {
        return html.replaceAll("\\s+", " ").trim();
    }

    /**
     * Reads the {@code Location} header of a response, defaulting to the empty string so the
     * caller's {@code contains}/{@code endsWith} checks never hit a null.
     *
     * @param response The HTTP response.
     * @return The Location header, or an empty string when absent.
     */
    private static String locationOf(Response response) {
        String location = response.getHeader("Location");
        return location == null ? "" : location;
    }

    /**
     * Logs in through the form endpoint and returns the resulting credential cookie.
     *
     * @param username The login name.
     * @param password The clear-text password.
     * @return The value of the {@code quarkus-credential} cookie.
     */
    private static String loginAndGetCookie(String username, String password) {
        Response r = RestAssured.given().redirects().follow(false)
                .formParam("j_username", username).formParam("j_password", password)
                .post("/j_security_check");
        String cookie = r.getCookie(SESSION_COOKIE);
        assertNotNull(cookie, "login must set the credential cookie");
        return cookie;
    }

    /**
     * Posts a forgot-password request for an address, without following the redirect.
     *
     * @param email The address as typed on the public form.
     * @return The redirect response.
     */
    private static Response postForgot(String email) {
        return RestAssured.given().redirects().follow(false).formParam("email", email).post("/ui/forgot");
    }

    /**
     * Posts a password-reset submission, without following the redirect.
     *
     * @param token    The raw reset token.
     * @param password The new password.
     * @param confirm  The confirmation of the new password.
     * @return The redirect response.
     */
    private static Response postReset(String token, String password, String confirm) {
        return RestAssured.given().redirects().follow(false)
                .formParam("token", token).formParam("password", password).formParam("confirm", confirm)
                .post("/ui/reset");
    }

    /**
     * Asserts a reset submission redirected (303) to a path carrying, once URL-decoded, the
     * expected literal error message.
     *
     * @param response The redirect response.
     * @param path     The path fragment the Location must contain (e.g. {@code /ui/reset}).
     * @param message  The literal error message the decoded Location must contain.
     */
    private static void assertRedirectContains(Response response, String path, String message) {
        assertEquals(303, response.statusCode(), "a refused reset must See-Other");
        String decoded = URLDecoder.decode(locationOf(response), StandardCharsets.UTF_8);
        assertTrue(decoded.contains(path), "the redirect must return to " + path + " but was " + decoded);
        assertTrue(decoded.contains(message), "the redirect must carry the message '" + message + "' but was " + decoded);
    }

    /**
     * Creates and persists an application user in a fresh transaction.
     *
     * @param username           The login name.
     * @param password           The clear-text password (bcrypt-hashed on store).
     * @param roles              The comma-separated roles.
     * @param active             Whether the account may sign in.
     * @param mustChangePassword Whether the change-password flag is set.
     * @param email              The e-mail address, or null for a machine account.
     */
    private static void createUser(String username, String password, String roles, boolean active,
                                   boolean mustChangePassword, String email) {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = new AppUser();
            user.username = username;
            user.setPassword(password);
            user.roles = roles;
            user.active = active;
            user.mustChangePassword = mustChangePassword;
            user.email = email;
            user.displayName = username;
            user.persist();
        });
    }

    /**
     * Deletes a user by login name, and any of its reset tokens first, in a fresh transaction.
     *
     * @param username The login name to remove.
     */
    private static void deleteUser(String username) {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = AppUser.findByUsername(username);
            if (user != null) {
                PasswordResetToken.delete("user", user);
                user.delete();
            }
        });
    }

    /**
     * Mints a valid reset token for a user by storing the SHA-256 hash of the raw value, so a
     * test can drive the reset flow without scraping the mailed link.
     *
     * @param username  The account the token resets.
     * @param rawToken  The raw token the test will submit.
     * @param expiresAt The expiry instant of the token.
     */
    private static void mintToken(String username, String rawToken, LocalDateTime expiresAt) {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = AppUser.findByUsername(username);
            PasswordResetToken token = new PasswordResetToken();
            token.user = user;
            token.tokenHash = sha256Hex(rawToken);
            token.expiresAt = expiresAt;
            token.persist();
        });
    }

    /**
     * Deletes every reset token of a user by login name, in a fresh transaction.
     *
     * @param username The account whose tokens are removed.
     */
    private static void deleteTokensFor(String username) {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = AppUser.findByUsername(username);
            if (user != null) {
                PasswordResetToken.delete("user", user);
            }
        });
    }

    /**
     * Counts the reset tokens attached to a user by login name, in a fresh transaction.
     *
     * @param username The account whose tokens are counted.
     * @return The number of tokens.
     */
    private static long countTokensFor(String username) {
        return QuarkusTransaction.requiringNew().call(() -> PasswordResetToken.count("user.username", username));
    }

    /**
     * Counts all reset tokens in the database, in a fresh transaction.
     *
     * @return The total number of tokens.
     */
    private static long totalTokens() {
        return QuarkusTransaction.requiringNew().call(() -> PasswordResetToken.count());
    }

    /**
     * Hashes a raw token with SHA-256, hex-encoded lowercase — the exact scheme
     * {@code PasswordResetService} stores, so a minted token matches the production lookup.
     *
     * @param value The raw token.
     * @return The hex-encoded SHA-256 hash.
     */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
