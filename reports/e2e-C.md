# E2E group C — `POST /api/earn`, the projection

Class: `src/test/java/com/intermarche/fidelity/e2e/GroupCIT.java`
Command: `mvn -q verify -DskipUTs=true -Dit.test=GroupCIT -DskipITs=false`
Result: **Tests run: 17, Failures: 0, Errors: 0, Skipped: 0** — green on the first campaign run.

## Scenarios covered (17/17, all RestAssured tier — none [W]/[P]/[D])

| Id | What it proves |
|----|----------------|
| C1 | Pure read: a fully earning `/earn` leaves accounts, movements and earn-traces byte-for-byte unchanged (contrast with the F6 credit). |
| C2 | 400 `{"error":"Missing valuation response in /earn request"}` on a body with no `valuationResponse`. |
| C3 | 422 on both reconciliation invariants — `Incoherent totals: totalPrice …` and `Offer '<type>': Σ items …` — plus the WARN log `Rejected /earn: reconciliation failed: <msg>` (captured via `BootLogCapture`, which records all runtime records). Calibrated tails frozen through stable prefixes + closed tokens. |
| C4 | Absent / blank / `9999999999999` card → 200 empty earn, never 404, never an auto-created account. |
| C5 | Dated evaluation: FL_WEEKEND on Sat vs Mon; SOCLE_4_MARQUES before 2026-05-18 vs SOCLE_5_MARQUES on/after (versioned windows); missing `createdAt` → program-clock fallback (SOCLE_5 era). |
| C6 | UNKNOWN_EAN literal §25.4 warning; both halves — line out of every assiette (no entry) yet inside `burnableBase`. |
| C7 | I2 non-cumul: coffee+biscuits absorbed by `PROMO_COFFEE_PACK` → earn 0, no warning; the socle reference cart earns ≈5 %. |
| C8 | Socle assiette in units: 2 items < 3 → 0; a UNIT quantity of 2 + 1 = 3 → earns. |
| C9 | 4th-visit boost 5 %→10 % on the call itself (visits aged deterministically); a no-visit card stays at 5 %. |
| C10 | Three cap floors: COMMUNITY:STUDENTS truncation traced with `capAmount 20.00`; every cap scope inside the closed `RULE:`/`COMMUNITY:`/`GLOBAL` nomenclature. |
| C11 | Once-per-month: 40 % hygiene-Labell fires, then after a real `ticket-closed` ingestion is silent (`hasEarnedThisPeriod`). |
| C12 | Activations gate e-coupon (nothing without / earns with) and the challenge differential gain (4.50 € + 0.60 € crosses 5 € → +1.00 €). |
| C13 | Degraded specs tolerated: `activeDays:["MONDAY",null,""]` (no phantom day from `""`), no 500. |
| C14 | Descending priority + exclusivity: only the higher rule earns; swapping priorities flips the earn. |
| C15 | Non-exclusive higher rule shares its assiette — both rules credit the same base (voluntary cumul). |
| C16 | Entries contract: stable `ruleCode`, printable `label`, post-cap `amount`, `baseAmount`, `lineIds` = exactly the assiette lines, never empty. |
| C17 | CGU program exclusion seeds the consumed set: gift-card line out of every assiette and out of `burnableBase`; `CGU_EXCLUSION` never an entry. |

## Files read

- `e2escenarios-imfid.md` (§C + inventory Q-A/Q-B/Q-F), `annexe-b-oracle.md`, `CLAUDE.md`.
- `docs/valuation-request.json`, `docs/valuation-response.json` (payload shape + amounts).
- `earn/`: `EarnResource`, `EarnRequest/Response/Result`, `ValuationReader`, `ValuationRequest/Response`, `AmountEvaluation`, `EarnEngine`, `CardContextBuilder`.
- `rule/`: `AbstractEarnRuleApplier`, `ValuedLine`, `CardContext`, `EarnEntry`, all seven `appliers/*Factory`.
- `seed/DataInitializer`; `domain/`: `FidelityRule`, `FidelityMovement`, `FidelityAccount`, `EarnTrace`, `EarnTraceLine`, `MovementType`.
- `ingestion/`: `EventsResource`, `EventDtos`, `IngestionService`; test peers `GroupAIT`, `GroupBIT`, `BootLogCapture`.

## Iterations

One. The class compiled and all 17 scenarios passed on the first `mvn verify` run — the payloads and expected amounts were derived directly from the engine semantics and the annexe-B oracle before writing.

## Hard points

- **Every crafted couple must reconcile (§22.1).** Solved by a builder that emits offers-only baskets (one Standard offer per line, `amount` = its single item, total = Σ offers), so the reconciliation holds by construction; C3 deliberately breaks it.
- **Calendar flakiness (§24.4, 1st–6th).** C9 and C12 age the relevant `EarnTrace` rows via `QuarkusTransaction` (out of month, then to fixed in-month days) rather than trusting the run date; dated evals go through `createdAt`.
- **Distinct cap-floor scope (C10).** The rule cap and community cap are both 20 € on `COMMUNITY_STUDENTS`, so a plain overflow only traces `RULE:`. A controlled `STUDENTS_HYGIENE_28` earn is posted this month so the community cumulative exceeds the rule cumulative and the `COMMUNITY:STUDENTS` floor bites distinctly (truncatedBy = 5.00); cumulatives are read from the DB so the assertion is deterministic regardless of the seeded relative earn, and the row is removed in `finally`.
- **Socle vs Students brand overlap.** The 5 socle brands equal the Students scope, so a single sub-threshold Labell line (1 item < 3) keeps the socle silent (it does not consume below threshold) and lets `COMMUNITY_STUDENTS` earn.
- **Fiscal idempotence (C11).** The ingested `ticketRef` is a per-test unique literal; the trace and movements are removed in `finally`.
- **Shared single-boot DB.** C13/C14/C15 persist custom rules on brands no seeded rule targets (Tefal, Staub) and delete them in `finally`; no test relies on execution order.

## Justified residue

None. Group C has no `[P]` or `[W]` scenarios — all 17 run and pass at the RestAssured tier.
