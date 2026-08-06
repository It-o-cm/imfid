# Annexe B — Oracle earn du panier de référence

Calculée par le **moteur réel** d'imfid (`POST /api/earn`) sur le couple de référence
`docs/valuation-request.json` / `docs/valuation-response.json`, avec le seed 2026 chargé
(§24.4). Ce fichier vit à la racine car `docs/` est en lecture seule ; toute évolution du
seed le recalcule et incrémente la version du document de spécification (§ Annexe B).

Contrôles de valorisation du couple (vérifiés à l'ingestion par le lecteur, §22.3) :
Σ offers TTC **65,25** · Σ remises **18,14** · `totalPrice` **47,11** · assiette TR 7,71.

## 1. Affectation marques / familles des neuf produits

Le référentiel produit est repris du seed e2e d'imvaluation (mêmes EAN, `docs`
`03-products.csv` / `04-product-families.csv`) ; imfid y ajoute les **marques socle** et
les **familles earn**, car les produits imvaluation ne portent aucune marque socle. Deux
affectations sont des **choix de démonstration assumés** (§24.4 : « l'oracle dépend
d'affectations que seul le seed fixe »), afin que le *même* panier exerce à la fois le
palier de visites du socle et la règle calendaire F&L — le panier ne contient qu'un seul
fruit/légume (L1, les pommes) et il est consommé par les offres commerciales.

| Ligne | EAN | Produit | Marque imfid | Famille imfid | Net TTC | Statut earn |
|---|---|---|---|---|---|---|
| L1 | 3300000000001 | Pommes Golden (×4) | — | `F_L` | 3,64 | **consommée** — N+M `PROMO_2FOR1` + 2 bons immédiats (I2) |
| L2 | 3300000000002 | Lait UHT 1L | **Pâturages** | — | 3,00 | **candidate socle** (marque du quotidien, laitier) |
| L3 | 3300000000004 | Café Grains 500g | — | — | 2,86 | **consommée** — MixedBundle `PROMO_COFFEE_PACK` |
| L4 | 3300000000013 | Biscuits Chocolat | — | — | 1,64 | **consommée** — MixedBundle `PROMO_COFFEE_PACK` |
| L5 | 3300000000014 | Chips Classiques 150g | — | `F_L` * | 1,80 | **candidate F&L week-end** |
| L6 | 3300000000020 | Poulet Rôti 1,2 kg | — | — | 10,55 | non couverte (viande, hors socle et hors F&L) |
| L7 | 3300000000031 | Poêle 28 cm | — | `CUISSON` | 7,92 | **consommée** — remise Vignette `VIGNETTE_CUISSON` |
| L8 | 3300000000032 | Casserole 20 cm | — | `CUISSON` | 13,50 | **consommée** — geste manuel −25 % |
| L9 | 3300000000007 | Eau Minérale 1,5L (×2) | **Paquito** * | — | 1,20 | **candidate socle** (2 unités) |

\* Affectations de démonstration : L9 (eau) → **Paquito** (marque socle « expert du
fruit / boissons ») pour atteindre le seuil de 3 produits socle avec L2 (1) + L9 (2) ;
L5 (chips) → famille **`F_L`** pour exercer la règle F&L du week-end sur une ligne non
consommée. Retirer ces deux affectations rendrait le panier de référence muet sur ces
deux mécaniques (seul fruit/légume réel = L1, consommé).

**Assiettes earn qui en résultent** (lignes Standard non consommées, net ≥ 0, I1/I2) :

- **Socle** (`SOCLE_5_MARQUES`, marques {Pâturages, Paquito, Fiorini, Labell, Mäy}) :
  L2 + L9 = **4,20 €**, **3 produits éligibles** (L2 = 1, L9 = 2) → seuil `minEligibleItems`
  = 3 atteint.
- **F&L week-end** (`FL_WEEKEND`, famille `F_L`, samedi/dimanche) : L5 = **1,80 €**.
- `burnableBase = totalPrice − Σ net des lignes couvertes par PROGRAM_EXCLUSION`. Le
  panier ne porte ni carte cadeau, ni carburant… → aucune ligne couverte →
  **`burnableBase = 47,11 €`** dans les quatre contextes.

Les règles communautaires, le 40 % Labell du 28, l'e-coupon et le défi sont dans le seed
mais **ne touchent pas** ce panier pour **LOYALTY-DEMO-001** (carte « compte simple »,
sans appartenance ; scopes hors panier), donc n'apparaissent pas dans l'oracle.

## 2. Table d'oracle — carte `LOYALTY-DEMO-001`

Le socle passe de **5 %** (taux de base) à **10 %** (taux majoré) dès la **4e visite** du
mois civil (`visitThreshold` = 4) ; la 4e visite est armée par trois en-têtes
`FIDELITY_VISITS` datés des 01, 03 et 05/08/2026 (§24.4), le 4e passage étant le panier
lui-même. La règle F&L n'est active que le **samedi/dimanche**. Panier daté au fuseau du
programme (Europe/Paris). Aucun plafond n'est atteint (`capsApplied` vide partout).

| Contexte | Règle | `baseAmount` | Taux | `amount` | Total | `capsApplied` | `burnableBase` |
|---|---|---|---|---|---|---|---|
| **jeudi 06/08 · 1re visite** | SOCLE_5_MARQUES | 4,20 | 5 % | 0,21 | **0,21** | — | 47,11 |
| **jeudi 06/08 · 4e visite** | SOCLE_5_MARQUES | 4,20 | 10 % | 0,42 | **0,42** | — | 47,11 |
| **samedi 08/08 · 1re visite** | SOCLE_5_MARQUES | 4,20 | 5 % | 0,21 | **0,39** | — | 47,11 |
| | FL_WEEKEND | 1,80 | 10 % | 0,18 | | | |
| **samedi 08/08 · 4e visite** | SOCLE_5_MARQUES | 4,20 | 10 % | 0,42 | **0,60** | — | 47,11 |
| | FL_WEEKEND | 1,80 | 10 % | 0,18 | | | |

`entries[]` par contexte (`ruleCode`, `baseAmount`, `amount`) :

- **jeudi · 1re** : `[{SOCLE_5_MARQUES, 4.20, 0.21}]`
- **jeudi · 4e** : `[{SOCLE_5_MARQUES, 4.20, 0.42}]`
- **samedi · 1re** : `[{SOCLE_5_MARQUES, 4.20, 0.21}, {FL_WEEKEND, 1.80, 0.18}]`
- **samedi · 4e** : `[{SOCLE_5_MARQUES, 4.20, 0.42}, {FL_WEEKEND, 1.80, 0.18}]`

`warnings` : vide (les neuf EAN du panier sont connus du référentiel imfid).
`capsApplied` : vide (aucune troncature ; les montants sont loin des plafonds
30/20/400 €).

## 3. Reproduction

Seed chargé automatiquement au démarrage sur base vide (`SeedLoader`, §24.4). Puis, pour
un contexte donné, avec le rôle `pos` :

```bash
jq -n --slurpfile req docs/valuation-request.json --slurpfile resp docs/valuation-response.json \
  '{valuationRequest:($req[0]|.createdAt="2026-08-08T10:15:00"),
    valuationResponse:$resp[0]}' \
 | curl -s -u pos:pos-password -X POST http://localhost:8060/api/earn \
        -H 'Content-Type: application/json' --data-binary @- | jq .
```

La carte **1re visite** se rejoue sur une carte neuve (0 visite du mois) ; la carte
**4e visite** est `LOYALTY-DEMO-001` avec ses trois visites seedées. Le Simulateur (§23.5)
produit les mêmes chiffres avec une **date forcée** et sans effet de bord (§30.2).
