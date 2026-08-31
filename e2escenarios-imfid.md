# Catalogue des scénarios e2e — imfid (service de fidélité)

Couche complémentaire des tests unitaires (BatchServiceTest…) : ici l'application
réelle démarre sur **:8060**, la base H2 **en mémoire** vit (`drop-and-create`), et —
différence majeure avec imvaluation — le jeu de données dev/test est **reconstruit à
chaque démarrage** par le `DataInitializer` (wipe + reload programmatique) : jamais de
« catalogue vide » en dev, jamais besoin de re-seeder. Monde seedé : 46 produits
(33 EAN miroirs `3300000000001`–`033` + 13 d'enrichissement `34…`), 6 familles,
4 communautés, 11 règles 2026, 9 cartes `299…` (tous les états), 22 mouvements,
2 passages de batch, 4 paramètres de programme. Comptes d'amorçage (`SecurityBootstrap`,
table `app_users` vide) : `admin`/`admin-password` (fid-admin, e-mail `admin@imfid.local`)
et `pos`/`pos-password` (pos, **sans e-mail** — volontaire). Le `SeedLoader` CSV ne
tourne **qu'en prod** sur base vide (exclu de dev/test par profil).

Infrastructure par groupe : **[D]** = profil dev/test (défaut) ; **[P]** = prod-like
(`IMFID_DB_*`, `IMFID_SESSION_KEY`, `IMFID_ADMIN_PASSWORD`, `IMFID_POS_PASSWORD`,
`IMFID_ADMIN_EMAIL`, SMTP `IMFID_SMTP_*` exigées) ; **[W]** = navigateur réel requis
(JS : formulaire schema-driven, verrouillage consultation, drag & drop, code-barres
EAN-13, workbench) ; sans marque = pur HTTP (RestAssured, Basic `pos` pour `/api/*`,
Basic `admin` pour imports/GraphQL, session formulaire pour `/ui/*`).

Convention : scénario = parcours → attendus vérifiables (HTTP, JSON, base, log, écran).
Les libellés cités sont les **textes littéraux du code**. Quatre garde-fous
transverses : **(1)** le jeu de données est **daté relatif** (visites de la carte riche
à J−2/J−4/J−6, fenêtres e-coupon/défi = mois courant) → les scénarios « boost 4ᵉ
visite » sont **invalides les 1er–6 du mois** (visites glissées sur le mois précédent) ;
**(2)** le recalcul d'ingestion **fait foi** — un écart avec la projection est un
warning, jamais un rejet ; **(3)** tout événement fiscal est un upsert idempotent —
rejouer est toujours sûr, et c'est un piège pour les tests (un `ticketRef` déjà ingéré
est absorbé sans bruit : suffixer chaque run) ; **(4)** l'UI d'admin est en français
mais **toutes les notices de redirection et messages de garde sont en anglais**
(`Rule X created`, `An adjustment reason is mandatory (§32.1)`) — contrat de fait à
asserter tel quel.

## A. Démarrage & amorçage [D]

- **A1 Boot dev nominal** : démarrage → log
  `Dev/test dataset loaded: 46 products, 11 rules, 4 communities, 9 accounts, 22 movements` ;
  log `Earn rule registry started: 8 schemas registered …` (registre AVANT le seed :
  observeurs `@Priority` 2500 < 2700) ; log
  `Bootstrap users created: 'pos' (pos), 'admin' (fid-admin)`.
- **A2 Amnésie inversée** : créer une règle + une carte à la main → redémarrer → tout a
  disparu ET le monde seedé est **revenu** (wipe + reload, pas de détection « base
  vide ») ; en une vie de JVM, `SecurityBootstrap` ne re-crée jamais (garde
  `AppUser.count() > 0`).
- **A3 SeedLoader prod-only** [P] : en dev/test, aucun log `Empty database detected —
  loading the 2026 program seed (§24.4)` n'apparaît jamais (bean exclu du profil) ; en
  prod-like sur base vide → les 9 CSV chargés dans l'ordre 01→09, log
  `Seed /seed/01-products.csv -> <report>` ; sur base non vide → return silencieux.
- **A4 Démarrage prod incomplet** [P] : sans `IMFID_DB_URL`/`IMFID_SESSION_KEY`/
  mots de passe bootstrap → échec de démarrage, aucun repli silencieux.
- **A5 Les 9 cartes du monde** : vérifier en base les états exacts — `2990000000019`
  ACTIVE 56.70 ; `…026` ACTIVE 18.50 (membre BABIES) ; `…033` ACTIVE 24.00 (STUDENTS
  actif + SMALL_BUDGETS expirée) ; `…040` PENDING_ACTIVATION 5.00 ; `…057` ACTIVE
  **−1.70** (I7) ; `…064` RESILIATED 0.00 `transferredToCard=…071` ; `…071` ACTIVE
  15.00 ; `…088` ACTIVE 30.00 + réservation ACTIVE 8.00 (bail now+30 min) ; `…095`
  RESILIATED 0.00 (ACTIVATION_VOID). Tous les numéros sont des EAN-13 valides
  (clé de contrôle) préfixés `299`.
