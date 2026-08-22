# E2E group O — Listes, filtres & pagination UI

`GroupOIT` — `@QuarkusTest` + RestAssured over an admin form session. **4 tests, 0 failures,
0 errors, 0 skipped. BUILD SUCCESS.**

Campaign command:

    mvn -q verify -DskipUTs=true -Dit.test=GroupOIT -DskipITs=false

## Scenarios covered

- **O1 — rule list filters & sort** (`o1_ruleListFiltersAreContainsInsensitiveTypeStrictAndSortWhitelisted`)
  - `code` filter = contains, case-insensitive (`?code=socle` and `?code=SoClE` both surface
    `SOCLE_5_MARQUES`/`SOCLE_4_MARQUES`, hide the rest).
  - `type` filter = strict-exact (`?type=COMMUNITY_EARN` matches; the prefix `?type=COMMUNITY`
    matches nothing — never a contains).
  - Sort whitelist: `?sort=priority` honoured (hidden `value="priority"`); the injected
    `?sort=specification` silently falls back to `code` (hidden `value="code"`, rows ordered by
    code ascending — no error, no arbitrary order). `dir=desc` reverses the order.
- **O2 — pagination clamp & frozen `ListView` literals** (`o2_paginationClampsToRealBoundsWithFrozenLiterals`)
  - 31 rules over two pages of 25 (11 seeded + 20 isolated `ZZZ_O2_*` fillers).
  - `?page=999` → last page (`31 rules — page 2 of 2`, `Showing 26–31 of 31`).
  - `?page=-3` → first page (`31 rules — page 1 of 2`, `Showing 1–25 of 31`).
  - A no-match filter → `No rule`. The three literals (`No <label>`, `Showing <a>–<b> of <n>`,
    `— page <i> of <m>`) asserted verbatim, en-dash (–) and em-dash (—) included (garde-fou n°4).
- **O3 — empty states** (`o3_filteredRuleListAndEmptyCommunityListRenderTheirFrenchEmptyStates`)
  - Filtered rule list → `Aucune règle ne correspond au filtre.` +
    `Créez une règle, ou importez le domaine FIDELITY_RULES depuis l'écran Imports.`
  - Empty community list → `Aucune communauté.` +
    `Importez le domaine FIDELITY_COMMUNITIES depuis l'écran Imports.`
- **O4 — flat community list, counters & badges** (`o4_communityListIsFlatWithActiveMemberCountersAndBadges`)
  - No pager on the community list.
  - Active-member counter excludes expired memberships (crossing M4): at frozen 2026-08-22,
    `BABIES`=1, `STUDENTS`=1, `SMALL_BUDGETS`=0 (its only membership closed 2026-02-28).
  - `OUVERT`/`FERMÉ` badges, both proven via an isolated closed probe community carrying one
    open + one expired membership (badge `FERMÉ`, counter 1).

## Files read

- `e2escenarios-imfid.md` (group O + inventory Q for literal cross-checks)
- `src/main/java/com/intermarche/fidelity/ui/ListView.java`, `UiSupport.java`,
  `RuleUiResource.java`, `CommunityUiResource.java`, `CommunityRow.java`, `RuleRow.java`
- `src/main/resources/templates/RuleUiResource/list.html`, `CommunityUiResource/list.html`
- `src/main/java/com/intermarche/fidelity/domain/FidelityRule.java`, `FidelityCommunity.java`,
  `FidelityMembership.java` (+ FK survey of `FidelityMovement`/`EarnTraceLine`/`FidelityActivation`)
- `src/main/resources/seed/03-communities.csv`, `04-rules.csv`, `09-memberships.csv`
- `src/test/java/com/intermarche/fidelity/e2e/GroupJIT.java`, `GroupLIT.java` (session/seed patterns)

## Iterations

One. The class compiled and all four scenarios passed on the first campaign run — no source
touched, only the generated test class added.

## Hard points

- **`— page <i> of <m>` literal is unreachable from the seed** (11 rules < 25/page → single
  page). Solved deterministically by minting 20 isolated `ZZZ_O2_*` filler rules to cross the
  page boundary (total 31 → 2 pages), purged in a `finally`. Direct Panache persist bypasses the
  admin schema validation, which is immaterial to a pagination count.
- **The community empty state cannot exist under the always-seeded catalog.** Rather than a
  flaky skip, O3 snapshots the whole seeded community + membership catalog into detached records,
  empties it (memberships first for the FK, then communities), asserts the empty state, and
  restores it byte-for-byte in a `finally`. Verified the only FK to `FidelityCommunity` is
  `FidelityMembership`, so the wipe/restore is complete and order-independent.
- **Per-row counter assertions** are isolated by slicing each community row from its code link to
  the next `</tr>`, so a `<td>1</td>`/`<td>0</td>` check never leaks across rows.

## Justified residue

None. Group O carries no `[P]` (prod-like) or `[W]` (Playwright) scenarios; all four are
unmarked and run at the `@QuarkusTest` + RestAssured tier. All pass.
