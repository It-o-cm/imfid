# E2E group L — Simulateur [W]

Class: `src/test/java/com/intermarche/fidelity/e2e/GroupLIT.java`
Command: `mvn -q verify -DskipUTs=true -Dit.test=GroupLIT -DskipITs=false`
Result: **Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS.**

Group L is entirely `[W]`: every scenario drives a real headless Chromium
(`@QuarkusTest` + `@WithPlaywright`) against the DataInitializer-rebuilt 2026 world,
pasting a `/valuation` response into the real `/ui/simulator` form and reading the
rendered earn back.

## Scenarios covered

- **L1 — Nominal.** Reference milk+water+yoghurt couple on the rich card `…019` at a
  forced August-2026 date. Asserts the earn is rendered rule by rule
  (`SOCLE_5_MARQUES` entry), the total (10 % boosted = 0.60 €), the burnable base
  (6.00 €), and — the C1 contract — that the simulation writes nothing (account /
  movement / earn-trace counts unchanged before and after, read via
  `QuarkusTransaction`).
- **L2 — Préremplissage fossile (⚠ trap).** Asserts the GET still preloads the retired
  CSV card `LOYALTY-DEMO-001`, and that simulating it as-is in the `299…` world yields
  the literal note `Carte inconnue — earn vide (§20)`, an empty earn
  (`Aucune entrée earn…`), and a still-rendered burnable base.
- **L3 — Erreurs.** An unparsable couple renders `Couple invalide : <msg>`; a couple
  whose total does not reconcile with its offers renders
  `Réconciliation §22.1 échouée : <msg>`. Both asserted by literal prefix.
- **L4 — Leviers de démonstration.** Three forced-date levers: the 28th lights the
  Students advantage (`STUDENTS_HYGIENE_28`, card `…033`, Labell hygiene line); a
  Saturday lights `FL_WEEKEND`; a date before 2026-05-18 answers with the four-brand
  socle version (`SOCLE_4_MARQUES`, `SOCLE_5_MARQUES` absent).

## Files read

- `e2escenarios-imfid.md` (§L, cross-referenced §C for earn semantics and cards).
- `src/main/java/com/intermarche/fidelity/ui/SimulatorUiResource.java`,
  `SimulatorView.java`, `templates/SimulatorUiResource/simulator.html` — DOM contract,
  notice/error literals, fossil prefill.
- `src/main/java/com/intermarche/fidelity/earn/ValuationResponse.java`,
  `EarnEngine.java` (null-account path) — JSON shape and empty-earn behaviour.
- Seed CSVs `04-rules.csv`, `05-accounts.csv`, `07-visits.csv`, `09-memberships.csv`,
  `08-activations.csv` — seeded cards, rules, and the three `…019` August visit days.
- `src/test/java/com/intermarche/fidelity/e2e/GroupIIT.java` (Playwright login pattern),
  `GroupCIT.java` (valuation-couple builders, earn oracle).

## Iterations

1. First run: `button[type='submit']` matched two elements (the layout's "Déconnexion"
   button + "Simuler"). Fixed by scoping the submit to
   `form[action='/ui/simulator'] button[type='submit']`.
2. Second run: `count(FidelityAccount::count)` bound to the un-enhanced
   `PanacheEntityBase.count()` (method references bypass Panache bytecode rewriting).
   Fixed by using explicit lambdas `() -> FidelityAccount.count()` like GroupCIT.
3. Third run: green (4/4).

## Hard points

- **Method references vs Panache enhancement.** `Entity::count` resolves to the base
  class and throws `implementationInjectionMissing`; only explicit call sites are
  enhanced. Use `() -> Entity.count()`.
- **Layout submit collision.** The admin layout carries a second `type=submit`
  (logout), so the Simuler button must be located inside its own form.
- **Deterministic boost.** `…019` carries three seeded August-2026 visit days, so a
  nominal evaluation forced into August 2026 is the fourth visit and boosts to 10 %
  with no aging needed — the forced date lands in the seeded visits' own month, so the
  test is calendar-stable.

## Justified residue

- **No `[P]` scenario** in group L — nothing is `@Disabled`.
- **L1 "plafonds" facet.** `capsApplied` renders only when a cap actually bites; the
  nominal reference cart saturates no seeded cap, so the caps table is legitimately
  absent. Cap-truncation rendering is exercised by group C (C10), not reachable from
  the nominal L1 basket without saturating a community cap the simulator is not meant
  to force. Documented in the class Javadoc.
