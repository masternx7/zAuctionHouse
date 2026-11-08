package fr.maxlego08.zauctionhouse.hooks.crazyauctions;

import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import fr.maxlego08.zauctionhouse.api.migration.MigrationCallback;
import fr.maxlego08.zauctionhouse.api.migration.MigrationProvider;
import fr.maxlego08.zauctionhouse.api.migration.MigrationResult;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Migration provider for CrazyAuctions (by badbones69).
 * <p>
 * This provider migrates data from the CrazyAuctions plugin to zAuctionHouse V4.
 * CrazyAuctions stores everything in a single Bukkit YAML file located at
 * {@code plugins/CrazyAuctions/data.yml}, so no database configuration is required:
 * the provider reads that file directly.
 * <p>
 * Scope of the migration:
 * <ul>
 *   <li>Active (non-expired) auctions from the {@code Items} section &rarr; {@code LISTED} items</li>
 *   <li>Expired auctions whose {@code Time-Till-Expire} is in the past &rarr; {@code EXPIRED} items</li>
 *   <li>Items in {@code OutOfTime/Cancelled} section &rarr; {@code EXPIRED} items</li>
 *   <li>Players (UUID/name) derived from the {@code Seller} fields</li>
 * </ul>
 * <p>
 * CrazyAuctions uses Paper's modern {@code ItemStack.serializeAsBytes()} with
 * {@code Base64.getEncoder()} for item serialization. The provider deserializes
 * using {@code ItemStack.deserializeBytes()} and re-encodes to zAuctionHouse V4 format.
 */
public class CrazyAuctionsMigrationProvider implements MigrationProvider {

    private static final File DATA_FILE = new File("plugins/CrazyAuctions", "data.yml");

    @Override
    public String getId() {
        return "crazyauctions";
    }

    @Override
    public String getDisplayName() {
        return "CrazyAuctions";
    }

    @Override
    public String getDescription() {
        return "CrazyAuctions plugin by badbones69 (reads from plugins/CrazyAuctions/data.yml)";
    }

    @Override
    public List<String> getAliases() {
        return List.of("crazy", "ca", "crazyauction");
    }

    @Override
    public String getConfigSection() {
        return null;
    }

    @Override
    public String getDefaultSqlitePath() {
        return null;
    }

    @Override
    public String getDefaultJsonFolder() {
        return null;
    }

    @Override
    public String getDefaultTablePrefix() {
        return null;
    }

    @Override
    public String validateConfig(ConfigurationSection config) {
        File folder = new File("plugins/CrazyAuctions");
        if (!folder.exists() || !folder.isDirectory()) {
            return "CrazyAuctions plugin folder not found at plugins/CrazyAuctions/";
        }
        if (!DATA_FILE.exists()) {
            return "CrazyAuctions data file not found at plugins/CrazyAuctions/data.yml";
        }
        return null;
    }

    @Override
    public CompletableFuture<MigrationResult> migrate(AuctionPlugin plugin, ConfigurationSection config, MigrationCallback callback) {
        var service = new CrazyAuctionsMigrationService(plugin);
        service.onProgress(callback::onProgress);
        return service.migrate().thenApply(result -> {
            if (!result.isSuccess()) {
                return MigrationResult.failure(result.getErrorMessage());
            }
            return MigrationResult.success(
                    result.getPlayersImported(),
                    result.getItemsImported(),
                    result.getTransactionsImported(),
                    result.getErrors(),
                    result.getDurationMs()
            );
        }).exceptionally(ex -> MigrationResult.failure(ex.getMessage()));
    }
}
