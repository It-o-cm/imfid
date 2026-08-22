# E2E group Q — Inventaire messages & surfaces (relevé PAR LE CODE)

Class: `src/test/java/com/intermarche/fidelity/e2e/GroupQIT.java`
Command: `mvn -q verify -DskipUTs=true -Dit.test=GroupQIT -DskipITs=false`
Result: **9 tests, 0 failures, 0 errors, 0 skipped** (green).

Group Q is not a behaviour group but the inventory that every contractual literal the
catalog freezes is really produced by the code, on the right surface, at the right HTTP
status. Each sub-section (Q-A … Q-F) is one `@Test` carrying its Q-x id in name and Javadoc
(Q-C is split by family — rules / cards / communities — because it holds ~30 guards).

## Scenarios covered

- **qA_posApiStatusesAndBodies** — the 11 POS-API status/body couples: `/earn` missing
  valuation response (400 literal), totals & offer reconciliation (422 frozen prefixes
  `Incoherent totals: totalPrice …` / `Offer '<type>': Σ items …`), unknown card = 200 empty
  earn, the three typed reservation refusals (422 `INSUFFICIENT_BALANCE`/`DAILY_RULE`/
  `ACCOUNT_STATUS`), lease-on-another-ticket 409 empty body, confirm-expired 410 empty body,
  `ticket-closed` missing ref (400), `ticket-return` missing refs (400, both branches),
  `/api/accounts` unknown 404 `Unknown card`, `/api/batches` unknown type 400
  `Unknown batch type '<t>'` (admin-authenticated).
- **qB_warningsClosedNomenclatureAndTraceFormat** — the five-code closed nomenclature and its
  two formats: `UNKNOWN_EAN` on the projection (with EAN + §25.4 literal) and as
  `UNKNOWN_EAN:<ean>` on a trace; the projection raises only `UNKNOWN_EAN`; the four
  ingestion-only codes as bare `CODE` on their traces — `CARD_MISMATCH`, `EARN_MISMATCH`,
  `RESILIATED_ACCOUNT`, `EXPIRED_LEASE_CONFIRMED` (burn catch-up).
- **qC_ruleAdministrationGuards** — every rule `AdminException` literal on a reaching channel
  (GraphQL for create/close/duplicate, UI for update/end-date), with `Unknown rule type …`
  proven on both channels (UI+GraphQL) to show an `AdminException` transits both.
- **qC_cardAdministrationGuards** — adjust reason/amount, resiliated-cannot-transfer,
  active-lease refusal, `Unknown card '<n>'` (proven on GraphQL and UI).
- **qC_communityAdministrationGuards** — the UI-only catalog guards (already-exists, unknown,
  negative caps, renewal window/month) and the membership/activation guards (closed,
  enrollment cap, membership validFrom, activation periodStart).
- **qD0_importLineErrorsPerResource** — the per-resource CSV row-error literals (rules,
  adjustments, memberships, activations, visits, families), deliberately worded differently
  from their admin twins, plus the mechanic report proven to be valid JSON by parsing.
- **qD_uiNotices** — the ~19 English notices on the French screens, read verbatim off the
  303 `Location`, each on an isolated `ZZZ_Q_*`/minted card, purged in `finally`.
- **qE_frenchTexts** — login/forgot/reset French texts, the H4/H5/G4/J3 confirm dialogs on
  the card sheet / program / workbench, the simulator notes/errors, the imports drag-drop
  hint.
- **qF_contractualLogLines** — the three boot lines (via `BootLogCapture`) and the
  per-request lines (reset-link-sent, rejected earn, staged-fallback chunk, import finished,
  failed held-return replay).

## Files read

- `e2escenarios-imfid.md` (§ Q), `CLAUDE.md`.
- Source of truth: `earn/EarnResource.java`, `earn/WarningCode.java`, `earn/ValuationReader.java`,
  `ingestion/EventsResource.java`, `ingestion/IngestionService.java`, `account/AccountResource.java`,
  `batch/BatchTriggerResource.java`, `admin/AdminService.java`, `graphql/FidelityGraphQLApi.java`,
  `ui/{Rule,Community,Card,Program,Import,Auth,Simulator}UiResource.java`, `ui/UiSupport.java`,
  `security/{PasswordResetService,SecurityBootstrap}.java`, `seed/{DataInitializer,SeedLoader}.java`,
  `rule/EarnRuleRegistry.java`, the `imports/*CsvResource.java`, the `templates/**` and
  `domain/{AccountStatus,FidelityRule}.java`.
- Pattern reuse from existing IT classes: `GroupCIT` (earn/warnings), `GroupDIT` (burn),
  `GroupEIT` (ingestion / held-return replay), `GroupKIT` (imports), `GroupMIT` (GraphQL),
  `GroupO/H/I/JIT` (UI form session, notices), `GroupFIT` (account/log), `BootLogCapture`.

## Iterations

1. First compile — one generics error on a `getList` loop (fixed).
2. First run — 5/9 green; 4 fixes: (a) the `updateEndDate` "overlap another instance" proof
   hit the `FidelityRule.code` UNIQUE constraint (removed, see residue); (b) GraphQL
   `setActivation` field was undefined in the schema — routed that guard through the UI card
   activation instead; (c) the `Invalid submission:` message wording is
   parser-specific — asserted the frozen prefix; (d) the forgot-page text wraps across a
   template line break — asserted its two single-line halves.
3. Second run — **all 9 green.**

## Hard points

- **Channel reachability of admin guards.** GraphQL exposes only create/close/duplicate rule,
  the card mutations, `upsertMembership`; `updateRule`/`updateEndDate` and the whole community
  catalog are UI-only. Each guard is asserted on a channel that reaches it, and the "transits
  both" contract is proven on two shared literals (`Unknown rule type …`, `Unknown card …`).
- **Import vs admin wording.** Import row errors are `IllegalArgumentException` with different
  text (lower-case `unknown rule type '<t>'`, `Card '<n>' not found.`) from their admin twins
  (`Unknown rule type …`, `Unknown card '<n>'`) — cross-checked separately in Q-D0.
- **Notices via the un-followed 303.** Following the redirect drops the session cookie, so
  every notice is read from the `Location` header.
- **State hygiene.** Every guard drives a refused (no-write) path; the few helper rows are
  isolated `ZZZ_Q_*` codes / minted `29900019*` cards purged in `finally`; time is frozen at
  2026-08-22 so the "past / not-yet-in-force" rule guards are deterministic; ticket refs carry
  a per-run suffix.

## Justified residues

- **No `[P]` scenarios** in group Q — nothing `@Disabled`.
- **`The new window would overlap another instance of code '<c>'`** (`AdminService.updateEndDate`)
  is **structurally unreachable**: `FidelityRule.code` is `@Column(unique = true)`, so a code
  never carries a second instance for the overlap loop to find. The branch cannot fire without
  a `src/main` change (out of scope). Documented in `qC_ruleAdministrationGuards`'s Javadoc;
  every other rule guard is asserted.
- **`Empty database detected — loading the 2026 program seed (§24.4)`** (`SeedLoader`) is the
  prod seed path and never fires under the DataInitializer wipe+reload of the dev/test boot.
  `qF_contractualLogLines` asserts its **absence** (proving the DataInitializer path was taken)
  rather than flakily awaiting a line that only appears in a prod-like harness.
