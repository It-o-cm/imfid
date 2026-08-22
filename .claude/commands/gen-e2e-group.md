Write the full e2e scenario test class for group letter $ARGUMENTS following
CLAUDE.md (section "E2E scenario tests (imfid — *IT classes)"), then make it
pass.

Scope and layout:
- One test class per group letter in package
  `com.intermarche.fidelity.e2e`, named `Group${ARGUMENTS}IT` (e.g.
  `GroupBIT`). One `@Test` per scenario; the scenario id in BOTH the method
  name and its Javadoc.
- Read the group's scenarios in `e2escenarios-imfid.md` at project root.
  Enumerate every attendu (HTTP status/headers/cookies, JSON, DB state, log,
  screen text) BEFORE writing.

Tiers — implement each scenario at the right level:
- Unmarked scenario → `@QuarkusTest` + RestAssured. Basic `pos` for
  `/api/*`; Basic `admin` for imports and `/graphql`; form session
  (`j_security_check`, `quarkus-credential` cookie) for `/ui/*`.
- `[W]` → `@QuarkusTest` + Playwright (quarkus-playwright, headless
  Chromium) for real-browser behaviour (schema-driven forms, consultation
  lock, drag & drop, EAN-13 barcode, workbench).
- `[P]` → prod-like env required: `@Disabled` with the exact reason as a
  justified residue until a prod-like harness exists. Do NOT delete it.
- `[D]` = default dev/test profile (no extra wiring).

Contract reminders (from CLAUDE.md — honour them):
- The DataInitializer rebuilds the full dataset at EVERY boot (wipe +
  reload). Never re-seed; assert against the seeded facts the catalog
  states.
- Date-relative scenarios (4th-visit / boost) are invalid on the 1st–6th of
  the month: age the relevant visit rows via `QuarkusTransaction` to make
  them deterministic — never a calendar-flaky skip. Dated evaluations go
  through the payload's `createdAt`, never the real clock.
- Fiscal idempotence: suffix every `ticketRef` with a per-test unique
  suffix, or a replay is silently absorbed.
- Admin notices are ENGLISH on French screens: assert the LITERAL texts the
  catalog quotes. DB assertions via Panache under `QuarkusTransaction`;
  never absolute ids or counters.

Loop to green with the campaign command:
    mvn -q verify -DskipUTs=true -Dit.test=Group${ARGUMENTS}IT -DskipITs=false
Iterate until the whole class is green (all non-[P] scenarios pass, [P]
scenarios @Disabled).

Style/scope: code and comments in English; Javadoc on EVERY method (tests
and private helpers included); JUnit 5 assertions only (never AssertJ); no
blank line inside a method body; touch only the generated test class and,
if needed, small e2e helpers under `com.intermarche.fidelity.e2e`. Never
modify `src/main`; a blocker stops and is reported in one line.

Finish with the report to `reports/e2e-${ARGUMENTS}.md` (create the
directory if needed): scenarios covered, files read, iterations, hard
points encountered, and any justified residue ([P] ids @Disabled with
their reason).
