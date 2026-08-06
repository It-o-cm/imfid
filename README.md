# imfid — Intermarché loyalty application

`imfid` is the loyalty pillar of the Intermarché sandbox ecosystem, alongside
**impos** (the POS / checkout) and **imvaluation** (`:8090`, pricing + commercial
offers). It runs on port **8060**, root package `com.intermarche.fidelity`,
PostgreSQL in production and H2 in tests.

It is modelled on imvaluation — same Quarkus/Panache patterns, same
administration mechanisms (bulk CSV imports, GraphQL by business codes, schema
registry, `DateTimeProvider`). Its **only** human interface is administration;
towards the POS it exposes a **REST API** only.

The normative reference is `docs/programme-fidelite-intermarche.pdf` (spec
**v1.16, closed**), together with `docs/guide-ihm-administration.pdf` and the
`docs/valuation-*` reference pair. `docs/` is read-only.

## Business model — cagnottage in euros

Intermarché does **not** run a points scheme. Advantages are expressed directly
in euros and credited to a **cagnotte** (pot) attached to the loyalty card:
1 € earned = 1 € of in-store discount, no conversion rate. The card is free,
nominative, one per person, valid across all channels.

imfid computes and holds the loyalty side of this: the earn rules, the cards,
the accounts and their movements. It never re-implements a discount — anything
that **reduces the price** is commercial (imvaluation); anything that **credits
the cagnotte** is loyalty (imfid) (§34.1).

### Two-phase evaluation (§15)

Earn is a **phase 2** evaluated on the already-valued basket. The POS
orchestrates:

1. **Phase 1 — commercial valuation** (imvaluation, `POST /valuation`): resolves
   prices at `priceDate`, applies commercial offers, produces a valued basket
   where each line carries its net amount and the offers that consumed it.
2. **Phase 2 — earn evaluation** (imfid, `POST /earn`): the POS forwards the
   `/valuation` request **and** response untouched; imfid derives baskets and
   predicates (§22), joins the card context and the rules in force, and returns
   the earn block.

`POST /earn` is a **pure read** — zero side effect (§30.2). Credit happens later,
at fiscal-event **ingestion**, where the recompute is authoritative (§26.1),
idempotent by natural key (I8), every account write under a **per-card lock**
(§30.1). Decagnottage (burn) is **reservation-then-confirmation** via a lease,
never a direct debit (I11).

## The 7 rule types (mechanics, §12)

Each rule is a `FidelityRule` row: a common backbone (unique code, `type` =
factory code, ticket label, `validFrom`/`validTo`, `priority`, `exclusive`,
nullable `monthlyCapPerCard`, `active`) plus a JSON `specification` validated by
the JSON Schema of its type's factory. The seven appliers below cover the whole
2026 programme; the framework is open — a new mechanic is **a factory + an
applier + a schema**, with no engine change and no schema migration (I10).

| Rule type (`type`) | Covers | Key administrable parameters |
|---|---|---|
| `BRAND_TIERED_EARN` | Everyday-brands base (5 % / 10 %) | Brand set; N eligible items (=3); base rate; boosted rate; visit threshold (4); promo-line exclusion |
| `CALENDAR_FAMILY_EARN` | Weekend fruit & veg (10 %) | Included families; exclusions (dried, processed, frozen…); active weekdays (Sat, Sun); rate |
| `COMMUNITY_EARN` | Communities (Babies, Large Families, Small Budgets, Students) | Community code; basis (brands / families / whole store); exclusions; rate; monthly cap (30/20 €) |
| `MONTHLY_DATE_EARN` | 40 % Labell feminine hygiene on the 28th (Students) | Day of month (28); basis; rate; monthly cap (10 €); required community |
| `CHALLENGE_EARN` | "Mes défis gagnants" | Basis (brand); tiers (spend € → gain €); period; mission-required flag; per-card activation |
| `ECOUPON_EARN` | Weekly e-coupons (≥ 20 %) | Aisle/family; rate; window (week); per-card activation flag |
| `PROGRAM_EXCLUSION` | Programme-wide exclusions (gift cards, books, press, gas, fuel) | Include/exclude scopes (brands, families, EANs); validity window |

