# E2E group J — UI Communautés (catalogue & atelier)

Class: `src/test/java/com/intermarche/fidelity/e2e/GroupJIT.java` — `@QuarkusTest` +
`@WithPlaywright`. Campaign: `mvn -q verify -DskipUTs=true -Dit.test=GroupJIT -DskipITs=false`.
Result: **Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS.**

## Scenarios covered

- **J1 Création** (`@QuarkusTest` + RestAssured, form session) —
  `j1_creationFormAppliesOrderedGuardsThenRedirectsToWorkbench`. Asserts the
  `Nouvelle communauté` button on the list, then each ordered guard literal: taken code
  (`A community with code 'BABIES' already exists`), negative caps
  (`The monthly cap cannot be negative` / `The enrollment cap cannot be negative`),
  half-window (`A renewal window needs both its start and end months`), out-of-range month
  (`A renewal month must be between 1 and 12`), and the 303-to-workbench success
  (`Community ZZZ_J1_OK created`, community persisted active).
- **J2 Édition** (form session) — `j2_editionFreezesTheCodeAndReportsUpdated`. Code field
  rendered `readonly`, form posts to `/update`, success `Community ZZZ_J2 updated`, new label
  persisted in base.
- **J3 Fermeture aux enrôlements** (form session + `/graphql` Basic admin) —
  `j3_closureBlocksEnrollmentsPreservesExistingMembersAndReopens`. Open workbench confirm text,
  `OUVERT`→`FERMÉ` badge, closed banner, existing membership stays active (keeps earning —
  cross C10), GraphQL enroll refused `Community 'ZZZ_J3' is closed to new enrollments (§23.2)`,
  workbench add non-member refused `…: card 2990000000019 cannot be added (§23.2)`, re-saving
  existing members alone passes (`1 membership(s) saved`), reopen
  `Community ZZZ_J3 reopened to enrollments`.
- **J4 Atelier = source complète de vérité [W]** (Playwright, headless Chromium) —
  `j4_workbenchIsTheCompleteSourceOfTruth`. Removes a row + `Enregistrer la structure` →
  deletion by omission (`1 membership(s) saved`, omitted membership gone from base), unknown
  card `9999999999999` silently ignored, over-cap submission refused
  `Enrollment cap reached for community 'ZZZ_J4' (2, §28.4)` with nothing written.

## Files read

- `e2escenarios-imfid.md` (§J, cross-refs C10, §23.2, §28.4).
- `src/test/java/…/e2e/GroupIIT.java` (form-session + Playwright reference pattern).
- `ui/CommunityUiResource.java`, `ui/CommunityFormView.java`; templates
  `templates/CommunityUiResource/{list,form,workbench}.html`.
- `admin/AdminService.java` (community guards, `replaceMemberships`, `upsertMembership`),
  `admin/MembershipInput.java`.
- `domain/{FidelityCommunity,FidelityMembership,FidelityAccount}.java`.
- `graphql/FidelityGraphQLApi.java` + `graphql/GraphQLTypes.java` (`upsertMembership`, `guard`).
- `ui/UiSupport.java`; `seed/{03-communities,05-accounts,09-memberships}.csv`.

## Iterations

1. First run: J2/J3 green; J1 failed (redirect `Location` is absolute → `startsWith` too
   strict) and J4 failed (`0 membership(s) saved`).
2. J1 fixed with `contains`. Diagnosed J4 via a browser-side dump: the surviving row's
   `validFrom` came back `null`.
3. Root cause (see residue): fixed by re-affirming row dates in the browser before each save
   and asserting deletion-by-omission / unknown-ignore / cap. Second run: **4/4 green.**

## Hard points

- **No POS enroll endpoint**: enrollment refusal (J3) is only reachable through the
  `/graphql` `upsertMembership` mutation (Basic admin), where `guard()` surfaces the
  `AdminException` message verbatim in `errors[0].message`.
- **Seeded-world isolation**: all mutations run on per-scenario `ZZZ_*` communities minted and
  purged in a `finally` (memberships deleted before the community for the FK), so the seeded
  four communities are only read and no test depends on another's order.
- **Playwright row targeting**: rows carry input *values* set via the `.value` property (no
  reflected `value` attribute), so a `[value=…]` CSS selector cannot match — rows are located
  by iterating and reading `inputValue()`.

## Justified residue

- **No `[P]` scenario in group J** — nothing is `@Disabled`.
- **Observed app behaviour (reported, not fixed — `src/main` untouched):** the workbench
  serializes its initial membership set with an `ObjectMapper` that does not disable
  `WRITE_DATES_AS_TIMESTAMPS`, so `LocalDate` `validFrom` is emitted as a numeric array and
  never populates the `type=date` inputs on load. A seeded member's date therefore does **not**
  round-trip through a no-op save; the test re-affirms dates in the browser (as an operator
  must) rather than working around it in source. This is a latent usability defect worth a
  `src/main` follow-up (disable date timestamps on `CommunityUiResource.MAPPER`).
