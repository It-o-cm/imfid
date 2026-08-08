Complete the plain unit test class for $ARGUMENTS following CLAUDE.md
("Tests unitaires (campagne par classe)"), then make it pass. If a test class
already exists for $ARGUMENTS, COMPLETE it — add the missing cases to reach full
branch coverage — never replace or truncate the existing tests.

Apply the per-class workflow:

1. Read the target class (and only what you actually need).
2. Enumerate every branch before writing: BOTH arms (null and non-null) of every
   guard and ternary, and EACH LEG of every compound guard `a || b || c` /
   `a && b && c` — one case per leg, nullities included (§29, §29.6). Every
   `BigDecimal` asserted by `compareTo`. Every protected division/prorata (§31.2)
   with a zero-denominator case and a non-zero one.
3. Time NEVER comes from the real clock: inject a mocked `DateTimeProvider` and
   FIX the instant. Test window/visit/lease/earnYear boundaries with the two
   instants straddling the border, never with the day the campaign runs (§24.6,
   §30.3, §25.1).
4. Plain unit only — no @QuarkusTest, no H2. Mock static finders with
   `mockStatic(PanacheEntityBase.class)` and neutralize `persist()` with
   `mockConstruction(<Entity>.class)`, both in try-with-resources. Earn appliers
   are pure logic: build baskets/specs in memory and assert the arithmetic to the
   cent — no Panache mocking where avoidable.
5. Write/extend the test → `mvn -q -Dtest=<TestClass> -DskipITs test` until green.
6. `mvn -q -Dtest=<TestClass> -DskipITs verify` → read the JaCoCo report for the
   target class (`target/site/jacoco/<pkg>/<Class>.html`) → fill every uncovered
   branch or line.

Scope is STRICT: never touch `src/main`; if a bug or a testability obstacle is
found, stop and report it in one line. Touch only the test class.

Finish with the report: branch count n/n, coverage %, files read, iterations,
and any justified residue.