- **A6 Sensibilité calendaire du seed** : lancé un 1er–6 du mois → moins de 3 visites
  au compteur de `…019` (le boost 10 % ne s'arme pas) ; fenêtres `ECOUPON_DEMO`/
  `CHALLENGE_DEMO` = `[1er du mois, 1er du mois suivant)` recalées à chaque boot.
  Scénario documentaire : conditionner les assertions de boost à `dayOfMonth ≥ 7`.

## B. Authentification & mot de passe oublié

- **B1 Chaîne nominale** : `GET /` → 303 `/ui/cards` → (anonyme) redirection
  `/ui/login` → `POST /j_security_check` (`j_username`/`j_password`) → 302
  **`/ui/cards`** (landing-page — fut `/ui`, corrigé : re-login après logout ne doit
  plus produire de 404). Cookie `quarkus-credential` posé.
- **B2 Login refusé** : → `/ui/login?error=true` → bandeau `Identifiants invalides.`
  (affiché pour toute présence du paramètre `error`, contrairement à imvaluation qui
  exige `error=true` strict — asymétrie à figer).
- **B3 Logout** : `POST /ui/logout` → cookie vidé (maxAge 0, path /) → 303
  `/ui/login`. Pas de notice « signed out » (différence avec imvaluation).
- **B4 Chemins publics — la liste ET le trou** : `permit` explicite sur `/q/health`,
  `/ui/login`, `/ui/forgot`, `/ui/reset`, `/ui/base.css`. ⚠ MAIS les chemins **non
  couverts par une policy sont permis par défaut** : `/ui/auth.css`, `/ui/fidelity.js`,
  `/ui/fidelity.css` se servent anonymement (c'est ce qui rend la page de login
  stylée). Test canari : `GET /ui/fidelity.js` sans session → 200 — inverser
  l'assertion si une policy catch-all est ajoutée un jour.
- **B5 Basic forcé** : `/api/*`, `/graphql` → sans `Authorization` : **401 challenge**,
  jamais de redirection HTML ; les imports (`/products/import`, `/product-families/import`,
  `/fidelity/*`) exigent `fid-admin` (policy nommée) → `pos` dessus : **403**.
- **B6 Rôles étanches** : `pos` sur `/ui/*` → refus ; `admin` sur `/api/earn` : la
  policy de chemin est `authenticated` mais `@RolesAllowed(pos)` sur la ressource →
  **403**. Croiser la matrice complète (2 comptes × 4 surfaces).
- **B7 Compte désactivé peut se connecter** : passer `active=false` en base sur un
  compte → le login **réussit** (`quarkus-security-jpa` ne lit pas le drapeau — même
  bibliothèque, même symptôme qu'imvaluation B6). Test NÉGATIF documentaire.
- **B8 `mustChangePassword` inerte** : le champ existe, le reset le repose à `false`,
  mais **aucun filtre ne l'impose** dans imfid (pas d'équivalent du mode forcé
  d'imvaluation). Test négatif gravant : un compte flaggé navigue librement.
- **B9 Mot de passe oublié — demande** : `/ui/forgot`, saisie e-mail → POST → 303
  `/ui/forgot?sent=true` → bandeau `Si un compte correspond à cette adresse, un lien de
  réinitialisation vient de lui être envoyé. Il est valable 30 minutes et à usage
  unique.` — **identique** que l'adresse existe (`admin@imfid.local`), n'existe pas, ou
  soit vide (anti-énumération). Adresse connue → log
  `Password reset link sent to the address of account 'admin'` + mail **mocké dans le
  log** (dev) contenant `/ui/reset?token=<64 hex>` ; inconnue → log DEBUG seulement.
- **B10 Reset — les refus dans l'ordre** : confirmation ≠ →
  `Les deux saisies ne correspondent pas.` ; jeton absent/inconnu/consommé/expiré →
  `Ce lien de réinitialisation est invalide, déjà utilisé ou expiré. Refaites une
  demande.` ; ⚠ politique de mot de passe **en anglais dans une page française** :
  `The password is mandatory.` / `The password must be at least 8 characters long.`
  (messages de `AppUser.validatePassword` affichés bruts — contrat de fait). Succès →
  303 `/ui/login?reset=true` → bandeau vert `Votre mot de passe a été modifié. Vous
  pouvez vous connecter.`
- **B11 Hygiène des jetons** : en base `password_reset_tokens.token_hash` = 64 hex
  (SHA-256, jamais le brut) ; re-demander → les jetons pendants du compte sont
  **supprimés** (un seul lien vivant) ; consommer → `used_at` posé, seconde
  consommation refusée ; TTL piloté par `imfid.reset.token-ttl-minutes` (30).
- **B12 Reset du compte machine impossible** : demander pour `pos` (sans e-mail) →
  même message neutre, rien d'envoyé, rien en base. À figer : c'est le contrat
  « machines sans self-service ».
- **B13 Sonde de santé — le contrat d'impos** : `GET /q/health` **sans
  authentification** → 200 `{"status":"UP", …}` ; imfid arrêté → connexion refusée.
  C'est LE discriminant du mode dégradé côté caisse (earn masqué, burn refusé,
  événements différés — spec d'intégration §2.2) : le scénario doit exister des deux
  côtés de la couture.

## C. API POS `POST /api/earn` — la projection

- **C1 Lecture pure** : après tout `/earn`, prouver **zéro écriture** : comptes,
  mouvements, `earn_traces`, visites inchangés (le contraste avec F6 est le contrat).
- **C2 400** : corps sans `valuationResponse` →
  `{"error":"Missing valuation response in /earn request"}`.
- **C3 422 réconciliation** : totaux incohérents (> 1 centime) → 422
  `{"error":"Incoherent totals: totalPrice <x> …"}` ; Σ tranches ≠ montant d'offre →
  `{"error":"Offer '<type>': Σ items <x> …"}` — les fins de message sont **à calibrer
  une fois** puis à figer ; log WARN `Rejected /earn: reconciliation failed: <msg>`.
- **C4 Carte absente ou inconnue** : `customerCode` absent, vide, ou `9999999999999` →
  **200** `{"total":0, "entries":[], …}` — jamais 404, jamais de création de compte
  (§20).
- **C5 Évaluation datée** : le même panier avec `createdAt` un samedi vs un lundi →
  FL_WEEKEND s'allume/s'éteint ; avec `createdAt` avant le 18/05/2026 →
  `SOCLE_4_MARQUES` répond à la place de `SOCLE_5_MARQUES` (fenêtres versionnées) ;
  `createdAt` absent → repli sur l'horloge programme.
- **C6 UNKNOWN_EAN** : EAN hors référentiel → warning littéral
  `EAN <ean> unknown to the imfid product reference; line kept out of every earn
  assiette but inside the burnable base (§25.4)` — et prouver les deux moitiés :
  la ligne ne cagnotte pas MAIS reste dans `burnableBase`.
- **C7 I2 — non-cumul, le piège canonique** : café `…004` + biscuits `…013` sur `0101`
  → le bundle `PROMO_COFFEE_PACK` d'imvaluation consomme les deux lignes → earn
  **0, sans warning** — c'est un vert. Le panier de référence qui cagnotte : lait
  `…002` + eau `…007` + yaourt `…010` (3 unités socle, aucune offre) → ≈ 5 % du net.
- **C8 Assiette socle en unités** : 1 café + 1 biscuits (branchés socle) = **2**
  articles < 3 → 0 ; 2 cafés + 1 biscuits = 3 → earn (le compte multiplie la quantité
  d'un produit UNIT ; une pesée compte 1).
- **C9 Boost 4ᵉ visite** : carte `…019` (3 visites posées) → le taux passe à 10 %
  **sur l'appel même** (la visite courante compte si le jour n'est pas déjà tracé) ;
  carte vierge → 5 %. [croiser A6]
- **C10 Les trois étages de plafonds** : saturer `COMMUNITY_STUDENTS` (cap 20 €) →
  `capsApplied` porte `{"scope":"COMMUNITY:STUDENTS","capAmount":20.00,"truncatedBy":…}` ;
  scopes fermés `RULE:<code>` | `COMMUNITY:<code>` | `GLOBAL` (400 €, paramètre
  `program.globalMonthlyCap`).
- **C11 Une fois par mois** : le 28 (date forcée), carte `…033` : 1er appel → 40 %
  hygiène Labell ; ingérer le ticket ; 2ᵉ appel le même 28 → `STUDENTS_HYGIENE_28`
  muette (`hasEarnedThisPeriod`).
- **C12 Activations** : e-coupon/défi sans activation → rien ; avec (carte `…019`) →
  earn ; défi : base cumulée 4.50 € seedée → +0.60 € d'achats Lay's franchit le palier
  5 € → gain **différentiel** 1.00 ; mission requise au dernier palier
  (`missionDone=true` seedé sur `…019`).
- **C13 Spécifications dégradées tolérées** : règle persistée en direct avec
  `activeDays: ["MONDAY", null, ""]` → le null JSON et le blanc sont ignorés (pas de
  MONDAY fantôme pour `""` — la garde `isBlank` est sémantique) ; `tiers: [1, null,
  {...}]` → seuls les objets comptent. Aucune 500 : dégradation silencieuse (§31.2).
- **C14 Priorité décroissante prouvée** : deux règles vivantes sur la même marque,
  priorités 90 et 100, la 100 **exclusive** → `entries[]` dans l'ordre 100 puis 90,
  et la 90 ne reçoit **rien** (lignes consommées). Inverser les priorités → l'earn
  bascule. C'est l'arbitrage data-borne du non-cumul entre règles (I2), distinct du
  non-cumul avec les promos (C7).
- **C15 Exclusive = consomme, non-exclusive = partage** : même montage avec la 100
  **non exclusive** → les DEUX règles créditent la même assiette (cumul volontaire).
  Le couple C14/C15 fige la sémantique du drapeau.
- **C16 Contrat des entrées** : chaque entrée porte `ruleCode` stable, `label`
  imprimable (la seule donnée de règle qui voyage, §18), `amount` post-plafonds,
  `baseAmount`, et `lineIds[]` = exactement les lignes de l'assiette (jamais vide sur
  une entrée non nulle) — c'est la matière première du débit de retour (§29.4).
- **C17 L'exclusion CGU ensemence la consommation** : une ligne carte-cadeau
  (`3400000000060`, famille CARTES_CADEAUX) → absente de toutes les assiettes ET de
  `burnableBase` **avant** l'évaluation des producteurs (le moteur seede son ensemble
  `consumed` avec les lignes d'exclusion) ; la règle `CGU_EXCLUSION` elle-même
  n'apparaît jamais dans `entries[]` (un filtre, pas un producteur).

## D. Réservations burn (`/api/burn/reservations`)

- **D1 Nominal** : POST `{card, amount, ticketRef}` → **201**
  `{"reservationId":<id>, "expiresAt":<now+900 s>}` (TTL =
  `reservation.leaseTtlSeconds`).
- **D2 Renouvellement** : re-POST même carte + même `ticketRef` → **200**, `expiresAt`
  repoussé ; montant **modifiable** au renouvellement (re-vérifié contre le solde).
- **D3 409** : bail actif sur un AUTRE ticket → 409 corps vide (une carte = une
  caisse).
- **D4 404** : carte inconnue → 404 corps vide.
- **D5 Les trois refus 422** : `{"reason":"INSUFFICIENT_BALANCE"}` (montant >
  disponible) ; `{"reason":"DAILY_RULE"}` (un BURN confirmé existe déjà ce jour
  fiscal) ; `{"reason":"ACCOUNT_STATUS"}` (carte `…040` PENDING_ACTIVATION ou `…095`
  RESILIATED — la pending **cagnotte** mais ne burn pas, à croiser avec C4).
- **D6 Disponible ≠ solde** : carte `…088` (30.00, bail seedé 8.00) → réserver 25.00 →
  `INSUFFICIENT_BALANCE` ; réserver 22.00 → 409 (autre ticket !) — les deux gardes
  dans l'ordre.
- **D7 Confirmation** : POST `/{id}/confirm` `{fiscalDate}` → 200, mouvement BURN
  négatif daté au jour fiscal ; re-confirm → 200 idempotent (un seul BURN) ; id
  inconnu → 404 ; bail expiré → **410**.
- **D8 Rattrapage du 410** : après 410, envoyer le `ticket-closed` avec
  `reservationId` → l'ingestion crée le BURN quand même + warning
  `EXPIRED_LEASE_CONFIRMED` dans la trace (§29.2). LE scénario de couture
  caisse/fidélité.
- **D9 Libération** : DELETE `/{id}` → **204 toujours** (id inconnu compris) ; le
  disponible remonte ; un bail RELEASED ne consomme pas la règle quotidienne.
- **D10 Expiration naturelle** : bail non renouvelé → après `expiresAt`, une nouvelle
  réservation sur un autre ticket passe (201) sans aucune intervention.

## E. Ingestion (`/api/events/*`) — le crédit qui fait foi

- **E1 ticket-closed nominal** : POST → **202** ; mouvements EARN par règle
  (`ruleCode`, `ticketRef`, `earnYear` = année civile de `fiscalDate`) ; balance
  recalculée ; trace `SUCCESS` avec `displayedEarn`/`recalculatedEarn` et payloads
  verbatim.
- **E2 400** : sans `ticketRef` → `{"error":"Missing ticketRef"}` ; retour sans une
  des deux refs → `{"error":"Missing returnTicketRef or originTicketRef"}`.
- **E3 Idempotence — LE piège des campagnes** : rejouer le même `ticket-closed` →
  202, **aucun nouveau mouvement** (clé `ticketRef+type+ruleCode`). Corollaire
  opérationnel gravé dans un message de test du pre-flight : une caisse qui
  renumérote de `C04-000001` à chaque boot voit ses événements absorbés — suffixer
  les refs par run.
- **E4 Le recalcul prévaut** : `displayedEarn` mensonger (2.10 au lieu de 2.05) →
  crédit = 2.05, warning `EARN_MISMATCH` dans la trace ; y compris quand le recalcul
  dit **0** (panier bundle C7) : trace SUCCESS/NO_MOVEMENT, zéro mouvement, zéro
  drame.
- **E5 CARD_MISMATCH** : champ `card` ≠ `valuationRequest.customerCode` → la requête
  fait foi, warning `CARD_MISMATCH`, crédit sur la carte de la requête.
- **E6 RESILIATED_ACCOUNT** : événement sur `…095` → trace header + warning, **aucun
  mouvement**, solde figé.
- **E7 Visite systématique** : ticket porteur de carte qui ne cagnotte rien →
  `earn_traces` header quand même → `monthVisits` +1 (I3). Un ticket **sans** carte :
  tracé sans mouvement.
- **E8 Chaîne de transfert** : événement adressé à `…064` (transférée) → crédité sur
  `…071` (résolution `resolveActive`, la caisse n'a rien à savoir).
- **E9 Retour avant l'origine** : `ticket-return` orphelin → 202, rangé dans
  `pending_returns` (unique par `returnTicketRef`) ; ingérer l'origine → le retour
  **se rejoue seul** ; échec de rejeu → log `Failed to replay held return %s`, jamais
  d'exception sortante.
- **E10 RETURN_DEBIT borné** : retour partiel (1 sur 3) → débit au prorata de la trace
  ligne, cumulé **jamais au-delà de l'earn de la ligne** ; solde négatif accepté
  (carte `…057` en est le témoin permanent).
- **E11 refundToCard** : → mouvement `REFUND_CREDIT`, `ruleCode` null, **hors
  plafonds** (n'apparaît dans aucun cumul `monthlyCaps`), périmable comme l'earn.
- **E12 Warnings en trace** : format persisté `CODE` ou `CODE:<ean>`
  (`UNKNOWN_EAN:3400000099999`) — à asserter sur `earn_trace_warnings`.

## F. API comptes (`GET /api/accounts/…`)

- **F1 Inconnu** : → 404 `{"error":"Unknown card"}`.
- **F2 Résumé** : `…088` → `balance: 30.00`, `availableBalance: 22.00`, statut,
  `monthVisits`, `monthlyCaps[]` (scopes fermés, cumuls EARN seuls — un ADJUSTMENT
  n'y figure jamais), `memberships[]` avec fenêtres.
- **F3 Historique** : tri récent d'abord ; `size=999` → écrêté à 200 ; `page=-5` → 0 ;
  types restitués : les 9 de la nomenclature (`EARN`…`TRANSFER`), `reason` porté par
  les ADJUSTMENT.

## G. Batchs (§16)

- **G1 Expiry dry-run vs exécution** : `DateTimeProvider` figé au 1er mars → dry-run :
  chiffres sans écriture ; exécution : EXPIRY négatif consommé FIFO par `earnYear`,
  borné par le **disponible** (jamais les euros sous bail) ; `BatchRunLog` écrit **à
  l'exécution seulement**.
- **G2 Purge 24 mois** : compte antidaté → purge = solde positif débité + RESILIATED ;
  solde négatif → résilié sans mouvement, reporté 0.
- **G3 Activation void** : PENDING_ACTIVATION > 2 mois → avantages annulés,
  RESILIATED. Le monde seedé en porte déjà la preuve historique (`…095` + BatchRunLog).
- **G4 Écran Programme** : dernier passage de chaque batch affiché ; déclenchement
  manuel [W] → confirm littéral `Exécuter le batch {type} ? Cette opération détruit
  des avantages de façon définitive.` ; type inconnu → notice `Unknown batch '<t>'`.
- **G5 Le scheduler — trois crons littéraux** : expiry `0 0 3 1 3 ?` (le 1er mars à
  03:00, une fois l'an) ; purge `0 0 4 * * ?` et activation void `0 0 5 * * ?` —
  ⚠ **quotidiens** (les seuils 24 mois/2 mois glissent chaque jour, pas de « jour de
  purge » mensuel). Stratégie de test : antidater les données puis appeler le service,
  le cron lui-même se prouve par sa seule expression (test de configuration).
- **G6 API machine des batchs** : `POST /api/batches/{type}?dryRun=…` — ⚠ `dryRun`
  **par défaut `true`** (l'oubli du paramètre simule, ne détruit jamais : le bon
  défaut, à graver) ; rôle `fid-admin` exigé (`pos` → 403) ; type inconnu → 400
  `{"error":"Unknown batch type '<t>'"}` ; réponse = le `BatchResult` JSON (batch,
  dryRun, accountsAffected, totalAmount, lines[]).

## H. UI Cartes

- **H1 Création** : numéro généré préfixe `299` + clé EAN-13, jamais saisi ;
  collisions résolues par marche avant (re-seed → jamais de doublon).
- **H2 Fiche** [W] : code-barres SVG **réellement scannable** (rendu `fidelity.js`,
  `<svg class="ean13" data-ean=…>`) ; compteurs du mois ; réservation active
  affichée ; historique paginé.
- **H3 Ajustement** : motif obligatoire —
  `An adjustment reason is mandatory (§32.1)` ; montant obligatoire ; succès → notice
  `Adjustment posted` ; hors plafonds mais périmable (earnYear = année du geste).
- **H4 Transfert** : confirm `Transférer la carte <n> (solde <s> €) vers une nouvelle
  carte ? L'ancienne carte sera résiliée.` → nouvelle carte générée, TRANSFER out/in
  **préservant les earnYears** (pas de rajeunissement), adhésions et activations
  migrées, visites re-rattachées ; re-transférer la résiliée →
  `A resiliated card cannot be transferred`.
- **H5 Résiliation** : confirm `Résilier la carte <n> (solde <s> €) ? Le solde ne
  bougera plus jamais.` → notice `Card resiliated` ; avec bail actif → refus
  `Refused: the card holds an active burn reservation (§28.1)`.

## I. UI Règles — consultation, édition, fin d'application

- **I1 Liste** : badges `ACTIVE`/`UPCOMING`/`CLOSED`/`INACTIVE` ; codes cliquables ;
  `Éditer` visible sur les seules UPCOMING ; plus **aucun** bouton « Fermer ».
- **I2 Consultation = formulaire gelé** [W] : fiche `SOCLE_5_MARQUES` → mêmes widgets
  que l'édition, tout désactivé, bascule Form/JSON **active** ; ⚠ le verrou est
  ré-appliqué après chaque re-render (retour de bascule JSON → Form) — c'est le point
  fragile à couvrir ; taux affiché 5, JSON stocké 0.05 (« facteur cent »).
- **I3 Création** [W] : formulaire régénéré par type depuis les 8 schémas (les mêmes
  que le moteur) ; erreur serveur → re-rendu **avec notice** (`Invalid specification
  for type '<t>': <violations>`) ; fenêtre chevauchant une instance du même code →
  `Rule window overlaps an existing instance of code '<c>'`.
- **I4 Édition UPCOMING seulement** : `GET /ui/rules/<active>/edit` → 303 fiche +
  `Only a rule not yet in force can be edited; duplicate then close instead (§18)` ;
  sur une UPCOMING → tout modifiable sauf le code ; `validFrom` passé →
  `An edited rule cannot start in the past (§18)`.
- **I5 Fin d'application — l'éditeur** : bloc absent d'une règle CLOSED ; poser une
  date → notice `Rule <c>: end of application set to <date>` ; vider →
  `…cleared (open-ended)` ; date passée →
  `The end of application can never be set in the past (§18)` ; étendre par-dessus la
  version suivante (`SOCLE_4` sur `SOCLE_5`) →
  `The new window would overlap another instance of code '<c>'` ; règle échue →
  `No rule with code '<c>' whose window is still open; an ended rule is frozen —
  version it instead (§18)`.
- **I6 Duplication** : `Dupliquer` → formulaire prérempli, code proposé `<code>_V2`,
  type ET spec repris ; code déjà pris → `A rule with code '<c>' already exists`.

## J. UI Communautés — catalogue & atelier

- **J1 Création** : bouton `Nouvelle communauté` → gardes dans l'ordre :
  `A community with code '<c>' already exists` ; cap négatif →
  `The monthly cap cannot be negative` / `The enrollment cap cannot be negative` ;
  fenêtre incomplète → `A renewal window needs both its start and end months` ; mois
  hors 1–12 → `A renewal month must be between 1 and 12`. Succès → 303 atelier +
  `Community <c> created`.
- **J2 Édition** : code figé (readonly) ; succès `Community <c> updated`.
- **J3 Fermeture aux enrôlements** : confirm `Fermer la communauté <c> aux nouveaux
  enrôlements ? Les membres existants conservent leurs avantages.` → badge `FERMÉ`,
  bandeau explicatif ; enrôler → `Community '<c>' is closed to new enrollments
  (§23.2)` ; dans l'atelier, ajouter une carte non membre →
  `…closed to new enrollments: card <n> cannot be added (§23.2)` mais retirer/modifier
  les existantes passe ; les membres **continuent de cagnotter** (croiser C10) ;
  réouverture → `Community <c> reopened to enrollments`.
- **J4 Atelier = source complète de vérité** [W] : retirer une ligne + `Enregistrer la
  structure` → l'adhésion est **supprimée** en base (deletion by omission) ; carte
  inconnue dans la soumission → ignorée silencieusement ; plafond d'enrôlement dépassé
  → `Enrollment cap reached for community '<c>' (<n>, §28.4)` ; succès →
  `<n> membership(s) saved`.

## K. Imports CSV & écran Imports

- **K1 Mécanique commune** : POST texte brut, pipe, en-tête sauté → 200 rapport
  (`Import finished. Created: <n>, Updated: <m>` en log ; **forme exacte du corps à
  calibrer une fois** puis figer) ; repli étagé sur lot fautif → log WARN
  `Failed to process chunk of size <x> with step <y> …Retrying with step <z>` →
  les saines passent, la fautive isolée ligne à ligne.
- **K2 Idempotence par checksum** : réimport identique → 0 update (vérifier
  `updated_at` en base) ; un champ modifié → 1.
- **K3 Ordre des 9 domaines** : rejouer le pack qualif 01→09 → tout passe ; jouer
  09 avant 05 → lignes en erreur (carte inconnue), rejouer après 05 → OK (rattrapage
  sans état).
- **K4 Drag & drop** [W] : lâcher `04-rules.csv` n'importe où dans le cadre →
  surbrillance `is-dragover`, fichier posé, domaine présélectionné
  `FIDELITY_RULES` ; ⚠ piège d'ordre gravé : `02-product-families.csv` →
  `PRODUCT_FAMILIES` (le motif `famil` est testé AVANT `product`) ; nom inconnu →
  domaine inchangé ; sélection classique → même présélection.
- **K5 Import de visites** : headers `NO_MOVEMENT`, idempotents par `ticketRef` —
  rejouer ne double aucune visite ; c'est le levier officiel pour armer le boost
  4ᵉ visite un 3 du mois.
- **K6 Limites du monde CSV** [P] : le pack qualif ne sait exprimer ni le ledger
  raconté (ADJUSTMENT seuls), ni une réservation active (`…088` y a disponible =
  solde) — écarts assumés avec le monde DataInitializer, à NE PAS asserter en
  prod-like.
- **K7 Le rapport est du JSON VALIDE** : provoquer des erreurs de ligne contenant
  guillemets et retours à la ligne → le corps
  `{"createdCount":n, "updatedCount":m, "errors":["…","…"]}` **se parse** (échappement
  `escapeJson` : `\"`, `\\`, sauts de ligne → espaces). Divergence assumée avec le D3
  d'imvaluation (JSON malformé là-bas) : ici, asserter EN PARSANT.
- **K8 Règles — les deux chemins divergent (canari)** : l'import FIDELITY_RULES
  **valide** contre le registre — type inconnu → `unknown rule type '<t>'`, spec non
  conforme → `specification invalid for type '<t>': …`, `missing rule type`,
  `missing or malformed validFrom (ISO date-time)` en erreurs de ligne ; la
  persistance **directe** (seed, SQL) ne valide pas → une règle difforme peut exister
  en base et le moteur la dégrade (C13). Prouver les deux chemins côte à côte.
- **K9 Ajustements — l'importeur qui écrit le ledger** : chaque ligne crée un
  mouvement ADJUSTMENT **et rafraîchit le solde** ; refus :
  `reason is mandatory for an ADJUSTMENT (§32.1).`, `amount is mandatory.`,
  `movementDate is mandatory.`, carte inconnue → `Card '<n>' …` ; idempotence par
  `reference` (rejouer le pack → zéro double crédit).
- **K10 Comptes — la colonne magique** : `cardNumber` renseigné → upsert ;
  **vide → le numéro est généré** (préfixe programme + séquence paddée + clé EAN-13,
  collisions résolues par marche avant) — l'import est aussi un générateur de cartes ;
  `transferredToCard` posé → la chaîne de transfert existe dès l'import (E8
  fonctionne sur un monde 100 % CSV).
- **K11 Adhésions & activations — dépendances** : `Card '<n>' …` / `Community '<c>'
  …` / `validFrom is mandatory.` ; `ruleCode is mandatory.` / `periodStart is
  mandatory.` ; jouer avant leurs dépendances → erreurs de ligne, rejouer après → OK
  (K3 au niveau ressource).
- **K12 Familles — remplacement** : réimporter `F_L` avec une liste d'EAN amputée →
  les liens absents sont **retirés** (colonne = état complet) ; messages :
  `Product EAN '<e>' …`, `SubFamily code '<c>' …`, auto-référence → `Family '<c>' …`.
  ⚠ vérifier l'état réel en base (le bug E4 d'imvaluation — update compté mais non
  persisté — est à contre-prouver ici).

## L. Simulateur [W]

- **L1 Nominal** : coller la réponse `/valuation` du panier lait+eau+yaourt, carte
  `…019`, date forcée → earn règle par règle, plafonds, base décagnottable — et
  **zéro effet de bord** (C1 s'applique).
- **L2 ⚠ Préremplissage fossile** : le GET précharge la carte **`LOYALTY-DEMO-001`**
  — l'ancien monde CSV. Dans le monde `299…`, simuler tel quel → note
  `Carte inconnue — earn vide (§20)`. Piège gravé jusqu'à correction (une ligne dans
  `SimulatorUiResource`).
- **L3 Erreurs** : JSON invalide → `Couple invalide : <msg>` ; réconciliation →
  `Réconciliation §22.1 échouée : <msg>`.
- **L4 Leviers de démonstration** : date forcée au 28 → l'avantage Étudiantes
  s'allume ; au samedi → F&L ; avant le 18/05/2026 → socle 4 marques. Le simulateur
  est l'oracle des scénarios C — chaque assertion d'earn de ce catalogue peut s'y
  pré-vérifier à la main.

## M. GraphQL d'administration (`/graphql`, Basic fid-admin)

- **M1 Sécurité** : sans auth → 401 ; `pos` → 403 ; requêtes ET mutations = fid-admin
  (pas de piège de rôles croisés à la imvaluation C4 — un seul rôle d'admin).
- **M2 Mutations règles** : `createRule`/`closeRule`/`duplicateRule` → les messages
  d'`AdminException` transitent par `GraphQLException` (`Unknown rule type '<t>' (no
  factory deployed)`, `A rule is closed with a validTo not in the past (§18)`…) — le
  rendu SmallRye exact est **l'inconnue à calibrer au premier scénario** puis à figer.
- **M3 `closeRule` survit à l'UI** : la mutation garde sa sémantique historique
  (validTo, défaut aujourd'hui) alors que l'UI est passée à l'éditeur de fin
  d'application — contrat machine stable, à prouver indépendamment de I5.
- **M4 Les queries** : `communities` restitue chaque communauté **avec son compteur
  de membres actifs** (adhésions dont la fenêtre couvre aujourd'hui — fenêtre expirée
  exclue, à prouver avec `…033`) ; `programSettings` restitue les 4 paramètres ; les
  queries règles/comptes → inventaire exact des champs exposés à figer au premier run
  (introspection) puis à asserter.
- **M5 Écran Programme — gardes de saisie** : les 4 paramètres affichent leurs
  valeurs par défaut comme placeholders (`400.00`, `900`, `Europe/Paris`, `299`) ;
  enregistrer → notice `Setting <clé> saved` ; le résultat d'un batch manuel s'affiche
  (fragments `Exécution : … compte(s) … € sur …` — littéral complet à calibrer une
  fois). Test négatif : aucune validation de type côté écran (un cap non numérique
  retombe sur le repli de lecture `getDecimal` — la robustesse est à la lecture, pas
  à l'écriture, §31.2).

## N. Coutures transverses

- **N1 Solde = Σ mouvements, partout** : après CHAQUE scénario écrivant (E, G, H) :
  `balance` recalculée == somme signée des mouvements — l'invariant §14 est
  l'assertion de clôture universelle de ce catalogue.
- **N2 Pseudonymat** : le flux ticket reste pseudonyme — assertions d'absence de clé
  nominative sur le résumé de compte et l'historique des mouvements ; sur la fiche
  carte, l'identité n'apparaît que dans le bloc « Porteur » de l'annuaire local
  (repli sans CRM), qui affiche le porteur seedé de la carte (§33.3).
- **N3 Concurrence par carte** : deux réservations simultanées sur la même carte
  (tickets ≠) → exactement une 201 + une 409 (verrou `SELECT FOR UPDATE`, I11) ;
  deux `ticket-closed` concurrents même carte → deux crédits, jamais de perte
  (sérialisation §30.1).
- **N4 Nomenclatures fermées** : warnings (5), motifs de refus (3), types de
  mouvement (9), scopes de plafond (3) — tout code inattendu dans une réponse est un
  échec de test (le contrat interdit l'extension silencieuse).
- **N5 Garde vivante assumée** : `prorata` (ValuationReader) conserve ses tests null
  défensifs — exclusion de couverture, PAS un trou de test (décision d'audit gravée ;
  les branches mortes prouvées ont, elles, été retirées).
- **N6 Hygiène inter-scénarios** : la base vit le temps de la JVM — suffixe unique
  par run pour tous les `ticketRef` (E3 !) ; `DateTimeProvider.setFixedDateTime` pour
  les scénarios datés, `clear()` systématique ; le redémarrage reste le reset ultime
  (A2, gratuit ici).

- **N7 Volumétrie** : import produits en masse (50 000 lignes, lots de 1000) →
  budget de temps explicite à fixer, compteurs exacts ; puis un ticket-closed de
  50 lignes distinctes → ingestion bornée dans le temps, un seul jeu de mouvements ;
  et 200 `/earn` consécutifs sur le même panier → lecture pure sans dérive (C1 en
  charge).

## O. Listes, filtres & pagination UI

- **O1 Liste des règles — filtres & tri** : filtre `code` = contains insensible à la
  casse ; filtre `type` = strict ; whitelist de tri `code`/`type`/`validFrom`/
  `priority` — `?sort=specification` (injection) → repli silencieux sur `code`
  (jamais d'erreur, jamais de tri arbitraire) ; `dir=desc` inversé.
- **O2 Clamp de pagination** : `?page=999` → dernière page réelle ; `?page=-3` →
  première ; 25 par page ; textes de pagination du `ListView` (préfixes anglais
  `No <label>…`, `Showing <a>–<b> of <n>`, `— page <i> of <m>` — littéraux complets à
  calibrer une fois puis figer, garde-fou n°4).
- **O3 États vides** : liste des règles filtrée à vide → `Aucune règle ne correspond
  au filtre.` + `Créez une règle, ou importez le domaine FIDELITY_RULES depuis
  l'écran Imports.` ; liste des communautés → `Aucune communauté.` + `Importez le
  domaine FIDELITY_COMMUNITIES depuis l'écran Imports.` (⚠ le hint ignore le bouton
  `Nouvelle communauté` ajouté depuis — incohérence documentaire à figer ou corriger).
- **O4 Communautés — liste plate** : pas de pagination (une page, compteurs de
  membres actifs par ligne, badge `OUVERT`/`FERMÉ`) ; le compteur exclut les
  adhésions expirées (croiser M4).

## Q. Inventaire — messages & surfaces (relevé PAR LE CODE)

### Q-A. API POS (statut + corps)

| Déclencheur | Statut | Littéral |
|---|---|---|
| `/earn` sans réponse de valorisation | 400 | `{"error":"Missing valuation response in /earn request"}` |
| Réconciliation totaux | 422 | `Incoherent totals: totalPrice …` (fin à calibrer) |
| Réconciliation offre | 422 | `Offer '<type>': Σ items …` (fin à calibrer) |
| Carte inconnue `/earn` | 200 | earn vide — jamais une erreur |
| Réservation refusée | 422 | `{"reason":"INSUFFICIENT_BALANCE"}` · `DAILY_RULE` · `ACCOUNT_STATUS` |
| Bail sur autre ticket | 409 | corps vide |
| Confirm bail expiré | 410 | corps vide |
| ticket-closed sans ref | 400 | `{"error":"Missing ticketRef"}` |
| ticket-return refs manquantes | 400 | `{"error":"Missing returnTicketRef or originTicketRef"}` |
| Compte inconnu `/api/accounts` | 404 | `{"error":"Unknown card"}` |
| Batch inconnu `/api/batches` | 400 | `{"error":"Unknown batch type '<t>'"}` |

### Q-B. Warnings (nomenclature fermée, trace + projection)

`UNKNOWN_EAN` (le seul en projection ; texte long §25.4 cité en C6) ·
`EARN_MISMATCH` · `EXPIRED_LEASE_CONFIRMED` · `RESILIATED_ACCOUNT` ·
`CARD_MISMATCH` — en trace, format `CODE` ou `CODE:<ean>`.

### Q-C. Gardes d'administration (AdminException, transitent UI **et** GraphQL)

Règles : `Unknown rule type '<t>' (no factory deployed)` · `Invalid specification for
type '<t>': <violations>` · `validFrom is mandatory` · `Rule window overlaps an
existing instance of code '<c>'` · `No rule with code '<c>'` · `Only a rule not yet in
force can be edited; version it instead (§18)` · `An edited rule cannot start in the
past (§18)` · `No rule with code '<c>' whose window is still open; an ended rule is
frozen — version it instead (§18)` · `The end of application can never be set in the
past (§18)` · `The new window would overlap another instance of code '<c>'` · `No open
rule with code '<c>'` · `A rule is closed with a validTo not in the past (§18)` ·
`A rule with code '<c>' already exists`.
Cartes : `An adjustment reason is mandatory (§32.1)` · `An adjustment amount is
mandatory` · `A resiliated card cannot be transferred` · `Refused: the card holds an
active burn reservation (§28.1)` · `Unknown card '<n>'`.
Communautés : `A community with code '<c>' already exists` · `Unknown community '<c>'` ·
`The monthly cap cannot be negative` · `The enrollment cap cannot be negative` ·
`A renewal window needs both its start and end months` · `A renewal month must be
between 1 and 12` · `Community '<c>' is closed to new enrollments (§23.2)` ·
`Enrollment cap reached for community '<c>' (<n>, §28.4)` · `Membership validFrom is
mandatory` · `Activation periodStart is mandatory`.

### Q-D0. Imports — erreurs de ligne par ressource

| Ressource | Littéraux |
|---|---|
| Règles | `unknown rule type '<t>'` · `specification invalid for type '<t>': …` · `missing rule type` · `missing or malformed validFrom (ISO date-time)` |
| Ajustements | `reason is mandatory for an ADJUSTMENT (§32.1).` · `amount is mandatory.` · `movementDate is mandatory.` · `Card '<n>' …` |
| Adhésions | `Card '<n>' …` · `Community '<c>' …` · `validFrom is mandatory.` |
| Activations | `Card '<n>' …` · `ruleCode is mandatory.` · `periodStart is mandatory.` |
| Visites | `cardNumber is mandatory for a visit (§29.1).` · `fiscalDate is mandatory.` |
| Familles | `Product EAN '<e>' …` · `SubFamily code '<c>' …` · `Family '<c>' …` |
| Mécanique | rapport `{"createdCount":n, "updatedCount":m, "errors":[…]}` — **JSON valide** (échappé) |

### Q-D. Notices UI (anglaises, sur écrans français — contrat de fait)

`Rule <c> created` / `updated` / `duplicated to <c2>` · `Rule <c>: end of application
set to <date>` / `cleared (open-ended)` · `Community <c> created` / `updated` /
`closed to new enrollments` / `reopened to enrollments` · `<n> membership(s) saved` ·
`Adjustment posted` · `Card resiliated` · `Membership saved` · `Activation saved` ·
`Setting <k> saved` · `Import failed: <msg>` · `Invalid submission: <msg>` ·
`Unknown rule '<c>'` · `Unknown community '<c>'` · `Unknown batch '<t>'`.

### Q-E. Textes français (login, reset, confirms, simulateur)

`Identifiants invalides.` · `Votre mot de passe a été modifié. Vous pouvez vous
connecter.` · `Si un compte correspond à cette adresse, un lien de réinitialisation
vient de lui être envoyé. Il est valable 30 minutes et à usage unique.` · `Les deux
saisies ne correspondent pas.` · `Ce lien de réinitialisation est invalide, déjà
utilisé ou expiré. Refaites une demande.` · confirms H4/H5/G4/J3 (littéraux cités
in situ) · `Carte inconnue — earn vide (§20)` · `Couple invalide : <msg>` ·
`Réconciliation §22.1 échouée : <msg>` · `Glissez-déposez le fichier n'importe où
dans ce cadre — le domaine se présélectionne d'après son nom (01-products.csv →
PRODUCTS, etc.).`

### Q-F. Lignes de log contractualisées

`Dev/test dataset loaded: …` · `Bootstrap users created: '<pos>' (pos), '<admin>'
(fid-admin)` · `Earn rule registry started: <n> schemas registered …` · `Empty
database detected — loading the 2026 program seed (§24.4)` · `Password reset link
sent to the address of account '<u>'` · `Rejected /earn: reconciliation failed:
<msg>` · `Failed to process chunk of size <x> with step <y>…` · `Failed to replay
held return <ref>` · `Import finished. Created: <n>, Updated: <m>`.

---

**114 scénarios** (A6 · B13 · C17 · D10 · E12 · F3 · G6 · H5 · I6 · J4 · K12 ·
L4 · M5 · N7 · O4) + inventaire Q (11 statuts API, 5 warnings, ~30 gardes
d'administration, ~25 erreurs d'import, ~15 notices, ~12 textes FR, 9 logs).

Priorité d'exécution suggérée : **C/E (projection + ingestion — l'argent et la
vérité)** → D (le protocole burn, dont le rattrapage D8) → E3/N6 (l'idempotence, le
piège qui fait échouer les campagnes) → G (les batchs destructeurs, en dry-run
d'abord) → I/J (l'administration des règles et communautés) → le reste. Les scénarios
[P] demandent un lancement prod-like complet (PostgreSQL + variables + pack CSV
qualif) ; les [W] demandent Playwright avec deux tolérances : le re-render du
formulaire schema-driven (verrou I2) et le rendu SVG du code-barres (H2).
