package fr.maxlego08.zauctionhouse.buttons.list;

import fr.maxlego08.menu.api.button.PaginateButton;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.utils.Placeholders;
import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;

public class ExpiredItemsButton extends PaginateButton {

    private final AuctionPlugin plugin;
    private final int emptySlot;

    public ExpiredItemsButton(Plugin plugin, int emptySlot) {
        this.plugin = (AuctionPlugin) plugin;
        this.emptySlot = emptySlot;
    }

    @Override
    public void onRender(Player player, InventoryEngine inventoryEngine) {

        var manager = this.plugin.getAuctionManager();
        var items = manager.getExpiredItems(player);

        if (items.isEmpty()) {
            if (this.emptySlot == -1) return;
            inventoryEngine.addItem(isPlayerInventory(), this.emptySlot, getCustomItemStack(player, false, new Placeholders()));
            return;
        }

        var configuration = this.plugin.getConfiguration().getItemLore();
        var line = configuration.expiredLore();
        var needed = configuration.expiredPlaceholders();

        paginate(items, inventoryEngine, (slot, item) -> {
            inventoryEngine.addItem(isPlayerInventory(), slot, item.buildItemStack(player, line, needed)).setClick(event -> {
                manager.getRemoveService().removeExpiredItem(player, item);
            });
        });
    }

    @Override
    public int getPaginationSize(@NonNull Player player) {
        return this.plugin.getAuctionManager().getExpiredItems(player).size();
    }
}
