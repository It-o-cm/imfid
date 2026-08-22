# E2E group I — UI Règles (`GroupIIT`)

Campaign: `mvn -q verify -DskipUTs=true -Dit.test=GroupIIT -DskipITs=false`
Result: **Tests run: 6, Failures: 0, Errors: 0, Skipped: 0** — all green. No `[P]` scenario in this group; one *sub-assertion* of I5 is a justified residue (unreachable under a `src/main` invariant, see below).

## Scenarios covered

| Id | Tier | Method | What it proves |
|----|------|--------|----------------|
| I1 | RestAssured (form session) | `i1_listShowsFourBadgesClickableCodesEditerOnlyUpcomingAndNoFermer` | List renders the four badges — `ACTIVE` (`badge-ok`) on `SOCLE_5_MARQUES`, `CLOSED` (`badge-off`) on `SOCLE_4_MARQUES`, `UPCOMING`/`INACTIVE` on two isolated test rules; codes are `/ui/rules/<code>` links; `Éditer` appears on the UPCOMING row and on **no** in-force row; **no** `Fermer` button anywhere (§18). |
| I2 | **[W]** Playwright | `i2_consultationIsAFrozenFormWithLiveToggleAndFacteurCent` | `SOCLE_5_MARQUES` sheet: backbone inputs (`code`/`type`/`validFrom`) **and** the schema-driven rate field disabled; Form/JSON toggle enabled; rate shows **5** while the JSON view holds the raw `"baseRate": 0.05` (facteur cent); the lock is **re-applied after the JSON → Form re-render** (the fragile point). |
| I3 | **[W]** Playwright | `i3_creationIsSchemaDrivenPerTypeAndRejectsInvalidSpecAndOverlap` | Form regenerated per type (BRAND rate field present → gone; PROGRAM_EXCLUSION scope block present); incomplete spec re-renders with `Invalid specification for type 'BRAND_TIERED_EARN': …`; a window on an existing code re-renders with `Rule window overlaps an existing instance of code 'SOCLE_5_MARQUES'`. |
| I4 | RestAssured (form session) | `i4_editionIsAllowedOnUpcomingOnlyAndForbidsAPastStart` | `GET …/SOCLE_5_MARQUES/edit` → **303** to the sheet + `Only a rule not yet in force can be edited; duplicate then close instead (§18)`; on an UPCOMING rule the form is live (`data-readonly="false"`), code `readonly`, posts to `/update`, submittable; a past `validFrom` on `/update` → `An edited rule cannot start in the past (§18)`. |
| I5 | RestAssured (form session) | `i5_endOfApplicationEditorSetsClearsRefusesPastAndFreezesEndedRules` | End-of-application block **absent** on CLOSED, **present** on open; set → `Rule <c>: end of application set to 2027-06-01T00:00` (persisted); clear → `…cleared (open-ended)` (validTo null); past → `The end of application can never be set in the past (§18)`; ended rule → `No rule with code 'SOCLE_4_MARQUES' whose window is still open; an ended rule is frozen — version it instead (§18)`. |
| I6 | RestAssured (form session) | `i6_duplicationPrefillsProposedCodeTypeAndSpecAndGuardsTakenCode` | `GET /ui/rules/new?from=SOCLE_5_MARQUES` prefills code `SOCLE_5_MARQUES_V2`, preselects the type, carries the spec; direct-copy POST → `Rule … duplicated to ZZZ_I6_COPY` with type **and** specification copied (asserted in DB); taken code → `A rule with code 'FL_WEEKEND' already exists`. |

## Files read

