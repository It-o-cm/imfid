# E2E group F — API comptes (`GET /api/accounts/…`)

**Class:** `src/test/java/com/intermarche/fidelity/e2e/GroupFIT.java`
**Command:** `mvn -q verify -DskipUTs=true -Dit.test=GroupFIT -DskipITs=false`
**Result:** Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 — green.

## Scenarios covered

| Id | Tier | What it asserts |
|----|------|-----------------|
| F1 | RestAssured | Unknown card → 404 `{"error":"Unknown card"}` (closed literal). |
| F2 | RestAssured | Summary of `…088`: 200; `balance` 30.00 €; `availableBalance` 22.00 € (30.00 − the 8.00 € ACTIVE lease, I11); `status` ACTIVE; `monthVisits` 0; every `monthlyCaps[]` scope is of the closed `GLOBAL` \| `RULE:<code>` \| `COMMUNITY:<code>` nomenclature; every cumulative `used` = 0.00 € (the seeded ADJUSTMENT never figures — EARN only); GLOBAL cap = 400.00 €; `memberships[]` empty for this card. |
| F3 | RestAssured | History of `…019`: most recent first (`movementDate desc`); every `type` badge in the closed nine-name nomenclature; the six seeded types (EARN, EXPIRY, ADJUSTMENT, REFUND_CREDIT, BURN, RETURN_DEBIT) restituted; `reason` carried on the ADJUSTMENT row (`Initial demo balance (seed)`) and null on an EARN row; `page=-5` clamped to page 0; `size=999` clamped to 200 rows (§31.3). |

All three scenarios are unmarked in the catalog → RestAssured tier. No `[W]` and no `[P]` in group F, so there is **no `@Disabled` residue**.

## Files read

- `e2escenarios-imfid.md` (§F, and the seeded-world catalog lines for `…088`/`…019`).
- `src/main/java/com/intermarche/fidelity/account/AccountResource.java` — the 404 literal, the `page`/`size` clamps (`max(0,page)`, `min(size,200)`, default 50).
- `src/main/java/com/intermarche/fidelity/account/AccountService.java` — summary shape (caps count EARN only via `monthlyEarnByRule`/`monthlyEarnTotal`, memberships, available balance via `LedgerService`).
- `src/main/java/com/intermarche/fidelity/account/AccountViews.java` — the JSON field names.
- `src/main/java/com/intermarche/fidelity/domain/MovementType.java` — the nine-name nomenclature.
- `src/main/java/com/intermarche/fidelity/domain/FidelityMovement.java` — `pageForAccount` ordering (`movementDate desc, id desc`), field set for the bulk insert.
- `src/main/java/com/intermarche/fidelity/seed/DataInitializer.java` — the exact seeded facts of `…088` (30.00 € ADJUSTMENT + 8.00 € ACTIVE lease, no membership, no visit) and `…019` (six movement types + reason-bearing ADJUSTMENT); GLOBAL cap 400.00 €.
- `src/test/java/com/intermarche/fidelity/e2e/GroupEIT.java`, `GroupCIT.java` — the RestAssured / Basic-`pos` / `QuarkusTransaction` patterns imitated.

## Iterations

One. The class compiled and went green on the first campaign run (3/3).

## Hard points

- **The 200-row clamp is not observable on the seeded world.** No seeded card carries > 200 movements, so `size=999` on any of them returns fewer than 200 and cannot distinguish "clamped to 200" from "returned everything". F3 makes it observable deterministically: it inserts a back-dated bulk of 201 EARN rows (year 2020, so they sit at the history tail and never displace the seeded rows on page 0) under a marker `ticketRef` `F3-BULK`, asserts `size=999` returns exactly 200 items with a `totalCount` of `base + 201`, then removes the marker rows and recomputes `…019`'s balance in a `finally`. The class stays order-independent.
- **PURGE is absent from the seeded world.** The nomenclature has nine types but PURGE is produced only by the purge batch at runtime (§16), never seeded. F3 therefore asserts every restituted `type` is *within* the closed nine-name set and that the six types `…019` actually carries are restituted, rather than requiring all nine to appear — a grounded reading of "les 9 de la nomenclature (EARN…TRANSFER)".
- **Cap cumulatives count EARN only.** `…088`'s only movement this month is an ADJUSTMENT; F2 asserts every cap `used` = 0.00 €, which is exactly the catalog's "un ADJUSTMENT n'y figure jamais".

## Justified residue

None — no `[P]` scenario in group F.
