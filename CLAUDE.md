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

## Tests unitaires (campagne par classe)

Bench repris d'imvaluation : **JUnit 5 + Mockito**, une classe de test complète et
compilable par classe de production.

**Architecture — NON NÉGOCIABLE.**

- **Unitaire pur** : jamais `@QuarkusTest`, jamais H2, jamais de boot de l'application
  pour un test de classe. On mocke tous les collaborateurs.
- Les entités Panache **ne sont pas** enrichies bytecode sous `mvn test` nu : les finders
  statiques retombent sur `PanacheEntityBase`. On les mocke avec
  `Mockito.mockStatic(PanacheEntityBase.class)` et on neutralise `persist()` avec
  `Mockito.mockConstruction(<Entity>.class)`. Les mocks statiques vont en
  `try-with-resources`.
- Chaque test est **isolé** : aucun état partagé, aucune dépendance d'ordre, assertions
  sur des valeurs absolues attendues.

**Le temps passe TOUJOURS par `DateTimeProvider` (§24.6) — règle imfid.** Aucun test ne
lit `now()`, `LocalDate.now()`, `Instant.now()`, `Clock`, ni aucune horloge réelle : on
**injecte un `DateTimeProvider` mocké et on fixe l'instant**. Toute logique temporelle est
donc testée sur un temps figé et déterministe — fenêtres de règles, visites, baux de
décagnottage (I11), et surtout la **date fiscale `earnYear`** au fuseau du programme
(§30.3, §25.1). Le franchissement d'une frontière (veille/lendemain d'une fenêtre,
31 déc./1ᵉʳ jan. fiscal, expiration d'un bail) se teste par **les deux instants** encadrant
la borne, jamais par l'heure du jour où tourne la campagne.

**Couverture des gardes — exigence §29.**

- **Les DEUX BRAS** de chaque garde et de chaque ternaire (null **et** non-null) sont
  couverts systématiquement, sans attendre une relecture JaCoCo.
- **CHAQUE JAMBE** de chaque garde composée : `a || b || c` = un cas par jambe rendue
  vraie (les autres fausses), nullités incluses (§29.6). Idem pour les `&&`.
- Tout **`BigDecimal`** se compare par `compareTo` (jamais `equals` : `2.0` ≠ `2.00`),
  échelle 2 / HALF_UP, à l'euro près.
- Toute **division / prorata** protégé (§31.2) : un cas dénominateur zéro **et** un cas
  dénominateur non nul.

**Oracle de couverture.** 100 % de couverture de **branches** JaCoCo sur la classe cible,
ou un résidu justifié ligne à ligne dans le rapport. On rapporte toujours le **compte de
branches (n/n)**, pas seulement le pourcentage.

**Style.** Code et commentaires en anglais ; **Javadoc sur toute méthode** (tests et
helpers privés inclus) ; assertions `org.junit.jupiter.api.Assertions` uniquement (jamais
AssertJ) ; pas de ligne vide dans un corps de méthode.

**Périmètre — STRICT.** Ne jamais modifier `src/main`. Un bug ou un obstacle à la
testabilité s'arrête et se signale en une ligne. Ne toucher que la classe de test générée.

**Appliers earn — LOGIQUE PURE, cœur du moteur.** Les 7 appliers (§12) sont de la
computation pure : les tester **sans** mocker Panache quand c'est possible — construire les
paniers valorisés / spécifications en mémoire et asserter l'arithmétique directement.
Viser la couverture au centime : quantités fractionnaires, arrondis, résidus de centime,
plafonds, prédicat de non-cumul (I1, I2).

**Workflow par classe.**
1. Lire la classe cible (et seulement le nécessaire).
2. Énumérer toutes les branches **avant** d'écrire.
3. Écrire/compléter la classe de test → `mvn -q -Dtest=<TestClass> -DskipITs test`
   jusqu'au vert.
4. `mvn -q -Dtest=<TestClass> -DskipITs verify` → lire le rapport JaCoCo de la classe →
   combler chaque branche manquante.
5. **Rapport** : compte de branches n/n, couverture %, fichiers lus, itérations, résidu
   justifié.

## Build et sessions

- `mvn quarkus:dev` (port 8060) ; commits conventionnels, messages en anglais.
- Ordre de construction : domaine → imports → SPI + appliers (7 mécaniques, §12) →
  moteur `/earn` → comptes/réservations/ingestion → GraphQL → IHM admin → Simulateur →
  batchs → kit de tests → Annexe B.
- Livrer uniquement les fichiers modifiés ; jamais de modification hors du périmètre
  demandé — toute observation hors périmètre se signale en une ligne, sans l'appliquer.
