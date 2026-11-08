package fr.maxlego08.zauctionhouse.buttons.list;

import fr.maxlego08.menu.api.button.PaginateButton;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.utils.Placeholders;
import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import fr.maxlego08.zauctionhouse.api.item.Item;
import fr.maxlego08.zauctionhouse.api.item.ItemPlaceholder;
import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * A button that combines items from multiple storage types (selling, expired, purchased)
 * into a single paginated view. The user configures which storage types to include
 * and the lore/placeholders for each type.
 */
public class CombinedItemsButton extends PaginateButton {

    private final AuctionPlugin plugin;
    private final int emptySlot;
    private final boolean includeSelling;
    private final boolean includeExpired;
    private final boolean includePurchased;

    public CombinedItemsButton(AuctionPlugin plugin, int emptySlot, boolean includeSelling, boolean includeExpired, boolean includePurchased) {
        this.plugin = plugin;
        this.emptySlot = emptySlot;
        this.includeSelling = includeSelling;
        this.includeExpired = includeExpired;
        this.includePurchased = includePurchased;
    }

    @Override
    public void onRender(Player player, InventoryEngine inventoryEngine) {

        var items = getCombinedItems(player);

        if (items.isEmpty()) {
            if (this.emptySlot == -1) return;
            inventoryEngine.addItem(isPlayerInventory(), this.emptySlot, getCustomItemStack(player, false, new Placeholders()));
            return;
        }

        var configuration = this.plugin.getConfiguration().getItemLore();
        var manager = this.plugin.getAuctionManager();
        var removeService = manager.getRemoveService();

        paginate(items, inventoryEngine, (slot, item) -> {

            StorageType storageType = getItemStorageType(item, player);
            List<String> lore = getLoreForStorageType(configuration, item, storageType);
            Set<ItemPlaceholder> needed = getPlaceholdersForStorageType(configuration, item, storageType);

            inventoryEngine.addItem(isPlayerInventory(), slot, item.buildItemStack(player, lore, needed)).setClick(event -> {
                switch (storageType) {
                    case LISTED -> removeService.removeSellingItem(player, item);
                    case EXPIRED -> removeService.removeExpiredItem(player, item);
                    case PURCHASED -> removeService.removePurchasedItem(player, item);
                    default -> { }
                }
            });
        });
    }

    @Override
    public int getPaginationSize(@NonNull Player player) {
        return getCombinedItems(player).size();
    }

    private List<Item> getCombinedItems(Player player) {
        var manager = this.plugin.getAuctionManager();
        List<Item> combined = new ArrayList<>();

        if (this.includeSelling) {
            combined.addAll(manager.getPlayerSellingItems(player));
        }
        if (this.includeExpired) {
            combined.addAll(manager.getExpiredItems(player));
        }
        if (this.includePurchased) {
            combined.addAll(manager.getPurchasedItems(player));
        }

        combined.sort(Comparator.comparing(Item::getExpiredAt).reversed());
        return combined;
    }

    private StorageType getItemStorageType(Item item, Player player) {
        return switch (item.getStatus()) {
            case AVAILABLE, IS_REMOVE_CONFIRM, IS_BEING_REMOVED, IS_PURCHASE_CONFIRM, IS_BEING_PURCHASED ->
                    StorageType.LISTED;
            case REMOVED -> StorageType.EXPIRED;
            case PURCHASED -> StorageType.PURCHASED;
            case DELETED -> StorageType.DELETED;
        };
    }

    private List<String> getLoreForStorageType(fr.maxlego08.zauctionhouse.api.configuration.records.ItemLoreConfiguration configuration, Item item, StorageType storageType) {
        return switch (storageType) {
            case LISTED ->
                    item.getStatus() == ItemStatus.AVAILABLE ? configuration.sellingLore() : configuration.beingPurchasedLore();
            case EXPIRED -> configuration.expiredLore();
            case PURCHASED -> configuration.purchasedLore();
            default -> configuration.sellingLore();
        };
    }

    private Set<ItemPlaceholder> getPlaceholdersForStorageType(fr.maxlego08.zauctionhouse.api.configuration.records.ItemLoreConfiguration configuration, Item item, StorageType storageType) {
        return switch (storageType) {
            case LISTED ->
                    item.getStatus() == ItemStatus.AVAILABLE ? configuration.sellingPlaceholders() : configuration.beingPurchasedPlaceholders();
            case EXPIRED -> configuration.expiredPlaceholders();
            case PURCHASED -> configuration.purchasedPlaceholders();
            default -> configuration.sellingPlaceholders();
        };
    }
}
