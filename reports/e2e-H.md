# E2E group H — UI Cartes (`GroupHIT`)

Campaign: `mvn -q verify -DskipUTs=true -Dit.test=GroupHIT -DskipITs=false`
Result: **Tests run: 5, Failures: 0, Errors: 0, Skipped: 0** — all green, no `[P]` residue in this group.

## Scenarios covered

| Id | Tier | Method | What it proves |
|----|------|--------|----------------|
| H1 | RestAssured (form session) | `h1_creationGeneratesAValidPrefixedEan13NumberNeverKeyedIn` | `POST /ui/cards/create` mints a `299`-prefixed, 13-digit, valid-EAN-13 number never keyed in; notice `Card <n> created`; a second creation yields a distinct valid number (marche avant → no duplicate, §33.1). |
| H2 | **[W]** Playwright | `h2_sheetRendersScannableBarcodeCountersReservationAndHistory` | On `…088`: `fidelity.js` fills `<svg class="ean13" data-ean=…>` with exactly the EAN-13 module bars (bar count mirrored in Java — genuinely scannable); month counters shown; ACTIVE 8.00 € lease displayed (placeholder absent); movement history carries the seeded ADJUSTMENT. |
| H3 | RestAssured (form session) | `h3_adjustmentRequiresReasonAndAmountThenPostsOutOfCaps` | Blank reason → `An adjustment reason is mandatory (§32.1)`; zero amount → `An adjustment amount is mandatory`; valid → `Adjustment posted`, one ADJUSTMENT movement, `earnYear` = civil year of the gesture (frozen), balance follows. |
| H4 | RestAssured (form session) | `h4_transferPreservesEarnYearsMigratesDependentsAndResiliatesSource` | Literal transfer confirm in the sheet; POST mints a fresh card with a TRANSFER-in per **preserved** earnYear (2024→5.00, 2025→7.00, never rejuvenated), a TRANSFER-out −12.00 on the source, migrates membership + activation + visit, resiliates the source and sets `transferredToCard`; re-transfer → `A resiliated card cannot be transferred`. |
| H5 | RestAssured (form session) | `h5_resiliationConfirmsSucceedsWithoutLeaseAndIsRefusedOnAnActiveLease` | Literal resiliation confirm in the sheet; lease-free card → `Card resiliated`, status RESILIATED; the seeded `…088` holding an ACTIVE lease → `Refused: the card holds an active burn reservation (§28.1)` and stays ACTIVE (§30.2). |

## Files read

- `e2escenarios-imfid.md` — group H catalog + the A5 seeded-world card catalog.
- `src/test/java/.../e2e/GroupGIT.java` — the `@WithPlaywright` + form-session + isolated-card patterns imitated here.
- `src/main/java/.../ui/CardUiResource.java`, `UiSupport.java` — endpoints, notice literals, POST→303 cycle.
- `src/main/java/.../admin/AdminService.java` — `createCard`/`adjustCard`/`transferCard`/`resiliateCard` rules and literals.
- `src/main/java/.../domain/util/CardNumberGenerator.java` — EAN-13 check-digit algorithm (mirrored for H1).
- `src/main/resources/META-INF/resources/ui/fidelity.js` — EAN-13 module encoding (mirrored for H2 bar count).
- `src/main/resources/templates/CardUiResource/detail.html`, `ui/layout.html` — confirm literals, section labels, `fidelity.js` include.
- `src/main/java/.../account/LedgerService.java`, `AccountViews.java`, `domain/{EarnTrace,FidelityMovement,FidelityMembership,FidelityActivation,FidelityAccount}.java`, `seed/DataInitializer.java` — seeding shapes and seeded `…088` facts.

## Iterations

1. First full run: 4/5 green; H4 membership assertion failed — asserted the community's *sole* holder, but `BABIES` already has a seeded member (`…026`).
2. Reworked the check to `holdsMembership(target, BABIES)` (target holds a migrated membership) instead of sole-holder → **5/5 green**.

Also dropped an initially time-flaky `seededMatch` helper (it compared `createdAt` against a fixed instant); H1 now proves marche-avant purely by two distinct valid creations.

## Hard points

- **H2 pagination**: `…088` is the only card with an ACTIVE reservation but carries a single movement, so the pager (`pageCount > 1`) cannot render on it. The history is asserted as the rendered movements table with the seeded ADJUSTMENT row; multi-page paging is a template feature out of reach of any single seeded card.
- **Scannable barcode**: rather than assert "some bars", the test recomputes the 95-module EAN-13 encoding in Java (mirroring `fidelity.js`) and asserts the drawn `rect` count equals the number's `1`-module count — the encoding is exactly the card number.
- **Confirm dialogs (unmarked H4/H5)**: the `confirm(...)` text lives in the sheet's `onsubmit` attribute; asserted from the rendered HTML over the form session, honouring the JS-escaped apostrophe (`L\'ancienne`). The POST is client-side-unguarded, so the DB effects are asserted directly.
- **Isolation**: every mutating gesture runs on `999…` cards inserted/removed via `QuarkusTransaction` in a `finally`; the transfer's minted `299…` target is also purged. The seeded world's nine cards are only read (H5 lease refusal on `…088` is a no-op refusal). `earnYear` determinism in H3 comes from a frozen `DateTimeProvider`, cleared in `finally`.

## Justified residue

None. Group H contains no `[P]` scenario; all five are implemented and pass.
