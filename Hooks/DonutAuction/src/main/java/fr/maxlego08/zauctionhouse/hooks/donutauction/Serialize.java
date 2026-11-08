package fr.maxlego08.zauctionhouse.hooks.donutauction;

import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputFilter;
import java.util.Base64;

/**
 * Deserializes DonutAuction item data.
 * <p>
 * DonutAuction stores items via {@code Base64Coder.encodeLines(BukkitObjectOutputStream(item))},
 * i.e. legacy Bukkit (Java) serialization, Base64-encoded <b>with line wrapping</b>
 * (a CRLF is inserted every 76 characters). We therefore decode with a MIME decoder,
 * which ignores the embedded line breaks, before reading the {@link ItemStack}.
 * <p>
 * <b>Security:</b> Java deserialization of untrusted data is dangerous (gadget-chain RCE). A
 * migration imports a data file whose provenance is not guaranteed (it may be copied from another
 * host or a backup), so the {@link ObjectInputFilter} below restricts the stream to the classes
 * that legitimately occur in a Bukkit-serialized {@link ItemStack} (Bukkit's serialization
 * {@code Wrapper}, the Guava {@code ImmutableMap} returned by item-meta serialization, and core
 * JDK value/collection types) and bounds the stream size/depth to mitigate denial of service.
 * Anything else (e.g. {@code org.apache.commons.*}, {@code sun.*}, {@code com.sun.*} gadgets) is
 * rejected, which causes the offending item to be skipped rather than deserialized.
 */
public final class Serialize {

    // Generous bounds: a single serialized ItemStack is tiny; these only stop pathological payloads.
    private static final long MAX_STREAM_BYTES = 5_000_000L;
    private static final long MAX_DEPTH = 50L;
    private static final long MAX_REFERENCES = 10_000L;
    private static final long MAX_ARRAY_LENGTH = 200_000L;

    private static final ObjectInputFilter ITEM_FILTER = Serialize::filter;

    private Serialize() {
    }

    public static ItemStack deserializeLegacy(String data, AuctionPlugin plugin) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(data);
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
                 BukkitObjectInputStream objectInputStream = new BukkitObjectInputStream(inputStream)) {
                // Must be set after construction (the header is read in the constructor) and before readObject().
                objectInputStream.setObjectInputFilter(ITEM_FILTER);
                return (ItemStack) objectInputStream.readObject();
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to deserialize DonutAuction ItemStack: "
                    + exception.getClass().getSimpleName() + " - " + exception.getMessage());
            plugin.getLogger().warning("Server version: " + Bukkit.getBukkitVersion()
                    + " - data length: " + data.length());
            return null;
        }
    }

    private static ObjectInputFilter.Status filter(ObjectInputFilter.FilterInfo info) {
        // Resource limits (these checks also run when serialClass() is null, e.g. array-length checks).
        if (info.depth() > MAX_DEPTH
                || info.references() > MAX_REFERENCES
                || info.streamBytes() > MAX_STREAM_BYTES
                || (info.arrayLength() >= 0 && info.arrayLength() > MAX_ARRAY_LENGTH)) {
            return ObjectInputFilter.Status.REJECTED;
        }

        Class<?> clazz = info.serialClass();
        if (clazz == null) {
            return ObjectInputFilter.Status.UNDECIDED;
        }

        // Arrays are matched by their (innermost) component type; primitive arrays are always safe.
        while (clazz.isArray()) {
            clazz = clazz.getComponentType();
        }
        if (clazz.isPrimitive() || isAllowedClass(clazz.getName())) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        return ObjectInputFilter.Status.REJECTED;
    }

    private static boolean isAllowedClass(String name) {
        return name.startsWith("org.bukkit.")          // Bukkit's serialization Wrapper + item classes
                || name.startsWith("net.minecraft.")    // NMS-backed component data (defensive)
                || name.startsWith("com.mojang.")       // Mojang component data (defensive)
                || name.startsWith("com.google.common.")// Guava ImmutableMap from CraftMetaItem#serialize
                || name.startsWith("java.lang.")        // String, Integer, Boolean, Number, ...
                || name.startsWith("java.util.")        // LinkedHashMap, HashMap, ArrayList, ...
                || name.startsWith("java.math.");       // BigInteger / BigDecimal
    }
}
