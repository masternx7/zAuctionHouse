package fr.maxlego08.zauctionhouse.hooks.donutauction;

import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import fr.maxlego08.zauctionhouse.api.migration.MigrationCallback;
import fr.maxlego08.zauctionhouse.api.migration.MigrationProvider;
import fr.maxlego08.zauctionhouse.api.migration.MigrationResult;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Migration provider for DonutAuction (by EliVB).
 * <p>
 * This provider migrates data from the DonutAuction plugin to zAuctionHouse V4.
 * DonutAuction stores everything in a single Bukkit YAML file located at
 * {@code plugins/DonutAuction/ah.data}, so no database configuration is required:
 * the provider reads that file directly.
 * <p>
 * Scope of the migration:
 * <ul>
 *   <li>Active (non-expired) auctions -&gt; {@code LISTED} items</li>
 *   <li>Expired auctions awaiting reclaim by the seller -&gt; {@code EXPIRED} items</li>
 *   <li>Players (UUID/name) derived from the auctions' seller fields</li>
 * </ul>
 * Purchased items are never persisted by DonutAuction (they are delivered straight to the
 * buyer's inventory), and {@code pendingPayments} represent unclaimed <i>money</i> rather than
 * items, so they are not part of this item migration.
 */
public class DonutAuctionMigrationProvider implements MigrationProvider {

    private static final File DATA_FILE = new File("plugins/DonutAuction", "ah.data");

    @Override
    public String getId() {
        return "donutauction";
    }

    @Override
    public String getDisplayName() {
        return "DonutAuction";
    }

    @Override
    public String getDescription() {
        return "DonutAuction plugin (reads from plugins/DonutAuction/ah.data)";
    }

    @Override
    public List<String> getAliases() {
        return List.of("donut", "donutsmp", "da");
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
        File folder = new File("plugins/DonutAuction");
        if (!folder.exists() || !folder.isDirectory()) {
            return "DonutAuction plugin folder not found at plugins/DonutAuction/";
        }
        if (!DATA_FILE.exists()) {
            return "DonutAuction data file not found at plugins/DonutAuction/ah.data";
        }
        return null;
    }

    @Override
    public CompletableFuture<MigrationResult> migrate(AuctionPlugin plugin, ConfigurationSection config, MigrationCallback callback) {
        var service = new DonutAuctionMigrationService(plugin);
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
