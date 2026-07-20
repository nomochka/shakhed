package ru.shakhed.drones;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class DroneCommand implements CommandExecutor, TabCompleter {
    private final ShakhedDronesPlugin plugin;

    public DroneCommand(ShakhedDronesPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            plugin.message(sender, "Дроны: shakhed, fp-1, geran-4, jet, copter; ракеты: flamingo, oreshnik, iskander, loitering; устройства: missile-station, fuel-canister, auto-pro, radar, ew, aa");
            return true;
        }
        if (args[0].equalsIgnoreCase("pack") && sender instanceof Player player) {
            plugin.resourcePack().send(player);
            plugin.message(player, "Ресурспак v0.2 отправлен повторно.");
            return true;
        }
        if (args[0].equalsIgnoreCase("exit") && sender instanceof Player player) {
            plugin.flights().exitCopter(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("target")) {
            if (!(sender instanceof Player player)) {
                plugin.message(sender, "Цель задаётся игроком.");
                return true;
            }
            if (!player.hasPermission("shakheddrones.use")) {
                plugin.message(sender, "Нет права задавать цель.");
                return true;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("clear")) {
                plugin.clearTarget(player.getUniqueId());
                plugin.message(player, "Координаты цели сброшены.");
                return true;
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("look")) {
                Block block = player.getTargetBlockExact(256);
                if (block == null) {
                    plugin.message(player, "В направлении взгляда нет блока в пределах 256 блоков.");
                    return true;
                }
                double altitude = parseAltitude(args[2]);
                plugin.target(player.getUniqueId(), block.getLocation().add(0.5, 0.5, 0.5), altitude);
                plugin.message(player, "Цель: " + block.getX() + " " + block.getY() + " " + block.getZ()
                        + ", эшелон Y=" + (int) altitude + ".");
                return true;
            }
            if (args.length != 5) {
                plugin.message(player, "Использование: /drone target <x> <y> <z> <высота до 140> или /drone target look <высота>");
                return true;
            }
            try {
                double x = parseCoordinate(args[1], player.getLocation().getX());
                double y = parseCoordinate(args[2], player.getLocation().getY());
                double z = parseCoordinate(args[3], player.getLocation().getZ());
                double altitude = parseAltitude(args[4]);
                plugin.target(player.getUniqueId(), new org.bukkit.Location(player.getWorld(), x + 0.5, y + 0.5, z + 0.5), altitude);
                plugin.message(player, "Цель задана: " + (int) x + " " + (int) y + " " + (int) z
                        + ", эшелон Y=" + (int) altitude + ".");
            } catch (NumberFormatException exception) {
                plugin.message(player, "Координаты должны быть числами; поддерживается запись ~ и ~10.");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("shakheddrones.admin")) return noAdmin(sender);
            plugin.reloadPluginConfig();
            plugin.message(sender, "Конфигурация перезагружена.");
            return true;
        }
        if (!args[0].equalsIgnoreCase("give") || args.length < 2) return false;
        if (!sender.hasPermission("shakheddrones.admin")) return noAdmin(sender);
        if (!(sender instanceof Player player)) {
            plugin.message(sender, "Эту команду пока можно выполнить только от игрока.");
            return true;
        }

        ItemStack item;
        String id = args[1].toLowerCase(Locale.ROOT);
        if (id.equals("pad")) item = plugin.items().launchPad();
        else if (id.equals("battery")) item = plugin.items().battery();
        else if (id.equals("controller")) item = plugin.items().controller();
        else if (id.equals("ew")) item = plugin.items().ewStation();
        else if (id.equals("radar")) item = plugin.items().radar();
        else if (id.equals("missile-station")) item = plugin.items().missileStation();
        else if (id.equals("fuel-canister")) item = plugin.items().fuelCanister();
        else if (id.equals("auto-pro")) item = plugin.items().autoPro();
        else if (id.equals("ew-battery")) item = plugin.items().ewBattery();
        else if (id.equals("wire")) item = plugin.items().wire();
        else if (id.equals("aa")) item = plugin.items().antiAir();
        else if (id.equals("bullets")) item = plugin.items().bulletCrate();
        else if (id.equals("missiles")) item = plugin.items().missileCrate();
        else if (id.equals("portable-ew")) item = plugin.items().portableEw();
        else if (id.equals("portable-battery")) item = plugin.items().portableBattery();
        else if (id.equals("antipersonnel")) item = plugin.items().antipersonnelPayload();
        else {
            MissileType missileType = MissileType.byId(id).orElse(null);
            if (missileType != null) item = plugin.items().strikeMissile(missileType);
            else {
                DroneType type = DroneType.byId(id).orElse(null);
                if (type == null) {
                plugin.message(sender, "Неизвестный тип. Используйте /drone list");
                return true;
                }
                item = plugin.items().drone(type);
            }
        }
        int amount = 1;
        if (args.length >= 3) {
            try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[2]))); }
            catch (NumberFormatException ignored) { }
        }
        item.setAmount(amount);
        player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        plugin.message(sender, "Предмет выдан.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> values = new ArrayList<>();
        if (args.length == 1) values.addAll(List.of("give", "target", "exit", "pack", "list", "reload"));
        if (args.length == 2 && args[0].equalsIgnoreCase("target")) values.addAll(List.of("look", "clear"));
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            values.addAll(Arrays.stream(DroneType.values()).map(DroneType::id).toList());
            values.addAll(Arrays.stream(MissileType.values()).map(MissileType::id).toList());
            values.addAll(List.of("pad", "battery", "controller", "ew", "radar", "ew-battery", "wire", "portable-ew", "portable-battery", "antipersonnel", "aa", "bullets", "missiles", "missile-station", "fuel-canister", "auto-pro"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private boolean noAdmin(CommandSender sender) {
        plugin.message(sender, "Нет права shakheddrones.admin.");
        return true;
    }

    private double parseCoordinate(String raw, double base) {
        if (!raw.startsWith("~")) return Double.parseDouble(raw);
        return raw.length() == 1 ? base : base + Double.parseDouble(raw.substring(1));
    }

    private double parseAltitude(String raw) {
        double altitude = Double.parseDouble(raw);
        if (altitude > 140 || altitude < -64) throw new NumberFormatException("altitude");
        return altitude;
    }
}
