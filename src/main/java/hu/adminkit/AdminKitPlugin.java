package hu.adminkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.potion.PotionType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AdminKitPlugin extends JavaPlugin {

    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
    private File dataFile;
    private YamlConfiguration data;
    // jatekos -> utolso claim ideje (ms)
    private final Map<UUID, Long> lastClaim = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        getLogger().info("AdminKit betoltve. Cooldown: " + getCooldownHours() + " ora.");
    }

    private double getCooldownHours() {
        return getConfig().getDouble("cooldown-hours", 24.0);
    }

    private long getCooldownMillis() {
        return (long) (getCooldownHours() * 60 * 60 * 1000);
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "claimed.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        lastClaim.clear();
        ConfigurationSection sec = data.getConfigurationSection("claims");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    lastClaim.put(UUID.fromString(key), sec.getLong(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void saveData() {
        data.set("claims", null);
        for (Map.Entry<UUID, Long> e : lastClaim.entrySet()) {
            data.set("claims." + e.getKey(), e.getValue());
        }
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Nem sikerult menteni a claimed.yml-t: " + e.getMessage());
        }
    }

    private Component msg(String key) {
        return msg(key, null, null);
    }

    private Component msg(String key, String placeholder, String value) {
        FileConfiguration c = getConfig();
        String text = c.getString("messages.prefix", "") + c.getString("messages." + key, key);
        if (placeholder != null) text = text.replace(placeholder, value);
        return legacy.deserialize(text);
    }

    private String formatTime(long millis) {
        long totalSeconds = Math.max(1, millis / 1000);
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append(" ora ");
        if (m > 0) sb.append(m).append(" perc ");
        if (h == 0) sb.append(s).append(" masodperc");
        return sb.toString().trim();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Csak OP-k hasznalhatjak az egesz parancsot
        if (!sender.isOp()) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }

        String sub = args.length == 0 ? "claim" : args[0].toLowerCase();

        switch (sub) {
            case "claim" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Ezt csak jatekos hasznalhatja.");
                    return true;
                }
                long now = System.currentTimeMillis();
                Long last = lastClaim.get(player.getUniqueId());
                if (last != null) {
                    long remaining = last + getCooldownMillis() - now;
                    if (remaining > 0) {
                        player.sendMessage(msg("cooldown", "%time%", formatTime(remaining)));
                        return true;
                    }
                }
                giveKit(player);
                lastClaim.put(player.getUniqueId(), now);
                saveData();
                player.sendMessage(msg("claimed"));
            }
            case "reset" -> {
                if (args.length < 2) {
                    sender.sendMessage("Hasznalat: /" + label + " reset <jatekos>");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null) {
                    sender.sendMessage(msg("reset-not-found", "%player%", args[1]));
                    return true;
                }
                lastClaim.remove(target.getUniqueId());
                saveData();
                sender.sendMessage(msg("reset-done", "%player%", args[1]));
            }
            case "reload" -> {
                reloadConfig();
                loadData();
                sender.sendMessage(msg("reloaded"));
            }
            default -> sender.sendMessage("Hasznalat: /" + label + " [claim|reset <jatekos>|reload]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) return List.of();
        if (args.length == 1) return List.of("claim", "reset", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        return List.of();
    }

    private void giveKit(Player player) {
        PlayerInventory inv = player.getInventory();
        boolean equip = getConfig().getBoolean("equip-armor", true);
        boolean dropped = false;

        for (Map<?, ?> entry : getConfig().getMapList("items")) {
            ItemStack item = buildItem(entry);
            if (item == null) continue;

            // Fix slot(ok): 0-8 hotbar, 9-35 taska, 40 masik kez
            List<Integer> slots = getSlots(entry);
            if (!slots.isEmpty()) {
                for (int s : slots) {
                    ItemStack copy = item.clone();
                    if (s >= 0 && s <= 40 && inv.getItem(s) == null) {
                        inv.setItem(s, copy);
                    } else if (addOrDrop(player, copy)) {
                        dropped = true;
                    }
                }
                continue;
            }

            if (equip && tryEquip(inv, item)) continue;

            if (addOrDrop(player, item)) dropped = true;
        }
        if (dropped) player.sendMessage(msg("inventory-full"));
    }

    private List<Integer> getSlots(Map<?, ?> entry) {
        List<Integer> result = new ArrayList<>();
        Object single = entry.get("slot");
        if (single instanceof Number n) result.add(n.intValue());
        Object multi = entry.get("slots");
        if (multi instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) result.add(n.intValue());
            }
        }
        return result;
    }

    /** @return true ha a targy a foldre esett */
    private boolean addOrDrop(Player player, ItemStack item) {
        boolean dropped = false;
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
            dropped = true;
        }
        return dropped;
    }

    private boolean tryEquip(PlayerInventory inv, ItemStack item) {
        EquipmentSlot slot = item.getType().getEquipmentSlot();
        switch (slot) {
            case HEAD -> {
                if (inv.getHelmet() == null) { inv.setHelmet(item); return true; }
            }
            case CHEST -> {
                if (inv.getChestplate() == null) { inv.setChestplate(item); return true; }
            }
            case LEGS -> {
                if (inv.getLeggings() == null) { inv.setLeggings(item); return true; }
            }
            case FEET -> {
                if (inv.getBoots() == null) { inv.setBoots(item); return true; }
            }
            default -> {
            }
        }
        return false;
    }

    private ItemStack buildItem(Map<?, ?> entry) {
        Object matObj = entry.get("material");
        if (matObj == null) return null;
        Material mat = Material.matchMaterial(String.valueOf(matObj));
        if (mat == null || !mat.isItem()) {
            getLogger().warning("Ismeretlen material: " + matObj);
            return null;
        }

        int amount = 1;
        Object amt = entry.get("amount");
        if (amt instanceof Number n) amount = Math.max(1, Math.min(n.intValue(), mat.getMaxStackSize()));

        ItemStack item = new ItemStack(mat, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        Object name = entry.get("name");
        if (name != null) {
            meta.displayName(legacy.deserialize(String.valueOf(name))
                    .decoration(TextDecoration.ITALIC, false));
        }

        Object lore = entry.get("lore");
        if (lore instanceof List<?> list) {
            List<Component> lines = new ArrayList<>();
            for (Object l : list) {
                lines.add(legacy.deserialize(String.valueOf(l))
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
        }

        Object ench = entry.get("enchants");
        if (ench instanceof List<?> list) {
            for (Object e : list) {
                String[] parts = String.valueOf(e).split(":");
                Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0].toLowerCase()));
                if (enchantment == null) {
                    getLogger().warning("Ismeretlen enchant: " + parts[0]);
                    continue;
                }
                int level = 1;
                if (parts.length > 1) {
                    try {
                        level = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                meta.addEnchant(enchantment, level, true);
            }
        }

        // Pancel trim: trim: {pattern: sentry, material: diamond}
        Object trimObj = entry.get("trim");
        if (trimObj instanceof Map<?, ?> trimMap && meta instanceof ArmorMeta armorMeta) {
            String patternName = String.valueOf(trimMap.get("pattern")).toLowerCase();
            String materialName = String.valueOf(trimMap.get("material")).toLowerCase();
            TrimPattern pattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(patternName));
            TrimMaterial trimMaterial = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(materialName));
            if (pattern == null || trimMaterial == null) {
                getLogger().warning("Ismeretlen trim: " + patternName + " / " + materialName);
            } else {
                armorMeta.setTrim(new ArmorTrim(trimMaterial, pattern));
            }
        }

        // Potion: potion: strong_swiftness
        Object potionObj = entry.get("potion");
        if (potionObj != null && meta instanceof PotionMeta potionMeta) {
            PotionType type = Registry.POTION.get(NamespacedKey.minecraft(String.valueOf(potionObj).toLowerCase()));
            if (type == null) {
                getLogger().warning("Ismeretlen potion: " + potionObj);
            } else {
                potionMeta.setBasePotionType(type);
            }
        }

        Object unbreakable = entry.get("unbreakable");
        if (unbreakable instanceof Boolean b && b) meta.setUnbreakable(true);

        item.setItemMeta(meta);
        return item;
    }
}