- `e2escenarios-imfid.md` — group I catalog + Q-C/Q-D literals (admin guards, UI notices).
- `src/test/java/.../e2e/GroupHIT.java` — the `@WithPlaywright` + form-session + isolated-entity + freeze patterns imitated here.
- `src/test/java/.../e2e/GroupAIT.java` — the seeded rules facts (11 rules, `FidelityRule.findByCode`, calendar-relative demo windows).
- `src/main/java/.../ui/RuleUiResource.java` — every `/ui/rules` route, form params, redirect targets and notice literals.
- `src/main/java/.../admin/AdminService.java` — `createRule`/`updateRule`/`updateEndDate`/`duplicateRule` guards and their exact `AdminException` messages; `windowsOverlap`, `openRuleByCode`, `latestRuleByCode`.
- `src/main/java/.../domain/FidelityRule.java` — fields, `unique=true` on `code`, tiers lifecycle, `isInForceAt`.
- `src/main/java/.../ui/UiSupport.java` — `redirect` (303 See Other + `notice`/`noticeOk`), `canWrite`.
- `src/main/resources/templates/RuleUiResource/{list,form}.html` — badge markup, `Éditer` gate, absence of `Fermer`, the end-of-application block gate (`view.open`), `data-readonly`.
- `src/main/resources/META-INF/resources/ui/rules-form.js` — schema-driven render, the `rate` factor-100 conversion, `lockIfReadOnly()` re-applied on every render and toggle.
- `src/main/resources/schemas/rule/{BRAND_TIERED_EARN,PROGRAM_EXCLUSION}.json` — required fields (invalid-spec case) and `x-label`s (`Taux de base`).
- `src/main/resources/seed/04-rules.csv` — the 11 seeded rows and their windows.
- `src/main/resources/META-INF/resources/ui/fidelity.css` — `.schema-scope-title { text-transform: uppercase }` (the I3 first-run trap).

## Iterations

1. First full run: **5/6 green**. I3 failed on `#schema-fields` "Inclusions" — Playwright `innerText()` returns the CSS-*rendered* text, and `.schema-scope-title` is `text-transform: uppercase`, so the DOM's "Inclusions" reads back "INCLUSIONS".
2. Switched the three `#schema-fields` content checks from `innerText()` to `textContent()` (raw DOM text, immune to CSS transforms) → **6/6 green**.

## Hard points

- **No seeded UPCOMING/INACTIVE rule.** Every one of the 11 seeded rows is ACTIVE or CLOSED at any campaign date past 2026-05-18 (`CGU_EXCLUSION` is `active=true`, not inactive). So I1/I4 mint isolated `ZZZ_*` test rules (future `validFrom` → UPCOMING; in-window + `active=false` → INACTIVE) via direct Panache persist, purged in a `finally`; the seeded socle rules are only read.
- **Frozen clock, literal windows.** The whole class freezes `DateTimeProvider` at `2026-08-22T10:00` (`@BeforeEach`/`@AfterEach`), so `SOCLE_5_MARQUES` is always ACTIVE and `SOCLE_4_MARQUES` always CLOSED, 2027 is always the future and 2020 always the past — never a calendar-flaky test (§24.6).
- **Facteur cent in a real browser (I2).** The rate field is located by its `x-label` text so it survives the JSON → Form DOM rebuild; the assertion pair is `input value == "5"` (shown) vs. `"baseRate": 0.05` in the JSON textarea (stored). The consultation lock is verified specifically **after** the round-trip re-render — the catalog's flagged fragile point.
- **I3 without persistence.** All three I3 branches are non-persisting: the type-switch never submits; the invalid-spec and overlap submits throw `AdminException` before any insert (so even reusing the existing code `SOCLE_5_MARQUES` for the overlap case triggers the overlap guard *before* the unique-key insert). Nothing seeded, nothing to purge (a defensive `ZZZ_I3_INVALID` purge is kept regardless).
- **303 read, not followed.** `/ui/*` POST/redirect responses are read with `redirects().follow(false)`; the notice and target path are parsed from the `Location` header (following it would drop the session cookie), matching the group-H pattern.

## Justified residue

- **I5, one sub-assertion — "extend a window over the next version of the same code" → `The new window would overlap another instance of code '<c>'`.** Unreachable in this implementation: `FidelityRule.code` is declared `@Column(unique = true)`, so no two rows can ever share a code, and `AdminService.updateEndDate`'s same-code overlap loop (`FidelityRule.list("code", code)` filtered to `id != rule.id`) can therefore never find a sibling. The guard is defensive dead code under the unique-code invariant. Per the `src/main`-read-only rule this is reported, not worked around; the other four end-of-application attendus of I5 are fully covered. (The related create-time overlap literal `Rule window overlaps an existing instance of code '<c>'` **is** reachable and is asserted in I3, because there the guard fires against the *existing* row before the duplicate insert.)
- No `[P]` scenario exists in group I.
