# E2E group K — Imports CSV & écran Imports

**Class:** `src/test/java/com/intermarche/fidelity/e2e/GroupKIT.java`
**Command:** `mvn -q verify -DskipUTs=true -Dit.test=GroupKIT -DskipITs=false`
**Result:** BUILD SUCCESS — `Tests run: 12, Failures: 0, Errors: 0, Skipped: 1` (the single skip is the `[P]` scenario K6, `@Disabled`).

## Scenarios covered

| Id | Tier | What it proves |
|----|------|----------------|
| K1 | `@QuarkusTest` + RestAssured | Raw `text/plain` pipe POST, skipped header, 200 JSON report `{createdCount,updatedCount}`; a faulty row (unknown card) drives the staged fallback — WARN `Failed to process chunk of size 4 with step 1000. Retrying with step 100` + `Import finished. Created: 3, Updated: 0`, healthy rows pass, faulty one isolated into `errors`. |
| K2 | RestAssured | Checksum idempotence: create → 1 created; identical replay → 0 updated and `updated_at` untouched in base; one changed field (label) → 1 updated, new value persisted. |
| K3 | RestAssured | Replaying the seed pack `01→09` (the very classpath files the DataInitializer loaded) raises no row error; a membership before its card errors `Card '<n>' not found.`, then passes once the account is imported (resource-level catch-up). |
| K4 | `[W]` Playwright | Drag & drop preselection: `is-dragover` highlight on dragover, file landed, `04-rules.csv → FIDELITY_RULES`; the graven order trap `02-product-families.csv → PRODUCT_FAMILIES` (`famil` before `product`); unknown name leaves the domain unchanged; classic `setInputFiles('05-accounts.csv') → FIDELITY_ACCOUNTS`. |
| K5 | RestAssured | Visit import writes header-only `NO_MOVEMENT` traces idempotent by ticketRef — status + fiscal date asserted, replay doubles no visit. |
| K6 | `[P]` `@Disabled` | Justified residue (see below). |
| K7 | RestAssured | The report is valid, escaped JSON: rows keyed by a `"`-bearing and a tab-bearing reference produce `errors` entries that parse; raw body carries `\"`. Asserted **by parsing** (assumed divergence with imvaluation's D3). |
| K8 | RestAssured | The two rule paths diverge: the import validates (`missing rule type`, `unknown rule type '<t>'`, `specification invalid for type '<t>':`, `missing or malformed validFrom (ISO date-time)`); direct persistence does **not** — a deformed unknown-type rule exists in base (engine degrades it, C13). Proven side by side. |
| K9 | RestAssured | Adjustment importer writes the ledger: 1 movement + balance refreshed by the amount; replay on the same reference credits nothing more (I8); mandatory-field + unknown-card refusals with their literals. |
| K10 | RestAssured | Account importer as card generator/chain builder: explicit card upsert (status change → 1 updated); blank card → generated 13-digit EAN-13 (prefix `29`, valid check digit); `transferredToCard` builds the transfer chain from the import alone (E8 on a 100% CSV world). |
| K11 | RestAssured | Membership/activation dependency guards (`Card`/`Community`/`validFrom`/`ruleCode`/`periodStart` literals) and resource-level catch-up (membership before its card errors, then passes). |
| K12 | RestAssured | Family import is a complete-state replacement: an amputated EAN list removes the absent links **in base** (E4 counter-proof, verified by reading the real state); `Product EAN`, `SubFamily code`, self-reference `Family '<c>' cannot contain itself.` refusals. F_L restored in `finally`. |

## Files read

- `e2escenarios-imfid.md` — group K catalog (K1–K12) + inventory Q-D0/Q-D.
- Import stack: `imports/ImporterCsvResource.java` (base machinery: report, `escapeJson`, staged fallback, log lines), the nine domain resources (`FidelityRule/Adjustment/Account/Membership/Activation/Visit/CommunityCsvResource`, `ProductFamilyCsvResource`, `ProductCsvResource`), `ui/ImportUiResource.java`.
- `templates/ImportUiResource/imports.html` + `META-INF/resources/ui/fidelity.js` (drag & drop: `.import-drop`, `is-dragover`, ordered `famil`→`product` patterns).
- Domain: `BaseEntity` (`updated_at`, checksum), `FidelityRule`, `FidelityMovement`, `EarnTrace`, `FidelityAccount`, `ProductFamily`, `FidelityActivation`; seed CSVs `seed/01..09`.
- `security/SecurityBootstrap.java` + `application.properties` (imports secured `fid-admin`, Basic).
- Patterns: `e2e/GroupJIT.java` (`[W]` Playwright, isolated `ZZZ_*` + purge), `e2e/BootLogCapture.java` (log-handler pattern, adapted to a per-scenario runtime handler).

## Iterations

1. Read the whole import stack, seed CSVs, drag & drop JS and Q-D0 literals; enumerated every attendu before writing.
2. Wrote `GroupKIT` (K1–K12) and ran the campaign once → **green on the first run** (12 run, 0 failures, K6 skipped). A second confirming run reproduced BUILD SUCCESS.

## Hard points

- **Runtime log capture (K1).** `BootLogCapture` only records at boot; the import log lines are emitted at request time. Added a per-scenario `java.util.logging.Handler` on the root logger (`@BeforeEach`/`@AfterEach`), reading `ExtLogRecord.getFormattedMessage()` like `BootLogCapture`.
- **Simulating an HTML5 file drop (K4).** `setInputFiles` fires only `change`, not `drop`, so it can't exercise the highlight or the drop path. Drove a `page.evaluate` that builds a `DataTransfer` with a `File` and dispatches `dragover`/`drop`, returning `over|domain|files` per drop; the classic path uses `setInputFiles` for the `change` branch.
- **Ledger side effects.** K1 uses `amount 0.00` to stay balance-neutral; K9 captures the balance, asserts the `+10.00` delta, then deletes the movement and restores the balance in `finally`.
- **Fiscal idempotence.** Every reference/ticketRef carries a `K<n>-…` per-scenario suffix so replays are the intended idempotent no-ops, never a silently absorbed collision with a seeded key.
- **Observed importer quirk (not worked around — `src/main` untouched).** Replaying `02-product-families.csv` makes the multi-row stages fail with `Detached entity passed to persist: Product` (products pre-fetched outside the row transaction), which the staged fallback absorbs by dropping to the 1-by-1 path where each family re-links freshly-managed products and commits — so the replay ends with **no row error**, which is what K3 asserts. Reported here as a one-line observation, per the scope rule.

## Justified residue ([P] @Disabled)

- **K6 `k6_limitsOfTheCsvWorld`** — `@Disabled`: the CSV qualification pack cannot express the narrated ledger (ADJUSTMENT-only movements) nor an active burn reservation (card `…088` showing available = balance); assumed divergences with the richer DataInitializer world, not to be asserted in a prod-like harness. Kept as a documented residue until a prod-like harness exists.
