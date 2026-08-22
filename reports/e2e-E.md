# E2E group E — Ingestion `POST /api/events/*` (the credit that makes faith)

Class: `src/test/java/com/intermarche/fidelity/e2e/GroupEIT.java`
Command: `mvn -q verify -DskipUTs=true -Dit.test=GroupEIT -DskipITs=false`
Result: **Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS.**

## Scenarios covered (12/12, all RestAssured tier — no `[W]`/`[P]` in group E)

| Id | Scenario | Key assertions |
|----|----------|----------------|
| E1 | ticket-closed nominal | 202; one EARN movement per rule with `ruleCode`/`ticketRef`/`earnYear`=civil year of `fiscalDate`; balance recomputed by +0.30; trace SUCCESS carrying `displayedEarn`+`recalculatedEarn` and verbatim request/response payloads. |
| E2 | the two 400 guards | `ticket-closed` missing **and** blank `ticketRef` → `{"error":"Missing ticketRef"}`; `ticket-return` missing origin **and** missing return ref → `{"error":"Missing returnTicketRef or originTicketRef"}` (each jamb of the composed guard, §29.6). |
| E3 | idempotence | replay of the same `ticket-closed` → 202 again, still exactly one EARN movement (natural key `ticketRef+type+ruleCode`, I8). |
| E4 | the recalc prevails | lying displayed 2.10 vs recalc 2.05 → credit 2.05 + `EARN_MISMATCH`; bundle basket recalc 0 → 202, zero movement, SUCCESS header, no drama. |
| E5 | CARD_MISMATCH | event `card` ≠ request `customerCode` → credit on the request card, `CARD_MISMATCH` traced. |
| E6 | RESILIATED_ACCOUNT | event on …095 → NO_MOVEMENT trace + `RESILIATED_ACCOUNT` warning, balance frozen. |
| E7 | systematic visit | non-earning card ticket → header written, month visit +1; cardless ticket → traced, no movement, no visit. |
| E8 | transfer chain | event to …064 (lost) → credited on …071 (successor) via `resolveActive`. |
| E9 | return before origin | orphan return → 202 held (unique by return ref); ingesting the origin replays it alone and clears it; a corrupt held payload logs `Failed to replay held return <ref>` with no escaping exception. |
| E10 | RETURN_DEBIT bounded | 1-of-3 partial return → −0.10 pro-rata; over-quantity return → −0.20 bounded by the residual line earn (never beyond 0.30); …057 witnesses the tolerated negative balance (I7). |
| E11 | refundToCard | `REFUND_CREDIT` movement, null `ruleCode`, out of every cap (monthly EARN cumulative stays the origin's 0.30, the 3.00 refund excluded). |
| E12 | warnings in trace | closed format on `earn_trace_warnings`: `UNKNOWN_EAN:3400000099999` (CODE:<ean>) + bare `CARD_MISMATCH` (CODE). |

## Files read

- `e2escenarios-imfid.md` (group E + inventory Q-A/Q-B/Q-F).
- `src/main/.../ingestion/{EventsResource,IngestionService,EventDtos}.java` — the ingestion contract.
- `src/main/.../domain/{EarnTrace,FidelityMovement,MovementType,PendingReturn,FidelityAccount}.java`, `earn/WarningCode.java`, `account/LedgerService.java`, `earn/ValuationReader.java`.
- `src/main/.../seed/DataInitializer.java` — the seeded 2026 world (cards, rules, balances).
- `src/test/.../e2e/{GroupCIT,BootLogCapture}.java` — established RestAssured/JSON/Panache patterns reused.

## Iterations

1. First run: 11/12 green; E11 failed — the assertion `monthlyEarnTotal == 0` was wrong because the origin ticket legitimately earns 0.30 (an EARN movement). Fixed to assert the cumulative equals exactly the origin earn (0.30), proving the 3.00 refund never entered a cap. Second run: 12/12 green.

## Hard points

- **Socle-rate determinism.** Ingestion writes the trace header *before* re-evaluating, so the current ticket counts as a visit; the 4th visit of a month boosts the socle to 10 %. Each earn-sensitive scenario pins its `fiscalDate` to a distinct 2026 month (all ≥ 2026-05-18, the SOCLE_5 window) on the neutral card …071, so no month ever reaches the 4th visit — 5 % base holds on every calendar day.
- **Cleanup + balance hygiene.** Every test removes its movements/trace/holds and recomputes the touched account balance, so the class is order-independent and delta assertions never drift.
- **Fiscal idempotence trap.** Every `ticketRef`/`returnTicketRef` carries a per-scenario unique suffix so no replay is silently absorbed; E3 replays deliberately to prove absorption.
- **Corrupt-replay path (E9).** Triggering `Failed to replay held return` required seeding a `PendingReturn` with an unparseable payload directly via Panache (no API path corrupts a hold), then ingesting the origin. The logged error stack trace in the build output is expected, not a failure.
- **Lazy fields.** Trace `warnings`/payloads and movement `account` are read inside `QuarkusTransaction` (warnings copied out) to avoid lazy-init outside a session.

## Justified residue

None. Group E has no `[P]` (prod-like) or `[W]` (Playwright) scenarios; all twelve run at the `@QuarkusTest` + RestAssured tier.
