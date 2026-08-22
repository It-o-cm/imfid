# E2E group A — startup & bootstrap — report

`GroupAIT` (package `com.intermarche.fidelity.e2e`), plus the test-only helper bean
`BootLogCapture`. Campaign command, green:

    mvn -q verify -DskipUTs=true -Dit.test=GroupAIT -DskipITs=false
    → Tests run: 6, Failures: 0, Errors: 0, Skipped: 2  (exit 0)

## Scenarios covered

| Id | Method | Tier | Result |
|----|--------|------|--------|
| A1 | `a1_bootLogsAnnounceSeedRegistryAndBootstrap` | @QuarkusTest + boot-log capture | green |
| A2 | `a2_reloadedWorldAndBootstrapGuardHold` | @QuarkusTest + Panache | green |
| A3 | `a3_seedLoaderIsProdOnly` | [P] | `@Disabled` (residue) |
| A4 | `a4_prodStartupFailsWithoutRequiredEnv` | [P] | `@Disabled` (residue) |
| A5 | `a5_theNineCardsOfTheWorld` | @QuarkusTest + Panache | green |
| A6 | `a6_calendarSensitivityOfTheSeed` | @QuarkusTest + Panache | green |

### Attendus asserted

- **A1** — the three contractual boot log lines, captured in emission order:
  - exact seed line `Dev/test dataset loaded: 46 products, 11 rules, 4 communities,
    9 accounts, 22 movements`;
  - registry line prefix `Earn rule registry started: 7 schemas registered …`, asserted
    to occur **before** the seed line (registry @Priority default 2500 < seed 2700);
  - exact bootstrap line `Bootstrap users created: 'pos' (pos), 'admin' (fid-admin)`.
- **A2** — the reloaded world is fully present (`SOCLE_5_MARQUES` back; counts
  46/11/4/9/22) and `app_users` (owned by the bootstrap, untouched by the seed wipe)
  holds **exactly** the two operator accounts with no duplicate and the right roles —
  the `AppUser.count() > 0` guard held across the JVM life.
- **A5** — the nine cards: exact status + balance (`compareTo`, scale 2) for each; open
  BABIES membership on `…026`; STUDENTS→2026-10-31 and expired SMALL_BUDGETS→2026-02-28
  on `…033`; `transferredToCard=…071` on `…064`; ACTIVE 8.00 € lease expiring in the
  future on `…088`; one ACTIVATION_VOID movement on `…095`; every number a valid EAN-13
  (mod-10 check digit) prefixed `299`.
- **A6** — `ECOUPON_DEMO`/`CHALLENGE_DEMO` windows recalibrated to `[1st, 1st of next
  month)` relative to the boot clock; the 4th-visit boost sensitivity proven
  deterministically by ageing `…019`'s traces: aged out of the month → 0 visits (< 3,
  boost silent, the 1st–6th regime); three distinct in-month days → 3 visits (boost
  arms).

## Files read

- `e2escenarios-imfid.md` (group A, cross-refs A6/N6, inventory Q-F logs).
- `src/main/.../seed/DataInitializer.java`, `security/SecurityBootstrap.java`,
  `rule/EarnRuleRegistry.java` (log strings, @Priority ordering, seeded world).
- Domain: `FidelityAccount`, `AccountStatus`, `FidelityMovement`, `MovementType`,
  `FidelityReservation`, `ReservationState`, `FidelityMembership`, `FidelityRule`,
  `FidelityCommunity`, `EarnTrace`, `AppUser`, `util/DateTimeProvider`.
- `src/test/.../e2e/GroupBIT.java` (established RestAssured/QuarkusTransaction patterns);
  `pom.xml` (failsafe wiring, JBoss LogManager).

## Iterations

One. The class compiled and went green on the first campaign run (6 tests, 0 failures,
2 skipped); confirmed reproducible on a second run (exit 0).

## Hard points

- **A1 needed boot-log capture, and none existed.** Quarkus configures logging before the
  `StartupEvent` observers run and there is no built-in `LogCollectingTestResource` on the
  classpath. Solution: `BootLogCapture`, a test-only `@ApplicationScoped` bean that
  observes `StartupEvent` at `@Priority(1)` (earlier than the registry/bootstrap at the
  CDI default 2500 and the seed at 2700) and attaches a JBoss LogManager handler to the
  root logger, resolving `ExtLogRecord.getFormattedMessage()` for the `infof` printf
  style. It is inert for every other group's run.
- **A2's literal restart is not reproducible in a single `@QuarkusTest` JVM.**
  Re-dispatching `StartupEvent` in-process would trip `EarnRuleRegistry.onStart`'s
  duplicate-type guard (`putIfAbsent` → non-null → `IllegalStateException`), and the seed
  observer is package-private (no clean re-invocation). A2 therefore asserts the
  observable reload/bootstrap invariants in-process; the create-then-restart erasure is
  proven "for free" by each fresh campaign boot (catalog N6). Documented in the method
  Javadoc.
- **A6 calendar flakiness.** The seeded `…019` visits are anchored at now−6/−4/−2, so the
  in-month count depends on the boot date (invalid on the 1st–6th). Per the CLAUDE.md e2e
  rule, made deterministic by **ageing the visit rows** via `QuarkusTransaction` to
  demonstrate both regimes, instead of a date-conditional skip.

## Justified residue ([P], `@Disabled`)

- **A3** `a3_seedLoaderIsProdOnly` — SeedLoader is `@IfBuildProfile(prod)` and only loads
  the 9 CSVs on an empty PostgreSQL base; the dev/test profile excludes the bean, so its
  seed path cannot be exercised in-process. Enabled only under a prod-like harness.
- **A4** `a4_prodStartupFailsWithoutRequiredEnv` — a failing startup without
  `IMFID_DB_URL`/`IMFID_SESSION_KEY`/bootstrap passwords needs a prod-profile boot with
  missing env, impossible under the in-process dev/test `@QuarkusTest` that supplies its
  own H2 and default credentials. Enabled only under a prod-like harness.
