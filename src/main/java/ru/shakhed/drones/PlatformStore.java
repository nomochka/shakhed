package ru.shakhed.drones;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public final class PlatformStore {
    private final ShakhedDronesPlugin plugin;
    private final File file;
    private final Set<String> platforms = new HashSet<>();

    public PlatformStore(ShakhedDronesPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "platforms.yml");
        load();
    }

    public boolean contains(Location location) {
        String key = key(location);
        if (!platforms.contains(key)) return false;
        if (location.getBlock().getType() == org.bukkit.Material.BARRIER) return true;
        platforms.remove(key);
        save();
        return false;
    }
    public void add(Location location) { platforms.add(key(location)); save(); }
    public void remove(Location location) { platforms.remove(key(location)); save(); }

    private String key(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        platforms.addAll(yaml.getStringList("platforms"));
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("platforms", platforms.stream().sorted().toList());
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Не удалось сохранить platforms.yml: " + exception.getMessage());
        }
    }
}
