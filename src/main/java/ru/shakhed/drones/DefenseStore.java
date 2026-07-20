package ru.shakhed.drones;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class DefenseStore {
    public enum Type { EW, RADAR, ANTI_AIR, MISSILE_STATION, AUTO_PRO }
    public enum AmmoType { BULLET, MISSILE }

    private final ShakhedDronesPlugin plugin;
    private final File file;
    private final Set<String> ew = new HashSet<>();
    private final Set<String> radars = new HashSet<>();
    private final Set<String> antiAir = new HashSet<>();
    private final Set<String> missileStations = new HashSet<>();
    private final Set<String> autoPro = new HashSet<>();
    private final Map<String, Double> batteries = new HashMap<>();
    private final Map<String, Integer> bulletCrates = new HashMap<>();
    private final Map<String, Integer> missileCrates = new HashMap<>();
    private final Map<String, Double> fuelCanisters = new HashMap<>();
    private final Set<String> poweredEw = new HashSet<>();
    private final Set<String> poweredRadars = new HashSet<>();
    private final Set<String> poweredMissileStations = new HashSet<>();
    private final Set<String> poweredAutoPro = new HashSet<>();
    private final Map<String, String> connections = new HashMap<>();

    public DefenseStore(ShakhedDronesPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "defenses.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ew.addAll(yaml.getStringList("ew"));
        radars.addAll(yaml.getStringList("radars"));
        antiAir.addAll(yaml.getStringList("anti-air"));
        missileStations.addAll(yaml.getStringList("missile-stations"));
        autoPro.addAll(yaml.getStringList("auto-pro"));
        for (String raw : yaml.getStringList("batteries")) {
            int separator = raw.lastIndexOf('|');
            if (separator < 0) continue;
            try { batteries.put(raw.substring(0, separator), Double.parseDouble(raw.substring(separator + 1))); }
            catch (NumberFormatException ignored) { }
        }
        loadAmmo(yaml.getStringList("bullet-crates"), bulletCrates);
        loadAmmo(yaml.getStringList("missile-crates"), missileCrates);
        loadDoubleValues(yaml.getStringList("fuel-canisters"), fuelCanisters);
        for (String raw : yaml.getStringList("connections")) {
            int separator = raw.indexOf('|');
            if (separator > 0) connections.put(raw.substring(0, separator), raw.substring(separator + 1));
        }
    }

    public void add(Location location, Type type) { set(type).add(key(location)); save(); }
    public void addBattery(Location location) {
        batteries.put(key(location), plugin.getConfig().getDouble("defense.ew-battery-capacity-seconds", 600));
        save();
    }
    public void addFuelCanister(Location location) {
        fuelCanisters.put(key(location), plugin.getConfig().getDouble("missiles.canister-fuel", 20.0));
        save();
    }
    public void addAmmoCrate(Location location, AmmoType type, int amount) {
        ammoMap(type).put(key(location), amount);
        save();
    }
    public void remove(Location location) {
        String removedKey = key(location);
        ew.remove(removedKey); radars.remove(removedKey); antiAir.remove(removedKey);
        missileStations.remove(removedKey); autoPro.remove(removedKey); batteries.remove(removedKey);
        connections.remove(removedKey); connections.values().removeIf(removedKey::equals);
        bulletCrates.remove(key(location)); missileCrates.remove(key(location)); fuelCanisters.remove(key(location)); save();
    }
    public boolean is(Location location, Type type) { return set(type).contains(key(location)); }
    public boolean isBattery(Location location) { return batteries.containsKey(key(location)); }
    public boolean isFuelCanister(Location location) { return fuelCanisters.containsKey(key(location)); }
    public double fuelAt(Location location) { return fuelCanisters.getOrDefault(key(location), 0.0); }
    public boolean isAmmoCrate(Location location, AmmoType type) { return ammoMap(type).containsKey(key(location)); }
    public int ammoAt(Location location, AmmoType type) { return ammoMap(type).getOrDefault(key(location), 0); }

    public boolean connect(Location station, Location battery) {
        if ((!is(station, Type.EW) && !is(station, Type.RADAR) && !is(station, Type.MISSILE_STATION)
                && !is(station, Type.AUTO_PRO)) || !isBattery(battery)
                || !station.getWorld().equals(battery.getWorld())) return false;
        double max = plugin.getConfig().getDouble("defense.ew-max-wire-length", 32.0);
        if (station.distanceSquared(battery) > max * max) return false;
        connections.put(key(station), key(battery));
        save();
        return true;
    }

    public int ammoNear(Location gun, AmmoType type) {
        double range = plugin.getConfig().getDouble("defense.anti-air-crate-range", 4.0);
        double squared = range * range;
        int total = 0;
        for (Map.Entry<String, Integer> entry : ammoMap(type).entrySet()) {
            Location location = parseLocation(entry.getKey());
            if (location != null && location.getWorld().equals(gun.getWorld()) && location.distanceSquared(gun) <= squared) {
                total += entry.getValue();
            }
        }
        return total;
    }

    public boolean consumeAmmoNear(Location gun, AmmoType type) {
        double range = plugin.getConfig().getDouble("defense.anti-air-crate-range", 4.0);
        double squared = range * range;
        for (Map.Entry<String, Integer> entry : ammoMap(type).entrySet()) {
            if (entry.getValue() <= 0) continue;
            Location location = parseLocation(entry.getKey());
            if (location != null && location.getWorld().equals(gun.getWorld()) && location.distanceSquared(gun) <= squared) {
                entry.setValue(entry.getValue() - 1);
                save();
                return true;
            }
        }
        return false;
    }

    public boolean consumeFuelNear(Location station, double amount) {
        double range = plugin.getConfig().getDouble("missiles.supply-range", 5.0);
        double squared = range * range;
        for (Map.Entry<String, Double> entry : fuelCanisters.entrySet()) {
            if (entry.getValue() < amount) continue;
            Location location = parseLocation(entry.getKey());
            if (location != null && location.getWorld().equals(station.getWorld())
                    && location.distanceSquared(station) <= squared) {
                entry.setValue(entry.getValue() - amount);
                save();
                return true;
            }
        }
        return false;
    }

    public double fuelNear(Location station) {
        double range = plugin.getConfig().getDouble("missiles.supply-range", 5.0);
        double squared = range * range;
        double total = 0.0;
        for (Map.Entry<String, Double> entry : fuelCanisters.entrySet()) {
            Location location = parseLocation(entry.getKey());
            if (location != null && location.getWorld().equals(station.getWorld())
                    && location.distanceSquared(station) <= squared) total += entry.getValue();
        }
        return total;
    }

    public void tickPower() {
        poweredEw.clear();
        poweredRadars.clear();
        poweredMissileStations.clear();
        poweredAutoPro.clear();
        double drain = plugin.getConfig().getDouble("defense.ew-idle-drain-per-second", 1.0);
        boolean changed = false;
        List<Location> poweredStations = new ArrayList<>();
        poweredStations.addAll(locations(Type.EW));
        poweredStations.addAll(locations(Type.RADAR));
        poweredStations.addAll(locations(Type.MISSILE_STATION));
        poweredStations.addAll(locations(Type.AUTO_PRO));
        for (Location station : poweredStations) {
            if (!station.getWorld().isChunkLoaded(station.getBlockX() >> 4, station.getBlockZ() >> 4)) continue;
            if (station.getBlock().getType() != Material.BARRIER) continue;
            Location battery = findConnectedBattery(station);
            if (battery == null) continue;
            String batteryKey = key(battery);
            double remaining = batteries.getOrDefault(batteryKey, 0.0);
            if (remaining <= 0) continue;
            batteries.put(batteryKey, Math.max(0, remaining - drain));
            if (is(station, Type.EW)) poweredEw.add(key(station));
            if (is(station, Type.RADAR)) poweredRadars.add(key(station));
            if (is(station, Type.MISSILE_STATION)) poweredMissileStations.add(key(station));
            if (is(station, Type.AUTO_PRO)) poweredAutoPro.add(key(station));
            changed = true;
        }
        if (changed) save();
    }

    public String powerStatus(Location station) {
        Location battery = findConnectedBattery(station);
        if (battery == null) return "нет подключённой заряженной батареи";
        return "питание включено, осталось " + (int) Math.ceil(batteries.getOrDefault(key(battery), 0.0)) + " сек.";
    }

    public boolean isPowered(Location station, Type type) {
        String stationKey = key(station);
        return switch (type) {
            case EW -> poweredEw.contains(stationKey);
            case RADAR -> poweredRadars.contains(stationKey);
            case MISSILE_STATION -> poweredMissileStations.contains(stationKey);
            case AUTO_PRO -> poweredAutoPro.contains(stationKey);
            case ANTI_AIR -> true;
        };
    }

    public Location missileStationCenterAt(Location block) {
        for (Location center : locations(Type.MISSILE_STATION)) {
            if (!center.getWorld().equals(block.getWorld()) || center.getBlockY() != block.getBlockY()) continue;
            if (Math.abs(center.getBlockX() - block.getBlockX()) <= 1
                    && Math.abs(center.getBlockZ() - block.getBlockZ()) <= 1) return center;
        }
        return null;
    }

    public boolean isJammed(Location location) {
        double radius = plugin.getConfig().getDouble("defense.ew-radius", 65.0);
        double radiusSquared = radius * radius;
        for (Location station : locations(Type.EW)) {
            if (poweredEw.contains(key(station)) && station.getWorld().equals(location.getWorld())
                    && station.distanceSquared(location) <= radiusSquared) return true;
        }
        return false;
    }

    public Location detectingRadar(Location location) {
        double radius = plugin.getConfig().getDouble("defense.radar-radius", 180.0);
        double radiusSquared = radius * radius;
        for (Location radar : locations(Type.RADAR)) {
            if (poweredRadars.contains(key(radar)) && radar.getWorld().equals(location.getWorld())
                    && radar.distanceSquared(location) <= radiusSquared) return radar;
        }
        return null;
    }

    private Location findConnectedBattery(Location station) {
        String batteryKey = connections.get(key(station));
        if (batteryKey == null || batteries.getOrDefault(batteryKey, 0.0) <= 0) return null;
        return parseLocation(batteryKey);
    }

    public List<Location> locations(Type type) {
        List<Location> result = new ArrayList<>();
        for (String raw : set(type)) {
            String[] parts = raw.split(":");
            if (parts.length != 4) continue;
            try {
                World world = Bukkit.getWorld(UUID.fromString(parts[0]));
                if (world != null) result.add(new Location(world, Integer.parseInt(parts[1]) + 0.5,
                        Integer.parseInt(parts[2]) + 0.5, Integer.parseInt(parts[3]) + 0.5));
            } catch (IllegalArgumentException ignored) { }
        }
        return result;
    }

    private Location parseLocation(String raw) {
        String[] parts = raw.split(":");
        if (parts.length != 4) return null;
        try {
            World world = Bukkit.getWorld(UUID.fromString(parts[0]));
            if (world == null) return null;
            return new Location(world, Integer.parseInt(parts[1]) + 0.5,
                    Integer.parseInt(parts[2]) + 0.5, Integer.parseInt(parts[3]) + 0.5);
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private Map<String, Integer> ammoMap(AmmoType type) { return type == AmmoType.BULLET ? bulletCrates : missileCrates; }

    private void loadAmmo(List<String> values, Map<String, Integer> target) {
        for (String raw : values) {
            int separator = raw.lastIndexOf('|');
            if (separator < 0) continue;
            try { target.put(raw.substring(0, separator), Integer.parseInt(raw.substring(separator + 1))); }
            catch (NumberFormatException ignored) { }
        }
    }

    private void loadDoubleValues(List<String> values, Map<String, Double> target) {
        for (String raw : values) {
            int separator = raw.lastIndexOf('|');
            if (separator < 0) continue;
            try { target.put(raw.substring(0, separator), Double.parseDouble(raw.substring(separator + 1))); }
            catch (NumberFormatException ignored) { }
        }
    }

    private Set<String> set(Type type) {
        return switch (type) {
            case EW -> ew;
            case RADAR -> radars;
            case ANTI_AIR -> antiAir;
            case MISSILE_STATION -> missileStations;
            case AUTO_PRO -> autoPro;
        };
    }
    private String key(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("ew", ew.stream().sorted().toList());
        yaml.set("radars", radars.stream().sorted().toList());
        yaml.set("anti-air", antiAir.stream().sorted().toList());
        yaml.set("missile-stations", missileStations.stream().sorted().toList());
        yaml.set("auto-pro", autoPro.stream().sorted().toList());
        yaml.set("batteries", batteries.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "|" + entry.getValue()).toList());
        yaml.set("bullet-crates", bulletCrates.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "|" + entry.getValue()).toList());
        yaml.set("missile-crates", missileCrates.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "|" + entry.getValue()).toList());
        yaml.set("fuel-canisters", fuelCanisters.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "|" + entry.getValue()).toList());
        yaml.set("connections", connections.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "|" + entry.getValue()).toList());
        try { yaml.save(file); }
        catch (IOException exception) { plugin.getLogger().severe("Не удалось сохранить defenses.yml: " + exception.getMessage()); }
    }

}