`PROGRAM_EXCLUSION` runs **before** all others: its scopes are removed from every
earn basis and ground the burnable base (§22.3). A basket is valued with the
rules in force at its `priceDate`; a programme change = close the current rule
(`validTo`) + open a new instance — rules are never modified retroactively (§13).

## POS-facing REST surface (§27)

- `POST /earn` — earn projection on the valued basket (pure read).
- `GET /accounts/{card}` — status, balance, monthly visits, cap accruals.
- `GET /accounts/{card}/movements` — movement history.
- `POST /burn/reservations` — lease a decagnottage (available balance +
  once-per-day rule); `POST /burn/reservations/{id}/confirm` (fiscal, idempotent);
  `DELETE /burn/reservations/{id}` (release); re-POST renews the lease.
- Fiscal credit-event ingestion (idempotent POS outbox).
- `GET /q/health` — SmallRye Health probe; impos reads it to detect the degraded
  mode (earn deferred, burn refused).

Administration (rules, communities, accounts, program settings) is served by the
**GraphQL** API (by business codes) and the **Qute** admin IHM; there is no other
human interface.

## Data model (§13, §14)

- **Rules** — `FidelityRule`, `FidelityCommunity`, `FidelityProgramSetting`
  (global cap 400 €/month, lease TTL, program time zone `Europe/Paris`).
- **Accounts** — `FidelityAccount` (`cardNumber` = POS `customerCode`; status
  `ACTIVE` / `PENDING_ACTIVATION` / `RESILIATED`), `FidelityMembership`,
  `FidelityMovement` (signed, typed `EARN` / `REFUND_CREDIT` / `ADJUSTMENT` /
  `BURN` / `RETURN_DEBIT` / `EXPIRY` / `PURGE` / `ACTIVATION_VOID` / `TRANSFER`,
  with `earnYear` for the 1st-March expiry), `FidelityActivation`.
- Balance is the **sum of movements**; expiry consumes FIFO by `earnYear`.
- imfid keeps its **own** product reference (EAN → brand, families), fed by the
  same CSV imports.

All amounts are `BigDecimal`, scale 2, HALF_UP, implicit euro (§30.5).

## Project layout

```
src/main/java/com/intermarche/fidelity/
  domain/        entities (BaseEntity pattern) + product reference
    util/        DateTimeProvider (no direct system-clock read, §24.6)
  imports/       bulk CSV imports (checksum, staged fallback)
  rule/          earn SPI: factories, appliers (7 mechanics), schema registry
  earn/          the /earn engine (phase 2 on the valued basket)
  account/       accounts, reservations/burn, ingestion, lifecycle batches
  graphql/       administration GraphQL (by business codes)
  ui/            admin IHM (Qute) + earn Simulator
  security/      DB-backed admin accounts, form + basic auth
```

Build order (per `CLAUDE.md`): domain → imports → SPI + appliers → `/earn`
engine → accounts/reservations/ingestion → GraphQL → admin IHM → Simulator →
batches → test kit → Annexe B.

## Prerequisites

- **JDK 21** (`maven.compiler.release=21`).
- **Maven 3.9+** — or the bundled wrapper (`./mvnw`).
- **Quarkus platform 3.30.6** (same as imvaluation; pulled from Maven Central).
- **PostgreSQL** for production only. In dev and test the app runs on an
  in-memory H2 with no external service.
- Production reads its datasource from the environment: `IMFID_DB_URL`,
  `IMFID_DB_USERNAME`, `IMFID_DB_PASSWORD`.

## Running

```bash
./mvnw quarkus:dev        # dev mode on :8060, H2 in-memory
curl http://localhost:8060/q/health

./mvnw verify             # unit + integration suites
./mvnw clean package      # build
```

## Conventions

Code and comments in **English**; **Javadoc on every method, without
exception**. JBoss logger only (never `System.out`); never return `null` for a
collection (`List.of()`); protected division in any scoring or proration
(§31.2). No single-class package, no over-decomposition. See `CLAUDE.md` for the
full contract.
