package fr.maxlego08.zauctionhouse.cluster;

import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import fr.maxlego08.zauctionhouse.api.cache.PlayerCacheKey;
import fr.maxlego08.zauctionhouse.api.cluster.AuctionClusterBridge;
import fr.maxlego08.zauctionhouse.api.cluster.LockToken;
import fr.maxlego08.zauctionhouse.api.item.Item;
import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import fr.maxlego08.zauctionhouse.api.messages.Message;
import org.bukkit.entity.Player;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.SetParams;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RedisAuctionClusterBridge implements AuctionClusterBridge {

    private static final String CHANNEL = "zauctionhouse:cluster";
    private static final String LOCK_PREFIX = "zauctionhouse:lock:";
    private static final long LOCK_TTL_MS = 30_000L;

    private final AuctionPlugin plugin;
    private final JedisPool jedisPool;
    private final String serverId;

    private volatile JedisPubSub pubSub;
    private Thread subscriberThread;

    private enum Action {
        ITEM_LISTED,
        ITEM_REMOVED,
        ITEM_STATUS_CHANGE,
        ITEM_BOUGHT
    }

    public RedisAuctionClusterBridge(AuctionPlugin plugin, String host, int port, String password, int database, String serverId) {
        this.plugin = plugin;
        this.serverId = serverId;

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(5);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        if (password != null && !password.isEmpty()) {
            this.jedisPool = new JedisPool(poolConfig, host, port, 2000, password, database);
        } else {
            this.jedisPool = new JedisPool(poolConfig, host, port, 2000, null, database);
        }

        startSubscriber();
        plugin.getLogger().info("[Redis] Connected to Redis at " + host + ":" + port + " (server-id: " + serverId + ")");
    }

    private void startSubscriber() {
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                try {
                    handleMessage(message);
                } catch (Exception e) {
                    plugin.getLogger().warning("[Redis] Error processing message: " + e.getMessage());
                }
            }

            @Override
            public void onSubscribe(String channel, int subscribedChannels) {
                plugin.getLogger().info("[Redis] Subscribed to channel: " + channel);
            }
        };

        subscriberThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.subscribe(pubSub, CHANNEL);
                } catch (Exception e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        plugin.getLogger().warning("[Redis] Subscriber disconnected, reconnecting in 5s: " + e.getMessage());
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        });
        subscriberThread.setDaemon(true);
        subscriberThread.setName("zauctionhouse-redis-sub");
        subscriberThread.start();
    }

    private void handleMessage(String raw) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 7) return;

        Action action;
        try {
            action = Action.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            return;
        }

        int itemId;
        try {
            itemId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return;
        }

        String originServer = parts[6];
        if (serverId.equals(originServer)) return;

        var manager = plugin.getAuctionManager();

        switch (action) {

            case ITEM_LISTED -> {
                plugin.getStorageManager().selectItem(itemId).thenAccept(item -> {
                    if (item == null) return;
                    plugin.getCategoryManager().applyCategories(item);
                    manager.addItem(StorageType.LISTED, item);
                    manager.clearPlayersCache(PlayerCacheKey.ITEMS_LISTED, PlayerCacheKey.ITEMS_SEARCH);
                    manager.rebuildSortedItemsCache();
                }).exceptionally(ex -> {
                    plugin.getLogger().warning("[Redis] Failed to load listed item " + itemId + ": " + ex.getMessage());
                    return null;
                });
            }

            case ITEM_BOUGHT -> {
                // parts[2] = buyerName, parts[3] = formattedPrice (embedded in publishBought)
                String buyerName = parts[2];
                String formattedPrice = parts[3];

                // Find the item in memory before removing it (need sellerUniqueId)
                var listedItems = manager.getItems(StorageType.LISTED, item -> item.getId() == itemId);
                if (!listedItems.isEmpty()) {
                    var soldItem = listedItems.get(0);
                    var sellerUniqueId = soldItem.getSellerUniqueId();
                    var itemDisplay = soldItem.getItemDisplay();

                    // Notify seller if they are online on this server
                    plugin.getScheduler().runNextTick(task -> {
                        var sellerOnline = plugin.getServer().getPlayer(sellerUniqueId);
                        if (sellerOnline != null) {
                            manager.message(sellerOnline, Message.ITEM_BOUGHT_SELLER,
                                    "%buyer%", buyerName,
                                    "%items%", itemDisplay,
                                    "%price%", formattedPrice,
                                    "%seller%", soldItem.getSellerName()
                            );
                            manager.clearPlayerCache(sellerOnline,
                                    PlayerCacheKey.ITEMS_SELLING,
                                    PlayerCacheKey.HISTORY_DATA,
                                    PlayerCacheKey.PENDING_MONEY_DATA
                            );
                        }
                    });
                }

                manager.removeItem(StorageType.LISTED, itemId);
                manager.clearPlayersCache(
                        PlayerCacheKey.ITEMS_LISTED,
                        PlayerCacheKey.ITEMS_SELLING,
                        PlayerCacheKey.ITEMS_SEARCH
                );
                manager.rebuildSortedItemsCache();
                plugin.getLogger().info("[Redis] Item " + itemId + " removed from LISTED (bought by " + buyerName + " on another server)");
            }

            case ITEM_REMOVED -> {
                StorageType sourceStorage = parseStorageType(parts[2]);
                if (sourceStorage == null) return;

                manager.removeItem(sourceStorage, itemId);
                manager.clearPlayersCache(
                        PlayerCacheKey.ITEMS_LISTED,
                        PlayerCacheKey.ITEMS_SELLING,
                        PlayerCacheKey.ITEMS_EXPIRED,
                        PlayerCacheKey.ITEMS_PURCHASED,
                        PlayerCacheKey.ITEMS_SEARCH
                );

                if (sourceStorage == StorageType.LISTED) {
                    manager.rebuildSortedItemsCache();
                }
                plugin.getLogger().info("[Redis] Item " + itemId + " removed from " + sourceStorage + " (removed on another server)");
            }

            case ITEM_STATUS_CHANGE -> {
                ItemStatus newStatus = parseItemStatus(parts[5]);
                if (newStatus == null) return;

                for (StorageType st : StorageType.values()) {
                    var matches = manager.getItems(st, item -> item.getId() == itemId);
                    if (!matches.isEmpty()) {
                        matches.get(0).setStatus(newStatus);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public CompletableFuture<Boolean> checkAvailability(Item item) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.get(LOCK_PREFIX + item.getId()) == null;
            } catch (Exception e) {
                plugin.getLogger().warning("[Redis] checkAvailability error: " + e.getMessage());
                return true;
            }
        }, plugin.getExecutorService());
    }

    @Override
    public CompletableFuture<LockToken> lockItem(Item item, UUID buyerId, StorageType storageType) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String result = jedis.set(
                        LOCK_PREFIX + item.getId(),
                        buyerId.toString(),
                        SetParams.setParams().nx().px(LOCK_TTL_MS)
                );
                return "OK".equals(result) ? LockToken.of(item) : LockToken.noop();
            } catch (Exception e) {
                plugin.getLogger().warning("[Redis] lockItem error: " + e.getMessage());
                return LockToken.noop();
            }
        }, plugin.getExecutorService());
    }

    @Override
    public CompletableFuture<Void> unlockItem(Item item, LockToken lockToken, StorageType storageType) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(LOCK_PREFIX + item.getId());
            } catch (Exception e) {
                plugin.getLogger().warning("[Redis] unlockItem error: " + e.getMessage());
            }
        }, plugin.getExecutorService());
    }

    @Override
    public CompletableFuture<Void> notifyItemBought(Player player, Item item) {
        String buyerName = player.getName();
        String formattedPrice = item.getFormattedPrice();
        return publishBought(item.getId(), buyerName, formattedPrice);
    }

    @Override
    public CompletableFuture<Void> notifyItemListed(Item item) {
        return publish(Action.ITEM_LISTED, item.getId(), StorageType.LISTED, null, null, null);
    }

    @Override
    public CompletableFuture<Void> notifyItemStatusChange(Item item, ItemStatus oldStatus, ItemStatus newStatus) {
        return publish(Action.ITEM_STATUS_CHANGE, item.getId(), null, null, oldStatus, newStatus);
    }

    @Override
    public CompletableFuture<Void> removeItem(Item item, StorageType storageType) {
        return removeItem(item, storageType, null);
    }

    @Override
    public CompletableFuture<Void> removeItem(Item item, StorageType sourceStorageType, StorageType destinationStorageType) {
        return publish(Action.ITEM_REMOVED, item.getId(), sourceStorageType, destinationStorageType, null, null);
    }

    @Override
    public boolean isDistributed() {
        return true;
    }


    private CompletableFuture<Void> publish(Action action, int itemId, StorageType sourceStorage,
                                            StorageType destStorage, ItemStatus oldStatus, ItemStatus newStatus) {
        return CompletableFuture.runAsync(() -> {
            String msg = action.name()
                    + "|" + itemId
                    + "|" + (sourceStorage != null ? sourceStorage.name() : "null")
                    + "|" + (destStorage != null ? destStorage.name() : "null")
                    + "|" + (oldStatus != null ? oldStatus.name() : "null")
                    + "|" + (newStatus != null ? newStatus.name() : "null")
                    + "|" + serverId;
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(CHANNEL, msg);
            } catch (Exception e) {
                plugin.getLogger().warning("[Redis] publish error for action " + action + ": " + e.getMessage());
            }
        }, plugin.getExecutorService());
    }

    private CompletableFuture<Void> publishBought(int itemId, String buyerName, String formattedPrice) {
        return CompletableFuture.runAsync(() -> {
            String msg = Action.ITEM_BOUGHT.name()
                    + "|" + itemId
                    + "|" + buyerName
                    + "|" + formattedPrice
                    + "|null|null"
                    + "|" + serverId;
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(CHANNEL, msg);
            } catch (Exception e) {
                plugin.getLogger().warning("[Redis] publish error for ITEM_BOUGHT: " + e.getMessage());
            }
        }, plugin.getExecutorService());
    }


    public void shutdown() {
        plugin.getLogger().info("[Redis] Shutting down cluster bridge...");
        if (pubSub != null && pubSub.isSubscribed()) {
            try {
                pubSub.unsubscribe();
            } catch (Exception ignored) {
            }
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
        plugin.getLogger().info("[Redis] Cluster bridge shut down.");
    }

    private StorageType parseStorageType(String value) {
        if (value == null || "null".equals(value)) return null;
        try {
            return StorageType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ItemStatus parseItemStatus(String value) {
        if (value == null || "null".equals(value)) return null;
        try {
            return ItemStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
