# E2E group G — Batchs (§16) — report

Class: `src/test/java/com/intermarche/fidelity/e2e/GroupGIT.java`
Command: `mvn -q verify -DskipUTs=true -Dit.test=GroupGIT -DskipITs=false`
Result: **Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS.**

## Scenarios covered

| Id | Tier | Method | What it proves |
|----|------|--------|----------------|
| G1 | [D] RestAssured | `g1_expiryDryRunReportsThenExecutionWritesBoundedByAvailable` | Clock frozen at 1 March 2026 → expireYear 2025. Dry-run reports the figures with **no** write (no EXPIRY movement, no `BatchRunLog`, balance untouched). Execution posts a negative EXPIRY consumed FIFO by earnYear (the 2026 credit is preserved), **bounded by the available balance** so the 15 € held under the active lease is never expired (min(residual 30, available 25) = 25); records exactly one `BatchRunLog`. |
| G2 | [D] RestAssured | `g2_purgeDebitsPositiveAndResiliatesReportingZeroForNegative` | Two cards antidated past the 24-month cut-off (`lastUsedAt` 2023). Dry-run reports without writing. Execution debits the positive 18 € to zero + RESILIATED; the negative −5 € card is RESILIATED **with no movement** and a **zero** line (I7); total moved = 18 €; one `BatchRunLog`. |
| G3 | [D] RestAssured | `g3_activationVoidHistoricalProofOnSeededCard` | Seeded historical proof on `…095`: RESILIATED, balance 0, a −2.40 € ACTIVATION_VOID movement, and its ACTIVATION_VOID `BatchRunLog` (1 account, 2.40 €). |
| G4 | **[W] Playwright** | `g4_programmeScreenLastRunsConfirmDialogAndUnknownNotice` | Real headless Chromium on `/ui/program`: last run of each batch shown (EXPIRY + ACTIVATION_VOID ran, PURGE `Jamais exécuté`); the Exécuter button raises the literal dialog `Exécuter le batch EXPIRY ? Cette opération détruit des avantages de façon définitive.` (dismissed → no write); an unknown type yields the literal `Unknown batch 'NOPE'` notice (via form-session POST). |
| G5 | [D] reflection | `g5_schedulerCronExpressionsAreTheThreeLiterals` | Configuration test: the three `@Scheduled` crons are exactly `0 0 3 1 3 ?` (expiry), `0 0 4 * * ?` (purge), `0 0 5 * * ?` (activation void). |
| G6 | unmarked RestAssured | `g6_machineApiDefaultsToDryRunGuardsRoleAndRejectsUnknownType` | `POST /api/batches/{type}`: `dryRun` **defaults to true** (forgotten param simulates, no write); `pos` → 403; unknown type → 400 `{"error":"Unknown batch type 'NOPE'"}`; body is the `BatchResult` JSON (batch, dryRun, accountsAffected, totalAmount, lines). |

No `[P]` scenarios in group G → no `@Disabled` residue.

## Files read

- `e2escenarios-imfid.md` — §G (G1–G6) and the Q inventory (Q-A batch 400, Q-D `Unknown batch '<t>'`, Q-E confirms).
- `batch/`: `BatchTriggerResource`, `BatchService`, `BatchType`, `BatchResult`, `BatchScheduler`.
- `domain/`: `BatchRunLog`, `FidelityAccount`, `FidelityMovement`, `FidelityReservation`, `MovementType`, `AccountStatus`, `ReservationState`, `BaseEntity`, `util/DateTimeProvider`, `util/ProgramClock`.
- `account/LedgerService`; `ui/ProgramUiResource`, `ui/ProgramView`, `templates/ProgramUiResource/program.html`; `templates/AuthUiResource/login.html`.
- `seed/DataInitializer` (seeded roster + batch history), `application.properties` (form/basic auth), `SecurityBootstrap` (admin/pos creds).
- `GroupFIT`, `GroupBIT`, `GroupCIT` for the established style; `pom.xml` (quarkus-playwright 2.3.8).

## Iterations

1. Non-[W] subset (G1/G2/G3/G5/G6) → **5/5 green** first run.
2. Full class with G4 [W] → Playwright/Chromium booted fine; only the unknown-type step failed: following the 303 dropped the session cookie and landed on `/ui/login`.
3. Read the notice from the redirect `Location` (`notice=` query param, URL-decoded) instead of following → **6/6 green, BUILD SUCCESS.**

## Hard points

- **Isolation of destructive batches.** The batch engine runs over *all* accounts, so an execution could corrupt the seeded world for sibling tests. Kept every mutation on inserted test cards (out-of-range prefix `999…`) removed in a `finally`, and chose parameters so **no seeded account ever qualifies**: G1 freezes at 1 March 2026 (expireYear 2025 — every seeded residual in years ≤ 2025 is 0), G2 antidates its own cards to 2023 while every seeded `coalesce(lastUsedAt, createdAt)` is 2026.
- **`DateTimeProvider` is a static global.** G1 inserts its card *before* freezing (so `createdAt` is real, never a purge candidate), freezes only around the batch call, and clears the clock in `finally`. First group to freeze the clock in the e2e suite.
- **`BatchRunLog` cleanup asymmetry.** The seed already records an EXPIRY run (02:00) and an ACTIVATION_VOID run — so G1 deletes only the run it wrote, by its exact `runAt` (03:00); G2 may delete all PURGE runs because the seed records none.
- **First Playwright [W] test in the repo.** `@WithPlaywright` + `@InjectPlaywright BrowserContext`, base URL via `@TestHTTPResource`, login through the real form, and a dismissed `onDialog` handler to read the confirm literal without submitting. Headless Chromium provisioned and ran under `mvn verify`.
