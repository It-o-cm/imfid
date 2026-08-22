# E2E calibration — Group B (authentication & forgot-password)

Calibration run of the imfid e2e campaign harness on group B. Class
`src/test/java/com/intermarche/fidelity/e2e/GroupBIT.java`, RestAssured tier
(no `[W]`/`[P]` scenario in group B). Not committed — this is the calibration
that tunes the harness before the automated rafale.

## Result

`mvn -q verify -DskipUTs=true -Dit.test=GroupBIT -DskipITs=false` → **exit 0**,
**Tests run: 13, Failures: 0, Errors: 0, Skipped: 0** (≈3.3 s of tests over a
3.1 s boot). One `@Test` per scenario B1–B13, scenario id in the method name and
its Javadoc.

| Scenario | Method | What it pins |
|---|---|---|
| B1 | `b1_nominalLoginChainLandsOnCards` | `GET /` 303→`/ui/cards`; anon `/ui/cards` 302→`/ui/login`; `POST /j_security_check` 302→**`/ui/cards`** (corrected landing) + `quarkus-credential` cookie opens `/ui/cards` 200 |
| B2 | `b2_refusedLoginShowsBannerForAnyErrorParam` | bad creds 302→`/ui/login?error=true`; banner `Identifiants invalides.` shows for **any** presence of `error` (even `error=false`), absent without it |
| B3 | `b3_logoutClearsCookieWithoutNotice` | `POST /ui/logout` 303→bare `/ui/login`, cookie cleared (`Max-Age=0`, `Path=/`), **no** signed-out notice |
| B4 | `b4_publicPathsAndDefaultPermitHole` | permit list 200; **canary** `GET /ui/fidelity.js` anon → **200** (plus `auth.css`/`fidelity.css`): the default-permit hole |
| B5 | `b5_basicChallengeAndImportRolePolicy` | `/api/earn` & `/graphql` unauth → 401 (no HTML redirect); `pos` on `/products/import` → 403 (named `fid-admin` policy) |
| B6 | `b6_rolesAreSealedAcrossSurfaces` | matrix: `pos`→`/ui/cards` 403, `admin`→`/ui/cards` 200, `admin`→`/api/earn` 403, `pos`→`/api/earn` passes auth (≠401/403) |
| B7 | `b7_disabledAccountCanStillLogIn` | documentary negative: `active=false` account still authenticates (quarkus-security-jpa ignores the flag) |
| B8 | `b8_mustChangePasswordIsInert` | documentary negative: seeded admin carries `mustChangePassword=true` yet navigates `/ui/cards` freely (no enforcing filter) |
| B9 | `b9_forgotRequestIsNeutralAndStoresTokenOnlyForKnownAddress` | `POST /ui/forgot` 303→`?sent=true`, neutral banner literal; known address stores exactly one token, unknown/empty store none |
| B10 | `b10_resetRefusalsInOrderThenSuccess` | refusal order: mismatch → unknown-token → `The password is mandatory.` → `The password must be at least 8 characters long.` → success 303→`/ui/login?reset=true` |
| B11 | `b11_tokenHygiene` | `token_hash` = 64 hex (SHA-256), TTL≈30 min, fresh request leaves a single live token, consume stamps `used_at`, replay refused |
| B12 | `b12_machineAccountHasNoSelfServiceReset` | `pos` (no e-mail) never becomes a reset target: neutral answer, zero tokens |
| B13 | `b13_healthProbeIsPublicAndUp` | `GET /q/health` anon → 200 `{"status":"UP"}` (the down half lives on the impos side of the seam) |

Residue: none. Group B has no `[P]` (prod-like) or `[W]` (browser) scenario, so
nothing is `@Disabled`.

## Iterations (3 campaign runs)

1. **12/13** — B9 failed: `the neutral banner literal must render`. The
   `forgot.html` template wraps the sentence across two source lines
   (`…lui être\n        envoyé…`); the raw HTML body carries the newline +
   indentation, so an exact-substring match on the single-spaced literal missed.
   Fixed by collapsing whitespace runs before the match (still asserts the exact
   wording).
