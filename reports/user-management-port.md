# Portage de la gestion des utilisateurs — imvaluation → imfid

Portage de la gestion des comptes opérateurs telle que la pratique **imvaluation**
(dépôt de référence, lu en **lecture seule absolue**) vers **imfid**, en code natif du
receveur. Aucun test écrit, aucun commit ; vérifié par `mvn -q test-compile` (succès —
le processeur d'annotations Qute a validé au passage les templates `@CheckedTemplate`).

---

## 1. Inventaire du donneur (imvaluation)

Pile : Quarkus + RESTEasy Classic + Qute (rendu HTML serveur), Hibernate Panache (Active
Record), `quarkus-security-jpa`, bcrypt via Elytron. Package racine
`com.intermarche.valuation`.

| Élément | Fichier donneur | Rôle |
|---|---|---|
| **Entité utilisateur** | `domain/AppUser.java` | `@UserDefinition`, `@Username/@Password/@Roles`, bcrypt (`setPassword`/`matchesPassword`/`validatePassword`), rôles CSV `VIEWER<MANAGER<ADMIN`, `displayName`, `active`, `mustChangePassword`, `getRoleSet/setRoleSet/hasRole`, `findByUsername`, `countActiveAdmins`. |
| **Écrans CRUD** | `ui/UserUiResource.java` | Classe entière `@RolesAllowed(ADMIN)`. Routes : `GET /ui/users` (liste filtrée/triée/paginée), `GET /ui/users/new`, `GET /ui/users/{id}`, `POST /ui/users/new` (create), `POST /ui/users/{id}` (update), `POST /ui/users/{id}/delete`. |
| **View model liste** | `ui/ListView.java` | Générique paginé (filtres, tri, pager, `canWrite` cosmétique). |
| **Templates** | `templates/UserUiResource/list.html`, `form.html` | Liste (Username/Display name/Roles/Status/Actions) ; formulaire en 3 cartes **Identity / Password / Access** (cases à cocher par rôle + toggle actif). |
| **Sécurité — bootstrap** | `security/UserBootstrap.java` | Admin initial si table vide, MDP env, **`mustChangePassword=true`**. |
| **Sécurité — enforcement** | `security/PasswordChangeFilter.java` | `ContainerRequestFilter` : redirige toute **navigation navigateur** vers `/ui/password` tant que `mustChangePassword` ; exempte Basic/non-HTML (clients API) ; piloté par `app.password-change.enforced` (off en dev/test). |
| **Sécurité — écran MDP** | `ui/AuthUiResource.java` (`/ui/password`) + `templates/.../password.html` | Changement forcé (sans MDP courant) vs volontaire (avec) ; `setPassword` + `mustChangePassword=false`. |
| **Identité template** | `security/CurrentUser.java` | `inject:currentUser` — `admin`/`authenticated`/`name`, purement cosmétique. |
| **Config** | `application.properties` | Form-auth + Basic, comptes `@UserDefinition`, `app.password-change.enforced`. |
| **CSS** | `META-INF/resources/ui/user.css` | `.chip-role`, `.chip-self`, `.role-grid`, `.role-option`, `.role-name/.role-desc`, `.toggle-row`. |

**Contrat fonctionnel** : CRUD réservé à l'admin ; garde-fous **dernier administrateur
actif** (pas de suppression/désactivation/rétrogradation) et **pas d'auto-suppression** ;
**MDP forcé** à chaque création/reset admin, confiné par un filtre jusqu'au changement ;
sanitisation des rôles soumis ; `matchesPassword`/policy 8 caractères ; POST → 303 → notice.

---

## 2. Repris tel quel / adapté (et pourquoi)

### Repris tel quel (mêmes réflexes, code du receveur)
- **Structure des écrans** : liste filtrable/triable/paginée + formulaire, garde-fous
  identiques (dernier admin, auto-suppression), sanitisation des rôles, MDP forcé sur
  création/reset. **Pourquoi** : c'est le contrat fonctionnel à transplanter.
- **Doublage serveur** : `@RolesAllowed(fid-admin)` sur toute la resource comme autorité ;
  `canWrite`/`CurrentUser.admin` seulement cosmétiques. **Pourquoi** : patron d'imfid
  (§21.4), déjà en place.
- **`ListView<T>`** réutilisé **sans modification** pour la liste des utilisateurs.
- **Enforcement `mustChangePassword`** (filtre + écran) : porté à l'identique dans son
  principe (Basic/non-HTML exemptés, prefixes autorisés, config d'activation).

### Adapté au receveur
- **Matrice de rôles** `VIEWER/MANAGER/ADMIN` → **`pos`/`fid-admin`** existants
  (`AppUser.ALL_ROLES`), non modifiée. Descriptions de rôles réécrites en conséquence.
- **Packages** `com.intermarche.valuation.*` → `com.intermarche.fidelity.*`.
- **Mutations dans un service** `UserAdminService` (`admin/`) levant `AdminException`, au
  lieu de la logique inline de la resource donneuse. **Pourquoi** : convention imfid
  « jamais de mutation directe dans la resource », symétrie avec `AdminService`.
- **View models natifs** `UserRow` / `UserFormView` sur le moule `RuleRow` / `RuleFormView`
  (champs publics + factories `creation`/`edition`), au lieu des templates typés donneurs.
- **Gabarit & tokens CSS d'imfid** : `{#include ui/layout}` + `{#title}/{#body}`, classes
  `page-head`, `data-table`, `card stacked-form`, `field-grid`, `btn/alert/badge/chip` ;
  styles `.chip-role/.chip-self/.role-grid/.role-option/.toggle-row` ajoutés à
  `fidelity.css` (le token violet `#eceaf7/#5a49a8` existait déjà via `.chip-group`).
- **Champ `email`** : imfid porte `email` sur `AppUser` et un flux de reset self-service
  par mail ; il est donc exposé dans la liste et le formulaire (le donneur n'avait pas
  d'e-mail). **Pourquoi** : alimente `findActiveByEmail` et `/ui/forgot` déjà présents.
- **Écran `/ui/password` standalone** (style des pages auth d'imfid : `login-panel`), et
  flux **redirect-based** (`?error=`) au lieu du re-render donneur — cohérent avec
  `login/forgot/reset` d'imfid. Redirection finale vers `/ui/cards`.
- **« Reset » de mot de passe** = saisie d'un nouveau MDP dans le **formulaire d'édition**
  (pose `mustChangePassword=true`), exactement comme le donneur ; pas d'endpoint séparé.
  Le reset self-service par e-mail (`/ui/forgot`, `/ui/reset`) préexistant est inchangé.

---

## 3. Fichiers créés et modifiés (exhaustif)

### Créés
1. `src/main/java/com/intermarche/fidelity/admin/UserAdminService.java` — service de
   mutation (create/update/delete) + garde-fous + sanitisation.
2. `src/main/java/com/intermarche/fidelity/ui/UserUiResource.java` — resource
   `@Path("/ui/users")` `@RolesAllowed(fid-admin)` (liste, new, edit, create, update, delete).
3. `src/main/java/com/intermarche/fidelity/ui/UserRow.java` — ligne de liste (+ flag `self`).
4. `src/main/java/com/intermarche/fidelity/ui/UserFormView.java` — view model du formulaire
   (factories `creation`/`edition`, `granted(role)`).
5. `src/main/java/com/intermarche/fidelity/security/PasswordChangeFilter.java` —
   enforcement `mustChangePassword` (navigation navigateur uniquement).
6. `src/main/resources/templates/UserUiResource/list.html` — écran liste.
7. `src/main/resources/templates/UserUiResource/form.html` — écran création/édition.
8. `src/main/resources/templates/AuthUiResource/password.html` — écran de changement de MDP.

### Modifiés
9. `src/main/java/com/intermarche/fidelity/ui/AuthUiResource.java` — ajout `GET`/`POST
   /ui/password` (changement authentifié, forcé vs volontaire) + helpers `validateChange`,
   `currentUser`.
10. `src/main/java/com/intermarche/fidelity/security/SecurityBootstrap.java` — compte admin
    bootstrap `mustChangePassword=true` (le compte machine `pos` reste `false`).
11. `src/main/resources/templates/ui/layout.html` — entrée de nav **Utilisateurs** (admin)
    + lien **Mot de passe** dans le bloc identité.
12. `src/main/resources/META-INF/resources/ui/fidelity.css` — styles users
    (`.chip-role/.chip-self/.role-grid/.role-option/.role-name/.role-desc/.toggle-row`).
13. `src/main/resources/application.properties` — `imfid.password-change.enforced`
    (`true`, `%dev`/`%test`=`false`).

> `AppUser.java` n'a **pas** été modifié dans ce portage (il portait déjà tout le modèle
> requis) ; il apparaît en `M` dans `git status` du fait du travail antérieur de la branche.

---

## 4. Écarts signalés (une ligne chacun)

- Le donneur n'a pas d'`email` ; imfid l'a → champ e-mail exposé en liste et formulaire (alimente le reset existant).
- « Reset » de MDP non exposé en action dédiée : il se fait via le formulaire d'édition (pose `mustChangePassword`), comme chez le donneur.
- L'enforcement `mustChangePassword` était **absent** d'imfid (champ écrit jamais lu) → filtre + écran `/ui/password` ajoutés pour honorer le contrat.
- Compte admin bootstrap passé à `mustChangePassword=true` (auparavant `false`) → aligne le comportement sur le donneur ; relâché en dev/test.
- Config d'activation nommée `imfid.password-change.enforced` (donneur : `app.password-change.enforced`).
- Mutations déplacées dans `UserAdminService` (donneur : inline dans la resource) → convention imfid « service pour toute mutation ».
- Badge « MDP à changer » ajouté dans la liste (état réel utile ; le donneur ne rendait pas ce badge malgré sa doc).
- Écran `/ui/password` rendu **standalone** (style auth d'imfid) et non via le layout comme chez le donneur.
- Chemins `/ui/users` et `/ui/password` protégés par `@RolesAllowed`/`@Authenticated` sur les resources, sans bloc de permission de chemin dédié (patron imfid pour `/ui/*`).
- La matrice de sécurité existante (`public`/`api`/`imports`, form-auth + Basic) n'a **pas** bougé.
