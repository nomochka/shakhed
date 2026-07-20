package ru.shakhed.drones;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class ShakhedDronesPlugin extends JavaPlugin {
    private final Map<DroneType, DroneSettings> settings = new EnumMap<>(DroneType.class);
    private ItemFactory items;
    private PlatformStore platforms;
    private DefenseStore defenses;
    private FlightManager flights;
    private ResourcePackServer resourcePack;
    private MissileManager missiles;
    private final Map<UUID, DroneTarget> targets = new HashMap<>();
    private final Set<UUID> portableEw = new HashSet<>();
    private final Map<UUID, Integer> portableCharge = new HashMap<>();
    private final Map<UUID, Long> portableToggleTimes = new HashMap<>();

    public NamespacedKey entityKindKey;
    public NamespacedKey entityTypeKey;
    public NamespacedKey ownerKey;
    public NamespacedKey displayKey;
    public NamespacedKey batteryKey;
    public NamespacedKey ammoKey;
    public NamespacedKey directionXKey;
    public NamespacedKey directionYKey;
    public NamespacedKey directionZKey;
    public NamespacedKey deviceBlockKey;
    public NamespacedKey payloadKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        createKeys();
        loadDroneSettings();
        items = new ItemFactory(this);
        platforms = new PlatformStore(this);
        defenses = new DefenseStore(this);
        flights = new FlightManager(this);
        missiles = new MissileManager(this);
        resourcePack = new ResourcePackServer(this);
        DroneListener listener = new DroneListener(this);
        getServer().getPluginManager().registerEvents(listener, this);

        DroneCommand command = new DroneCommand(this);
        PluginCommand droneCommand = getCommand("drone");
        if (droneCommand != null) {
            droneCommand.setExecutor(command);
            droneCommand.setTabCompleter(command);
        }
        flights.start();
        missiles.start();
        resourcePack.start();
        getServer().getScheduler().runTaskTimer(this, this::tickPortableEw, 20L, 20L);
        getLogger().info("ShakhedDrones включён. Автор: nome");
    }

    @Override
    public void onDisable() {
        if (flights != null) flights.shutdown();
        if (missiles != null) missiles.shutdown();
        if (resourcePack != null) resourcePack.stop();
    }

    public void reloadPluginConfig() {
        reloadConfig();
        loadDroneSettings();
    }

    private void loadDroneSettings() {
        settings.clear();
        for (DroneType type : DroneType.values()) {
            String path = "drones." + type.id() + ".";
            settings.put(type, new DroneSettings(
                    getConfig().getDouble(path + "range"),
                    getConfig().getDouble(path + "speed"),
                    (float) getConfig().getDouble(path + "explosion-power"),
                    getConfig().getDouble(path + "health", 10.0)
            ));
        }
    }

    private void createKeys() {
        entityKindKey = new NamespacedKey(this, "entity_kind");
        entityTypeKey = new NamespacedKey(this, "drone_type");
        ownerKey = new NamespacedKey(this, "owner");
        displayKey = new NamespacedKey(this, "display");
        batteryKey = new NamespacedKey(this, "battery");
        ammoKey = new NamespacedKey(this, "tnt_ammo");
        directionXKey = new NamespacedKey(this, "direction_x");
        directionYKey = new NamespacedKey(this, "direction_y");
        directionZKey = new NamespacedKey(this, "direction_z");
        deviceBlockKey = new NamespacedKey(this, "device_block");
        payloadKey = new NamespacedKey(this, "payload");
    }

    public void message(org.bukkit.command.CommandSender target, String text) {
        String prefix = getConfig().getString("messages.prefix", "&8[&cДроны&8] &f");
        target.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + text));
    }

    public DroneSettings settings(DroneType type) { return settings.get(type); }
    public ItemFactory items() { return items; }
    public PlatformStore platforms() { return platforms; }
    public DefenseStore defenses() { return defenses; }
    public FlightManager flights() { return flights; }
    public ResourcePackServer resourcePack() { return resourcePack; }
    public MissileManager missiles() { return missiles; }
    public void target(UUID player, Location location, double cruiseAltitude) {
        targets.put(player, new DroneTarget(location.clone(), cruiseAltitude));
    }
    public DroneTarget target(UUID player) {
        DroneTarget target = targets.get(player);
        return target == null ? null : new DroneTarget(target.location.clone(), target.cruiseAltitude);
    }
    public void clearTarget(UUID player) { targets.remove(player); }

    public void togglePortableEw(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now - portableToggleTimes.getOrDefault(id, 0L) < 350L) return;
        portableToggleTimes.put(id, now);
        if (portableEw.remove(id)) {
            portableCharge.remove(id);
            message(player, "Переносной РЭБ выключен.");
            return;
        }
        if (!consumePortableBattery(player)) {
            message(player, "В инвентаре нет батареи переносного РЭБ.");
            return;
        }
        portableEw.add(id);
        portableCharge.put(id, getConfig().getInt("defense.portable-ew-battery-seconds", 60));
        message(player, "Переносной РЭБ включён.");
    }

    public boolean isPortableJammed(Location location) {
        double radius = getConfig().getDouble("defense.portable-ew-radius", 22.0);
        double squared = radius * radius;
        for (UUID id : portableEw) {
            Player player = getServer().getPlayer(id);
            if (player != null && player.getWorld().equals(location.getWorld())
                    && player.getLocation().distanceSquared(location) <= squared) return true;
        }
        return false;
    }

    private void tickPortableEw() {
        for (UUID id : new HashSet<>(portableEw)) {
            Player player = getServer().getPlayer(id);
            if (player == null || !items.isPortableEw(player.getInventory().getItemInMainHand())) {
                portableEw.remove(id);
                portableCharge.remove(id);
                continue;
            }
            int seconds = portableCharge.getOrDefault(id, 0) - 1;
            if (seconds <= 0) {
                if (!consumePortableBattery(player)) {
                    portableEw.remove(id);
                    portableCharge.remove(id);
                    message(player, "Переносной РЭБ выключен: батареи закончились.");
                    continue;
                }
                seconds = getConfig().getInt("defense.portable-ew-battery-seconds", 60);
            }
            portableCharge.put(id, seconds);
            player.sendActionBar(Component.text("РЭБ АКТИВЕН • батарея " + seconds + "с", NamedTextColor.AQUA));
        }
    }

    private boolean consumePortableBattery(Player player) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!items.isPortablePowerCell(item)) continue;
            if (item.getAmount() <= 1) player.getInventory().setItem(slot, null);
            else item.setAmount(item.getAmount() - 1);
            return true;
        }
        return false;
    }

    public record DroneTarget(Location location, double cruiseAltitude) { }
}
