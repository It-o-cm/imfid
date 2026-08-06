# CLAUDE.md — imfid

Application **fidélité** de l'écosystème Intermarché sandbox. Quarkus, port **8060**,
package racine **`com.intermarche.fidelity`**, PostgreSQL en production, H2 en tests.

## Référence normative

`docs/programme-fidelite-intermarche.pdf` — **version 1.16, spécification close**.
Toute décision y est déjà prise : ne jamais rediscuter un invariant (I1-I11, §19) ni une
décision d'interview (§20). En cas d'ambiguïté : citer le § concerné et poser la question,
ne jamais inventer. Les autres artefacts de `docs/` font partie du contrat :

- `guide-ihm-administration.pdf` — conformité obligatoire de l'IHM d'admin (§21) ;
- `valuation-request.json` / `valuation-response.json` / `valuation-output-reference.md`
  — le couple de référence de l'entrée `/earn` (§22) et sa sémantique.

`docs/` est en lecture seule pour toutes les sessions.

## Écosystème

Trois applications : **impos** (caisse), **imvaluation** (`:8090`, prix + offres
commerciales), **imfid** (`:8060`, fidélité). Frontière (§34.1) : tout ce qui réduit le
prix est commercial (imvaluation) ; tout ce qui crédite la cagnotte est fidélité (imfid) —
imfid ne réimplémente jamais une remise. imfid n'expose au POS qu'une **API REST** (§27) ;
sa seule IHM est l'administration.

**Patrons à imiter d'imvaluation** (mêmes noms, mêmes réflexes) :

- `BaseEntity` : version optimiste, audit, checksum par lifecycle JPA ;
- factories CDI + spécification **JSON validée par JSON Schema** par type de règle,
  registre de schémas partagé moteur/IHM (patron `OfferSchemaRegistry`) ;
- imports CSV en masse : optimisation par checksum, fallback étagé 1000→100→10→1 ;
- GraphQL par **codes métier**, jamais par IDs ;
- `DateTimeProvider` : aucune lecture directe de l'horloge système (§24.6).

## Conventions de code

- Code et commentaires **en anglais**. **Javadoc sur toute méthode, sans exception**
  (getters, setters, constructeurs, méthodes privées, callbacks, overrides).
- Pas de package à classe unique ; pas d'hyperdécoupage.
- Logger JBoss — jamais `System.out`. Jamais de retour `null` pour une collection
  (`List.of()`). Gardes de nullité sur tout parseur. Division protégée dans tout
  scoring ou prorata (§31.2).
- `BigDecimal` pour tout montant : échelle 2, HALF_UP, euro implicite (§30.5).

## Rappels moteur (détail dans la spec)

- L'earn est une **phase 2** évaluée sur le panier valorisé transmis par le POS (§15) ;
  assiette **nette**, non-cumul par **prédicat de consommation** (I1, I2).
- `POST /earn` est une **lecture** : zéro effet de bord (§30.2).
- Le crédit se fait à l'ingestion des événements fiscaux : **le recalcul fait foi**
  (§26.1), idempotence par clé naturelle (I8), toute écriture de compte **sous verrou
  par carte** (§30.1).
- Décagnottage : **réservation à bail puis confirmation** (I11) — jamais de débit direct.
- `earnYear` = date fiscale au **fuseau du programme** (§30.3, §25.1).

## Tests

- Couverture **par jambe** de chaque garde composée : `a || b || c` = un cas par jambe
  vraie, nullités incluses (§29.6).
- Groupes du kit (§24.4, §26.5) : earn (une classe par mécanique + non-cumul +
  plafonds), réservations/burn, comptes/batchs, imports, GraphQL, admin, ingestion.
- Fixture de référence : le couple `docs/valuation-*.json` ; l'**Annexe B** (oracle earn
  sur 4 contextes) est produite par le moteur réel — première tâche après le seed.

## Build et sessions

- `mvn quarkus:dev` (port 8060) ; commits conventionnels, messages en anglais.
- Ordre de construction : domaine → imports → SPI + appliers (7 mécaniques, §12) →
  moteur `/earn` → comptes/réservations/ingestion → GraphQL → IHM admin → Simulateur →
  batchs → kit de tests → Annexe B.
- Livrer uniquement les fichiers modifiés ; jamais de modification hors du périmètre
  demandé — toute observation hors périmètre se signale en une ligne, sans l'appliquer.