2. **12/13** — B9 then errored: `totalTokens` used the method reference
   `PasswordResetToken::count`, which binds to the **un-enhanced**
   `PanacheEntityBase.count()` and throws *"This method is normally automatically
   overridden in subclasses"*. Fixed by a direct call inside the lambda
   (`() -> PasswordResetToken.count()`) — enhancement only rewrites direct call
   sites.
3. **13/13 green.**

## Traps encountered (harness-level, reusable)

- **Template line-wrapping vs literal asserts.** Screen texts the catalog quotes
  are wrapped across source lines in Qute; the raw HTML is *not* whitespace-
  collapsed (only a browser collapses it). Exact `contains` on a single-spaced
  literal fails. Normalize (`\s+`→space) before matching.
- **Panache statics via method reference.** Under `@QuarkusTest` the entity *is*
  enhanced, but only at direct call sites. A method reference (`Entity::count`)
  captures the base implementation and blows up at runtime. Always call the
  static directly inside the transaction lambda.
- **Query-encoded redirect messages.** `redirectToReset` builds
  `/ui/reset?token=…&error=<message>` with the message URL-encoded (accents,
  spaces). The `Location` header must be URL-decoded before asserting the French
  literal.
- **Dual mechanism (basic + form).** Both are enabled: anonymous `/ui/*` →
  302 to `/ui/login` (form), while `/api/*` and `/graphql` (path
  `auth-mechanism=basic`) → 401. A *present* `Authorization: Basic` header
  authenticates even on `/ui/*`, which is what turns a missing role into a clean
  403 — used to drive the B6 role matrix without a session.
- **Seeded-state poisoning across one JVM boot.** A successful reset rewrites the
  target's password and clears `mustChangePassword`; the DataInitializer only
  rebuilds at boot, not between tests. Consuming resets on the seeded `admin`
  would break later `admin` logins in the same class. Mitigation: create
  throwaway users with unique literal names, mint their tokens directly
  (`sha256Hex(raw)` + `DateTimeProvider`-based expiry, no link scraping), and
  delete user+tokens in a `finally`.
- **Idempotence of the forgot flow.** Re-requesting deletes pending tokens, so
  token counters must be scoped per test (clear the target's tokens at start and
  in `finally`), never asserted as absolute totals.

## Proposed contract additions (NOT applied)

Lines I would add to the CLAUDE.md *"E2E scenario tests"* section to harden the
rafale, kept out of the file until you approve them:

- **Screen-text asserts normalize whitespace.** Qute templates wrap quoted
  sentences across source lines; assert against the HTML body with whitespace
  runs collapsed to a single space — never a raw exact `contains`, which the
  line-wrap defeats.
- **Panache statics only at direct call sites.** Inside `QuarkusTransaction`
  lambdas call `Entity.count(...)`/`Entity.find(...)` directly; never a method
  reference (`Entity::count`) — it binds to the un-enhanced `PanacheEntityBase`
  and throws at runtime.
- **URL-decode redirect Locations before matching.** `/ui/reset` (and any
  redirect that carries a message in the query) encodes the literal; decode the
  `Location` header before asserting the French/English text.
- **Auth mechanism by surface.** Anonymous `/ui/*` → 302 to `/ui/login`;
  `/api/*` and `/graphql` → 401 Basic challenge (never an HTML redirect); a
  present Basic header authenticates on any surface, so a missing role reads as
  403 — the lever for role-matrix cells without a form session.
- **Throwaway users for state-mutating flows.** Any scenario that consumes a
  reset token, disables an account, or otherwise rewrites seeded rows must act on
  a uniquely-named user created for the test and clean it up in a `finally` — the
  seeded world is rebuilt only at boot, so mutations leak across tests in one JVM.
- **Mint reset tokens, don't scrape the mail.** Store `sha256Hex(rawToken)` with
  a `DateTimeProvider`-based expiry to drive the reset flow deterministically,
  instead of parsing the mocked mail log for the link.
- **Scope token/counter assertions per test.** The forgot flow is an idempotent
  upsert that deletes pending tokens; clear the target's tokens before and after,
  and assert relative counts, never absolute totals.
