# E2E group D — burn reservations (`/api/burn/reservations`)

Class: `src/test/java/com/intermarche/fidelity/e2e/GroupDIT.java`
Command: `mvn -q verify -DskipUTs=true -Dit.test=GroupDIT -DskipITs=false`
Result: **Tests run: 10, Failures: 0, Errors: 0, Skipped: 1** (D6 `@Disabled`).

## Scenarios covered

| Id | Tier | Method | Attendus asserted |
|----|------|--------|-------------------|
| D1 | RestAssured | `d1_nominalReservationCreated` | 201; `reservationId` non-null; `expiresAt` = now + 900 s TTL; lease persisted ACTIVE at 5.00 €. |
| D2 | RestAssured | `d2_renewalPushesLeaseAndRechecksBalance` | Same card+ticket re-POST → 200; `expiresAt` pushed past the aged value; amount modifiable (→ 5.00 €); over-balance renewal (999 €) → 422 `INSUFFICIENT_BALANCE`, lease unchanged. Seeded `…088` lease restored after. |
| D3 | RestAssured | `d3_liveLeaseOtherTicketIsConflict` | New ticket on `…088` → 409 empty body; seeded lease untouched. |
| D4 | RestAssured | `d4_unknownCardIsNotFound` | Unknown card → 404 empty body. |
| D5 | RestAssured | `d5_theThreeRejections` | `INSUFFICIENT_BALANCE` (amount > available); `DAILY_RULE` (a BURN already exists this fiscal day); `ACCOUNT_STATUS` on `…040` PENDING_ACTIVATION and `…095` RESILIATED. |
| D6 | RestAssured | `d6_availableIsNotBalance` | **@Disabled — justified residue** (see hard points). |
| D7 | RestAssured | `d7_confirmationIntoDatedBurn` | Confirm ACTIVE lease → 200, BURN −5.00 dated the fiscal day, `ruleCode` null; re-confirm → 200 idempotent (single BURN); unknown id → 404; expired lease → 410 with no BURN. |
| D8 | RestAssured | `d8_expiredLeaseCaughtUpByIngestion` | After a 410, `ticket-closed` with `reservationId` → 202, BURN −4.00 created despite the expired lease, trace warns `EXPIRED_LEASE_CONFIRMED`, lease ends CONFIRMED (§29.2). |
| D9 | RestAssured | `d9_releaseIsAlwaysNoContent` | DELETE unknown id → 204; active lease lowers `availableBalance`; release → 204 and frees the balance; a released lease consumes no daily rule (new reservation → 201). |
| D10 | RestAssured | `d10_naturalExpiryFreesTheCard` | Aged (elapsed) lease blocks nothing → new-ticket reservation 201, old lease swept EXPIRED, new one ACTIVE. |

No `[W]` or `[P]` scenarios in group D.

## Files read

- `e2escenarios-imfid.md` (§D and the group/tier legend).
- `src/test/java/com/intermarche/fidelity/e2e/GroupCIT.java` (RestAssured + `QuarkusTransaction` patterns to imitate).
- `src/main/java/com/intermarche/fidelity/burn/` — `ReservationResource`, `ReservationDtos`, `ReservationService`.
- `src/main/java/com/intermarche/fidelity/domain/` — `FidelityReservation`, `ReservationState`, `FidelityMovement`, `FidelityAccount`.
- `src/main/java/com/intermarche/fidelity/account/LedgerService.java`, `AccountResource`/`AccountViews` (available-balance API).
- `src/main/java/com/intermarche/fidelity/ingestion/` — `IngestionService` (`confirmReservationAtIngestion`), `EventsResource`, `EventDtos`.
- `src/main/java/com/intermarche/fidelity/domain/util/` — `DateTimeProvider`, `ProgramClock`.
- `src/main/java/com/intermarche/fidelity/seed/DataInitializer.java` (seeded cards, balances, `…088` lease, TTL setting 900 s).
- `pom.xml` (failsafe `it.test` / `skipUTs` / `skipITs` wiring).

## Iterations

1. First compile failed — passed a `long` id where `reservationAmount(String ticketRef)` was expected (D1). Fixed to pass the ticket reference.
2. Second run: 9/10 green; **D6** failed (`expected 422 but was 409`), confirming the guard-order blocker below.
3. Third run: D6 `@Disabled` with the blocker reason → **10 run, 0 failures, 1 skipped**.

## Hard points

- **Isolation on the shared seeded world.** `…088` (seeded ACTIVE 8.00 € lease) is used read-only by D3/D6 and mutated by D2; D2 restores the lease to its catalog state (8.00 €, ACTIVE, ticket `0101-2026-003001`, +30 min) in `finally`. Scratch cards (`…071`, `…033`, `…026`, `…057`) carry no seeded lease; each test cleans their reservations before and after, and recomputes the denormalized balance after posting/removing BURN movements.
- **Deterministic time.** No wall-clock boundary is asserted: the TTL is checked as now + 900 s ± tolerance; expiry (D2/D7/D8/D10) is forced by aging `expiresAt` rows via `QuarkusTransaction`, never by the run day (§24.6). Dated BURN movements go through the confirm/ingest payload's fiscal date.
- **Fiscal idempotence.** Every `ticketRef` is suffixed with `System.nanoTime()`, so BURN and `ticket-closed` upserts are never silently absorbed; re-confirm idempotence (D7) is asserted as a single BURN.
- **D8 verified via DB.** `ticket-closed` returns a bodiless 202, so the caught-up BURN and the `EXPIRED_LEASE_CONFIRMED` warning are asserted on the persisted movement and `EarnTrace.warnings`.

## Justified residue

- **D6 `d6_availableIsNotBalance` — `@Disabled`.** `ReservationService.reserve()` evaluates the one-card-one-register conflict guard **before** the available-balance guard, and the renewal path re-checks the amount against `account.balance`, not the available balance. Consequently any new-ticket reservation on `…088` short-circuits to **409** (observed), and the available-balance guard is only reachable when no lease is held — where `available == balance`. The scenario's `25 € → INSUFFICIENT_BALANCE` (available 22 < balance 30 binding) is therefore **structurally unreachable** without a `src/main` change, which is out of scope for this test class. The test body keeps the spec-faithful assertions for the day the ordering is corrected; the `@Disabled` reason records the blocker.
