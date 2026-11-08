package fr.maxlego08.zauctionhouse.hooks.donutauction;

import fr.maxlego08.sarah.DatabaseConnection;
import fr.maxlego08.sarah.SchemaBuilder;
import fr.maxlego08.sarah.database.Schema;
import fr.maxlego08.sarah.logger.JULogger;
import fr.maxlego08.sarah.logger.Logger;
import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import fr.maxlego08.zauctionhouse.api.item.ItemType;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import fr.maxlego08.zauctionhouse.api.storage.Tables;
import fr.maxlego08.zauctionhouse.api.utils.Base64ItemStack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Service that performs the actual DonutAuction data migration.
 * <p>
 * Reads {@code plugins/DonutAuction/ah.data} (a Bukkit YAML file) and imports the auctions into
 * zAuctionHouse V4. The file has the following structure (as written by DonutAuction's
 * {@code DataManager}):
 * <pre>{@code
 * active:                 # global list of currently-listed (non-expired) auctions
 *   0:
 *     auctionId: <uuid>
 *     sellerUUID: <uuid>
 *     sellerName: <string>
 *     itemStackBase64: <line-wrapped Base64 of a Bukkit-serialized ItemStack>
 *     price: <double>
 *     createTime: <long>
 *     expireTime: <long>
 * player:                 # per-player auctions (superset; includes expired-not-yet-reclaimed)
 *   0: { ...same fields... }
 * pendingPayments:        # unclaimed sale money (NOT items) - skipped by this migration
 *   0: { sellerUUID, sellerName, amount, itemName, itemAmount, timestamp }
 * }</pre>
 * Auctions are deduplicated by {@code auctionId} across both sections. An auction whose
 * {@code expireTime} is in the past is imported as {@link StorageType#EXPIRED} (the seller can
 * reclaim it); otherwise it is imported as {@link StorageType#LISTED}.
 */
public class DonutAuctionMigrationService {

    /**
     * Economy name used for imported items. Matches the default economy declared in
     * zAuctionHouse's {@code economies.yml}; DonutAuction itself only supports Vault.
     */
    private static final String ECONOMY_NAME = "vault";

    private final AuctionPlugin plugin;
    private final Logger logger;
    private Consumer<String> progressCallback;

    public DonutAuctionMigrationService(AuctionPlugin plugin) {
        this.plugin = plugin;
        this.logger = JULogger.from(plugin.getLogger());
    }

    public void onProgress(Consumer<String> callback) {
        this.progressCallback = callback;
    }

    public CompletableFuture<DonutMigrationResult> migrate() {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            AtomicInteger errors = new AtomicInteger(0);

            try {
                File dataFile = new File("plugins/DonutAuction", "ah.data");
                if (!dataFile.exists()) {
                    return DonutMigrationResult.failure("DonutAuction data file not found at: " + dataFile.getAbsolutePath());
                }

                progress("Reading DonutAuction data file: " + dataFile.getName());
                YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);

                DatabaseConnection v4Connection = plugin.getStorageManager().getDatabaseConnection();
                Map<UUID, String> players = new HashMap<>();
                Set<UUID> seenAuctionIds = new HashSet<>();

                // counters[0] = listed, counters[1] = expired
                int[] counters = new int[2];

                progress("Migrating auctions (listed + expired)...");
                migrateSection(data, "active", v4Connection, players, seenAuctionIds, counters, errors);
                migrateSection(data, "player", v4Connection, players, seenAuctionIds, counters, errors);

                int itemsMigrated = counters[0] + counters[1];
                progress("Migrated " + itemsMigrated + " items (" + counters[0] + " listed, " + counters[1] + " expired)");

                // pendingPayments are unclaimed money, not items - report and skip.
                ConfigurationSection pendingSection = data.getConfigurationSection("pendingPayments");
                if (pendingSection != null) {
                    int pendingCount = pendingSection.getKeys(false).size();
                    if (pendingCount > 0) {
                        progress("Skipped " + pendingCount + " pending payments (unclaimed money - not part of item migration)");
                    }
                }

                long duration = System.currentTimeMillis() - startTime;
                progress("Migration completed in " + duration + "ms");

                return DonutMigrationResult.success(players.size(), itemsMigrated, 0, errors.get(), duration);

            } catch (Exception exception) {
                this.plugin.getLogger().severe("Migration failed: " + exception.getMessage());
                exception.printStackTrace();
                return DonutMigrationResult.failure("Migration failed: " + exception.getMessage());
            }
        }, plugin.getExecutorService());
    }

    /**
     * Migrates every auction in the given section ({@code active} or {@code player}), skipping
     * any auction id already processed (the two sections overlap in DonutAuction).
     */
    private void migrateSection(YamlConfiguration data, String sectionName, DatabaseConnection v4Connection,
                                Map<UUID, String> players, Set<UUID> seenAuctionIds, int[] counters, AtomicInteger errors) {
        ConfigurationSection section = data.getConfigurationSection(sectionName);
        if (section == null) {
            return;
        }

        long now = System.currentTimeMillis();

        for (String key : section.getKeys(false)) {
            String path = sectionName + "." + key;
            try {
                String auctionIdStr = data.getString(path + ".auctionId");
                String sellerUuidStr = data.getString(path + ".sellerUUID");
                if (auctionIdStr == null || sellerUuidStr == null) {
                    continue;
                }

                UUID auctionId = UUID.fromString(auctionIdStr);
                if (!seenAuctionIds.add(auctionId)) {
                    continue; // already imported from the other section
                }

                UUID sellerUuid = UUID.fromString(sellerUuidStr);
                String sellerName = data.getString(path + ".sellerName");
                String itemStackBase64 = data.getString(path + ".itemStackBase64");
                double price = data.getDouble(path + ".price");
                long expireTime = data.getLong(path + ".expireTime");

                String v4Itemstack = convertItemStack(itemStackBase64);
                if (v4Itemstack == null) {
                    this.plugin.getLogger().warning("Failed to convert itemstack for auction " + auctionId + ", skipping");
                    errors.incrementAndGet();
                    continue;
                }

                // Player must exist before the item (items.seller_unique_id is a foreign key).
                trackPlayer(v4Connection, players, sellerUuid, sellerName);

                boolean expired = expireTime > 0 && expireTime <= now;
                StorageType storageType = expired ? StorageType.EXPIRED : StorageType.LISTED;
                long effectiveExpire = expireTime > 0 ? expireTime : now;

                int itemId = createItem(v4Connection, sellerUuid, price, storageType, effectiveExpire);
                if (itemId == -1) {
                    errors.incrementAndGet();
                    continue;
                }

                insertAuctionItem(v4Connection, itemId, v4Itemstack);

                if (expired) {
                    counters[1]++;
                } else {
                    counters[0]++;
                }

                int total = counters[0] + counters[1];
                if (total % 100 == 0) {
                    progress("Migrated " + total + " items...");
                }
            } catch (Exception exception) {
                this.plugin.getLogger().warning("Failed to migrate auction at " + path + ": " + exception.getMessage());
                errors.incrementAndGet();
            }
        }
    }

    /**
     * Converts a DonutAuction (legacy line-wrapped Bukkit Base64) itemstack to the V4 format.
     */
    private String convertItemStack(String donutData) {
        ItemStack itemStack = Serialize.deserializeLegacy(donutData, plugin);
        if (itemStack == null) {
            return null;
        }
        return Base64ItemStack.encode(itemStack);
    }

    private int createItem(DatabaseConnection v4Connection, UUID sellerUuid, double price, StorageType storageType, long expireAt) {
        try {
            Schema schema = SchemaBuilder.insert(Tables.ITEMS, s -> {
                s.string("item_type", ItemType.AUCTION.name());
                s.uuid("seller_unique_id", sellerUuid);
                s.decimal("price", BigDecimal.valueOf(price));
                s.string("economy_name", ECONOMY_NAME);
                s.string("storage_type", storageType.name());
                s.string("server_name", plugin.getConfiguration().getServerName());
                s.object("expired_at", new Date(expireAt));
            });

            return schema.execute(v4Connection, logger);
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("Failed to create item: " + exception.getMessage());
            return -1;
        }
    }

    private void insertAuctionItem(DatabaseConnection v4Connection, int itemId, String itemstack) {
        try {
            Schema schema = SchemaBuilder.insert(Tables.AUCTION_ITEMS, s -> {
                s.object("item_id", itemId);
                s.string("itemstack", itemstack);
            });

            schema.execute(v4Connection, logger);
        } catch (SQLException exception) {
            this.plugin.getLogger().warning("Failed to create auction item for item_id " + itemId + ": " + exception.getMessage());
        }
    }

    /**
     * Tracks a player UUID-&gt;name mapping, always preferring a real name over "Unknown".
     * Creates or updates the player directly in the V4 database and adds them to the local map.
     */
    private void trackPlayer(DatabaseConnection v4Connection, Map<UUID, String> players, UUID uuid, String name) {
        String resolvedName = (name != null && !name.isBlank()) ? name : "Unknown";
        String previous = players.get(uuid);
        if (previous == null || ("Unknown".equals(previous) && !"Unknown".equals(resolvedName))) {
            players.put(uuid, resolvedName);
            try {
                SchemaBuilder.upsert(Tables.PLAYERS, s -> {
                    s.uuid("unique_id", uuid).primary();
                    s.string("name", resolvedName);
                }).execute(v4Connection, logger);
            } catch (Exception exception) {
                this.plugin.getLogger().warning("Failed to upsert player " + uuid + ": " + exception.getMessage());
            }
        }
    }

    private void progress(String message) {
        this.plugin.getLogger().info("[Migration] " + message);
        if (this.progressCallback != null) {
            this.progressCallback.accept(message);
        }
    }
}
