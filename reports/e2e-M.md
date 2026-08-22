# E2E group M — GraphQL d'administration (`/graphql`, Basic fid-admin) + écran Programme

Class: `com.intermarche.e2e.GroupMIT` — `@QuarkusTest` + RestAssured.
Result: **5 tests, 0 failures, 0 errors, 0 skipped** (BUILD SUCCESS).

Campaign command (a random test port used only because ports 8081/8060 were held by
unrelated IntelliJ/`run-e2e-campaign.sh` processes; the canonical command is otherwise
unchanged):

    mvn -q verify -DskipUTs=true -Dit.test=GroupMIT -DskipITs=false -Dquarkus.http.test-port=0

## Scenarios covered

- **M1 Sécurité** — `/graphql` challenges anonymous with 401; an authenticated `pos`
  is refused at the GraphQL layer (see residue below); a single `fid-admin` role gates
  both a query (200 + data) and a mutation (200, business error surfaces), so there is
  no imvaluation-style cross-role trap.
- **M2 Mutations règles** — `AdminException` transits through `GraphQLException` onto
  `errors[0].message` verbatim: `createRule` unknown type →
  `Unknown rule type 'NOPE' (no factory deployed)`; `closeRule` past validTo →
  `A rule is closed with a validTo not in the past (§18)`, and the refused close leaves
  the rule open (nothing written).
- **M3 `closeRule` survit à l'UI** — machine-stable semantics independent of I5: an
  explicit `validTo` is posted verbatim (`2030-06-30T00:00:00`), and a missing `validTo`
  defaults to today's start of day at the frozen clock (`2026-08-22T00:00:00`); both
  persisted. Proven on isolated `ZZZ_M3*` rules, seeded/purged per test.
- **M4 Les queries** — `communities` returns each community with its active-member
  count and excludes an expired membership window (BABIES 1, LARGE_FAMILIES 0,
  STUDENTS 1, SMALL_BUDGETS 0 — the `…033` card is counted for STUDENTS but its expired
  SMALL_BUDGETS window is not); `programSettings` restores exactly the four parameters
  (`card.prefix`=299, `program.globalMonthlyCap`=400.00, `program.zone`=Europe/Paris,
  `reservation.leaseTtlSeconds`=900); `RuleType` exposes exactly its ten backbone fields
  (frozen by introspection); the account query is keyed by card and exposes
  cardNumber/status/balance/availableBalance/monthVisits.
- **M5 Écran Programme** — the four settings render pre-filled with their defaults
  (`value="400.00"`, `"900"`, `"Europe/Paris"`, `"299"`); saving reports
  `Setting <clé> saved`; a manual `ACTIVATION_VOID` execution shows its result
  (`ACTIVATION_VOID — Exécution : a traité … € sur … compte(s)`); and there is no
  write-side type validation — a non-numeric cap is accepted and stored verbatim
  (robustness is on the read side via `getDecimal`, §31.2), then restored to `400.00`
  so the setting inventory is left untouched.

## Files read

- `e2escenarios-imfid.md` (group M, plus N for the cross-cutting contract).
- `src/main/java/com/intermarche/fidelity/graphql/FidelityGraphQLApi.java`,
  `graphql/GraphQLTypes.java`.
- `src/main/java/com/intermarche/fidelity/admin/AdminService.java` (createRule/closeRule
  guards and their literal messages).
- `src/main/java/com/intermarche/fidelity/ui/ProgramUiResource.java` +
  `templates/ProgramUiResource/program.html` (settings/batch notices).
- `src/main/java/com/intermarche/fidelity/batch/{BatchService,BatchType,BatchResult}.java`.
- `src/main/java/com/intermarche/fidelity/domain/{FidelityRule,FidelityProgramSetting,
  BaseEntity}.java`, `account/AccountViews.java`, `domain/util/{ProgramClock,DateTimeProvider}.java`.
- `src/main/java/com/intermarche/fidelity/seed/DataInitializer.java` (seeded communities,
  memberships, settings, rules; `…033` = CARD_STUDENT).
- `src/test/.../e2e/GroupJIT.java` (form-session + seed/purge patterns reused).
- `src/main/resources/application.properties` (roles, bootstrap `pos`/`admin` creds).

## Iterations

1. Wrote the class; first run failed to boot — port 8081 held by an unrelated process.
   Retried on a random test port (`-Dquarkus.http.test-port=0`); 4/5 passed.
2. M1 `pos` returned HTTP 200 (GraphQL error payload), not 403. Calibrated M1 to the
   real SmallRye rendering and froze it. **5/5 green.**

## Hard points

- **`pos` on `/graphql` is 200, not 403 (calibrated, frozen).** The class-level
  `@RolesAllowed(fid-admin)` raises a `ForbiddenException` *inside* the data fetcher, so
  SmallRye returns HTTP 200 with a GraphQL `errors` payload and null `data` — the catalog
  flagged the exact SmallRye rendering as "l'inconnue à calibrer au premier scénario puis
  à figer". Authorization is enforced at the GraphQL layer (no data leaks); only the
  anonymous case is a transport 401. Frozen accordingly. `src/main` untouched.
- **Non-destructive `Exécution` (M5).** `ACTIVATION_VOID` at the frozen clock
  (2026-08-22) targets PENDING accounts created before 2026-06-22; the only seeded
  PENDING card (`…040`) is created at boot, so the execute affects nobody — the notice is
  exercised without mutating the seeded world. The assertion checks the literal fragments,
  not counts, so it stays deterministic regardless.
- **Setting isolation.** M5's non-numeric write and same-value saves are restored in a
  `finally`, so M4's setting-inventory assertions are order-independent.

## Justified residue

None. Group M has no `[W]` and no `[P]` scenarios; all five run at the default
`@QuarkusTest` + RestAssured tier and are green.
