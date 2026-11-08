package fr.maxlego08.zauctionhouse.hooks.crazyauctions;

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
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Base64;
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
 * Service that performs the actual CrazyAuctions data migration.
 * <p>
 * Reads {@code plugins/CrazyAuctions/data.yml} (a Bukkit YAML file) and imports the auctions into
 * zAuctionHouse V4. The file has the following structure (as written by CrazyAuctions):
 * <pre>{@code
 * Items:                  # active listings (sell or bid)
 *   1:
 *     Price: <long>
 *     Seller: <uuid-string>
 *     Time-Till-Expire: <long millis>
 *     Full-Time: <long millis>
 *     StoreID: <int>
 *     Biddable: <boolean>
 *     TopBidder: <uuid-string or "None">
 *     Item: <Base64 of ItemStack.serializeAsBytes()>
 * OutOfTime/Cancelled:    # expired or admin-cancelled items awaiting reclaim
 *   1:
 *     Seller: <uuid-string>   (owner: original seller, or bid winner)
 *     Full-Time: <long millis>
 *     StoreID: <int>
 *     Item: <Base64 of ItemStack.serializeAsBytes()>
 * }</pre>
 * Items are deduplicated by {@code StoreID} across both sections. An active item whose
 * {@code Time-Till-Expire} is in the past is imported as {@link StorageType#EXPIRED};
 * otherwise it is imported as {@link StorageType#LISTED}. All {@code OutOfTime/Cancelled}
 * items are imported as {@link StorageType#EXPIRED}.
 */
public class CrazyAuctionsMigrationService {

    /**
     * Economy name used for imported items. CrazyAuctions only supports Vault.
     */
    private static final String ECONOMY_NAME = "vault";

    private final AuctionPlugin plugin;
    private final Logger logger;
    private Consumer<String> progressCallback;

    public CrazyAuctionsMigrationService(AuctionPlugin plugin) {
        this.plugin = plugin;
        this.logger = JULogger.from(plugin.getLogger());
    }

    public void onProgress(Consumer<String> callback) {
        this.progressCallback = callback;
    }

    public CompletableFuture<CrazyAuctionsMigrationResult> migrate() {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            AtomicInteger errors = new AtomicInteger(0);

            try {
                File dataFile = new File("plugins/CrazyAuctions", "data.yml");
                if (!dataFile.exists()) {
                    return CrazyAuctionsMigrationResult.failure("CrazyAuctions data file not found at: " + dataFile.getAbsolutePath());
                }

                progress("Reading CrazyAuctions data file: " + dataFile.getName());
                YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);

                DatabaseConnection v4Connection = plugin.getStorageManager().getDatabaseConnection();
                Map<UUID, String> players = new HashMap<>();
                Set<Integer> seenStoreIds = new HashSet<>();

                // counters[0] = listed, counters[1] = expired
                int[] counters = new int[2];

                // Migrate active items
                progress("Migrating active auctions (Items section)...");
                migrateActiveItems(data, v4Connection, players, seenStoreIds, counters, errors);

                // Migrate expired/cancelled items
                progress("Migrating expired/cancelled auctions (OutOfTime/Cancelled section)...");
                migrateExpiredItems(data, v4Connection, players, seenStoreIds, counters, errors);

                int itemsMigrated = counters[0] + counters[1];
                progress("Migrated " + itemsMigrated + " items (" + counters[0] + " listed, " + counters[1] + " expired)");

                long duration = System.currentTimeMillis() - startTime;
                progress("Migration completed in " + duration + "ms");

                return CrazyAuctionsMigrationResult.success(players.size(), itemsMigrated, 0, errors.get(), duration);

            } catch (Exception exception) {
                this.plugin.getLogger().severe("Migration failed: " + exception.getMessage());
                exception.printStackTrace();
                return CrazyAuctionsMigrationResult.failure("Migration failed: " + exception.getMessage());
            }
        }, plugin.getExecutorService());
    }

    /**
     * Migrates active auctions from the {@code Items} section.
     * Items whose {@code Time-Till-Expire} is in the past are imported as EXPIRED.
     */
    private void migrateActiveItems(YamlConfiguration data, DatabaseConnection v4Connection,
                                    Map<UUID, String> players, Set<Integer> seenStoreIds, int[] counters, AtomicInteger errors) {
        ConfigurationSection section = data.getConfigurationSection("Items");
        if (section == null) {
            progress("No active items found in Items section.");
            return;
        }

        long now = System.currentTimeMillis();

        for (String key : section.getKeys(false)) {
            String path = "Items." + key;
            try {
                int storeId = data.getInt(path + ".StoreID");
                if (!seenStoreIds.add(storeId)) {
                    continue; // duplicate
                }

                String sellerUuidStr = data.getString(path + ".Seller");
                if (sellerUuidStr == null) {
                    continue;
                }

                UUID sellerUuid = UUID.fromString(sellerUuidStr);
                long price = data.getLong(path + ".Price");
                long timeTillExpire = data.getLong(path + ".Time-Till-Expire");
                String itemBase64 = data.getString(path + ".Item");

                if (itemBase64 == null || itemBase64.isBlank()) {
                    this.plugin.getLogger().warning("Skipping item at " + path + ": no item data");
                    errors.incrementAndGet();
                    continue;
                }

                // Convert CrazyAuctions itemstack to V4 format
                String v4Itemstack = convertItemStack(itemBase64);
                if (v4Itemstack == null) {
                    this.plugin.getLogger().warning("Failed to convert itemstack at " + path + ", skipping");
                    errors.incrementAndGet();
                    continue;
                }

                // Resolve player name
                String sellerName = resolvePlayerName(sellerUuid);
                trackPlayer(v4Connection, players, sellerUuid, sellerName);

                // Determine storage type: if expired, mark as EXPIRED
                boolean expired = timeTillExpire > 0 && timeTillExpire <= now;
                StorageType storageType = expired ? StorageType.EXPIRED : StorageType.LISTED;
                long effectiveExpire = timeTillExpire > 0 ? timeTillExpire : now;

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
                this.plugin.getLogger().warning("Failed to migrate item at " + path + ": " + exception.getMessage());
                errors.incrementAndGet();
            }
        }
    }

    /**
     * Migrates expired/cancelled items from the {@code OutOfTime/Cancelled} section.
     * All items in this section are imported as EXPIRED.
     */
    private void migrateExpiredItems(YamlConfiguration data, DatabaseConnection v4Connection,
                                     Map<UUID, String> players, Set<Integer> seenStoreIds, int[] counters, AtomicInteger errors) {
        ConfigurationSection section = data.getConfigurationSection("OutOfTime/Cancelled");
        if (section == null) {
            progress("No expired/cancelled items found.");
            return;
        }

        for (String key : section.getKeys(false)) {
            String path = "OutOfTime/Cancelled." + key;
            try {
                int storeId = data.getInt(path + ".StoreID");
                if (!seenStoreIds.add(storeId)) {
                    continue; // duplicate
                }

                String sellerUuidStr = data.getString(path + ".Seller");
                if (sellerUuidStr == null) {
                    continue;
                }

                UUID sellerUuid = UUID.fromString(sellerUuidStr);
                long fullTime = data.getLong(path + ".Full-Time");
                String itemBase64 = data.getString(path + ".Item");

                if (itemBase64 == null || itemBase64.isBlank()) {
                    this.plugin.getLogger().warning("Skipping expired item at " + path + ": no item data");
                    errors.incrementAndGet();
                    continue;
                }

                String v4Itemstack = convertItemStack(itemBase64);
                if (v4Itemstack == null) {
                    this.plugin.getLogger().warning("Failed to convert itemstack at " + path + ", skipping");
                    errors.incrementAndGet();
                    continue;
                }

                String sellerName = resolvePlayerName(sellerUuid);
                trackPlayer(v4Connection, players, sellerUuid, sellerName);

                // Expired items have no price in OutOfTime/Cancelled, default to 0
                int itemId = createItem(v4Connection, sellerUuid, 0, StorageType.EXPIRED, fullTime > 0 ? fullTime : System.currentTimeMillis());
                if (itemId == -1) {
                    errors.incrementAndGet();
                    continue;
                }

                insertAuctionItem(v4Connection, itemId, v4Itemstack);
                counters[1]++;

                int total = counters[0] + counters[1];
                if (total % 100 == 0) {
                    progress("Migrated " + total + " items...");
                }
            } catch (Exception exception) {
                this.plugin.getLogger().warning("Failed to migrate expired item at " + path + ": " + exception.getMessage());
                errors.incrementAndGet();
            }
        }
    }

    /**
     * Converts a CrazyAuctions Base64 itemstack to the zAuctionHouse V4 format.
     * <p>
     * CrazyAuctions uses {@code Base64.getEncoder().encodeToString(ItemStack.serializeAsBytes())}
     * (Paper modern format). We deserialize using {@code ItemStack.deserializeBytes()}, then
     * re-encode using {@link Base64ItemStack#encode(ItemStack)}.
     */
    private String convertItemStack(String crazyAuctionsBase64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(crazyAuctionsBase64);
            ItemStack itemStack = ItemStack.deserializeBytes(bytes);
            if (itemStack == null) {
                return null;
            }
            return Base64ItemStack.encode(itemStack);
        } catch (Exception exception) {
            this.plugin.getLogger().warning("Failed to deserialize CrazyAuctions ItemStack: "
                    + exception.getClass().getSimpleName() + " - " + exception.getMessage());
            this.plugin.getLogger().warning("Server version: " + Bukkit.getBukkitVersion()
                    + " - data length: " + crazyAuctionsBase64.length());
            return null;
        }
    }

    /**
     * Resolves a player name from their UUID using Bukkit's offline player cache.
     */
    private String resolvePlayerName(UUID uuid) {
        try {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            return (name != null && !name.isBlank()) ? name : "Unknown";
        } catch (Exception exception) {
            return "Unknown";
        }
    }

    private int createItem(DatabaseConnection v4Connection, UUID sellerUuid, long price, StorageType storageType, long expireAt) {
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
     * Tracks a player UUID&rarr;name mapping, always preferring a real name over "Unknown".
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
