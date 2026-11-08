# zAuctionHouse V4 — Correctif base de données (errno 150 sur la table `options`)

## Symptôme

Lors de la mise à jour **4.0.0.6 → 4.0.0.9** (ou supérieur), le plugin ne démarre plus et affiche :

```
[zAuctionHouse] Create table operation failed on table: %prefix%options
 - Can't create table `s379_earth`.`zauctionhouse_options`
   (errno: 150 "Foreign key constraint is incorrectly formed")

Error occurred while enabling zAuctionHouse v4.0.0.9 (Is it up to date?)
```

## Cause

Les anciennes tables (`players`, `items`, `logs`, `transactions`…) ont été créées avec le **charset par défaut du serveur MySQL** (souvent `latin1` ou `utf8mb3`).

La nouvelle table `options` est créée en **`utf8mb4`** et possède une **clé étrangère** vers `players.unique_id`.

MySQL exige que les deux colonnes d'une clé étrangère aient **exactement le même charset/collation**. Le charset des anciennes tables ne correspond pas à `utf8mb4` → erreur **errno 150**.

> ✅ **Solution :** convertir les anciennes tables du plugin en `utf8mb4`. Opération **non destructive** : les données sont conservées.

---

## Procédure

1. **Arrêter** le serveur Minecraft (le plugin ne doit pas tourner).
2. **Faire une sauvegarde** de la base (en ligne de commande, pas dans le script SQL) :
   ```bash
   mysqldump -u UTILISATEUR -p NOM_DE_LA_BASE > backup_zauctionhouse.sql
   ```
3. **Exécuter le script SQL** ci-dessous (phpMyAdmin, HeidiSQL, DBeaver, ou client `mysql`).
4. **Redémarrer** le serveur : le plugin recréera la table `options` automatiquement, sans erreur.

---

## Script SQL

> **Préfixe par défaut = `zauctionhouse_`** (confirmé par le log : `zauctionhouse_options`).
> Si le serveur utilise un préfixe personnalisé, voir la section **Préfixe personnalisé** plus bas.

### Étape 1 — Vérification AVANT (facultatif)

Affiche le charset/collation des colonnes concernées par les clés étrangères :

```sql
SELECT TABLE_NAME, COLUMN_NAME, CHARACTER_SET_NAME, COLLATION_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME LIKE 'zauctionhouse\_%'
  AND COLUMN_NAME IN ('unique_id', 'player_unique_id',
                      'seller_unique_id', 'buyer_unique_id', 'target_unique_id')
ORDER BY TABLE_NAME, COLUMN_NAME;
```

> Avant correctif, `players.unique_id` n'est **pas** en `utf8mb4` → c'est la cause.

### Étape 2 — Correctif

```sql
SET FOREIGN_KEY_CHECKS = 0;

ALTER TABLE `zauctionhouse_players`        CONVERT TO CHARACTER SET utf8mb4;
ALTER TABLE `zauctionhouse_items`          CONVERT TO CHARACTER SET utf8mb4;
ALTER TABLE `zauctionhouse_auction_items`  CONVERT TO CHARACTER SET utf8mb4;
ALTER TABLE `zauctionhouse_transactions`   CONVERT TO CHARACTER SET utf8mb4;
ALTER TABLE `zauctionhouse_logs`           CONVERT TO CHARACTER SET utf8mb4;

SET FOREIGN_KEY_CHECKS = 1;
```

> ⚠️ **Important :** on utilise `CONVERT TO CHARACTER SET utf8mb4` **sans** préciser de `COLLATE`, afin d'obtenir la collation par défaut du serveur pour `utf8mb4` — exactement celle que le plugin applique à la table `options`. Ne forcez **pas** une collation différente, sinon la clé étrangère échouera encore (mismatch de collation).
>
> Le `SET FOREIGN_KEY_CHECKS = 0/1` est indispensable : sinon la conversion est bloquée par les clés étrangères existantes.

### Étape 3 — Vérification APRÈS

Relancez la requête de l'**Étape 1** : toutes les colonnes doivent afficher
`CHARACTER_SET_NAME = utf8mb4` (et la même `COLLATION_NAME` partout).

---

## Préfixe personnalisé

Si le préfixe des tables n'est **pas** `zauctionhouse_`, ce bloc **génère** automatiquement les commandes `ALTER` pour toutes les tables du plugin :

```sql
SET @prefix := 'VOTRE_PREFIXE_';   -- ex. 'ah_' (gardez le _ final s'il existe)

SELECT CONCAT('ALTER TABLE `', TABLE_NAME, '` CONVERT TO CHARACTER SET utf8mb4;')
         AS commande_a_executer
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME LIKE CONCAT(REPLACE(@prefix, '_', '\\_'), '%');
```

Puis exécutez les lignes générées entre les deux `SET FOREIGN_KEY_CHECKS` :

```sql
SET FOREIGN_KEY_CHECKS = 0;
-- ... coller ici les lignes ALTER générées ci-dessus ...
SET FOREIGN_KEY_CHECKS = 1;
```
