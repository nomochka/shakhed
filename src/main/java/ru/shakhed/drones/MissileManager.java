package ru.shakhed.drones;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class MissileManager {
    private final ShakhedDronesPlugin plugin;
    private final Map<UUID, ActiveMissile> missiles = new HashMap<>();
    private final Map<String, Long> proCooldowns = new HashMap<>();
    private final Map<UUID, Long> radarCooldowns = new HashMap<>();
    private BukkitTask task;
    private long ticks;

    public MissileManager(ShakhedDronesPlugin plugin) { this.plugin = plugin; }

    public void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L); }

    public void shutdown() {
        if (task != null) task.cancel();
        missiles.values().forEach(this::remove);
        missiles.clear();
    }

    public boolean launch(Player player, Location station, MissileType type,
                          Location target, double cruiseAltitude) {
        if (!plugin.defenses().isPowered(station, DefenseStore.Type.MISSILE_STATION)) {
            plugin.message(player, "Ракетная станция не запитана. Подключите проводом заряженную батарею.");
            return false;
        }
        if (target == null || !target.getWorld().equals(station.getWorld())) {
            plugin.message(player, "Сначала задайте цель: /drone target <x> <y> <z> <высота до 140>");
            return false;
        }
        MissileSettings settings = settings(type);
        double distance = station.distance(target);
        if (distance > settings.range()) {
            plugin.message(player, "Цель вне дальности ракеты: " + (int) distance + "/" + (int) settings.range() + ".");
            return false;
        }
        if (!plugin.defenses().consumeFuelNear(station, settings.fuel())) {
            plugin.message(player, "Рядом нет канистры с необходимым количеством топлива: " + settings.fuel() + ".");
            return false;
        }
        Location start = station.clone().add(0, 2.4, 0);
        ItemDisplay display = start.getWorld().spawn(start, ItemDisplay.class, entity -> {
            entity.setItemStack(plugin.items().strikeMissile(type));
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setPersistent(false);
        });
        double cruiseY = Math.max(start.getY() + 12.0, Math.min(140.0, cruiseAltitude));
        ActiveMissile missile = new ActiveMissile(display, type, player.getUniqueId(), target.clone(), cruiseY);
        missiles.put(display.getUniqueId(), missile);
        updateTicket(missile, start);
        start.getWorld().playSound(start, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2.0f, 0.55f);
        plugin.message(player, type.displayName() + " запущена. Топливо из канистры: -" + settings.fuel() + ".");
        return true;
    }

    private void tick() {
        ticks++;
        Iterator<ActiveMissile> iterator = missiles.values().iterator();
        while (iterator.hasNext()) {
            ActiveMissile missile = iterator.next();
            if (!missile.display.isValid() || missile.intercepted) {
                if (missile.intercepted && missile.display.isValid()) {
                    missile.display.getWorld().spawnParticle(Particle.EXPLOSION, missile.display.getLocation(), 2);
                    missile.display.getWorld().playSound(missile.display.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.6f);
                }
                remove(missile);
                iterator.remove();
                continue;
            }
            MissileSettings settings = settings(missile.type);
            missile.age++;
            if (ticks % 20 == 0) alertRadar(missile);
            Location current = missile.display.getLocation();
            double horizontal = Math.hypot(current.getX() - missile.target.getX(), current.getZ() - missile.target.getZ());
            Location waypoint;
            if (missile.type == MissileType.LOITERING && horizontal < 55.0 && missile.loiterTicks < 100) {
                double angle = missile.loiterTicks++ * 0.12;
                waypoint = new Location(current.getWorld(), missile.target.getX() + Math.cos(angle) * 42.0,
                        missile.cruiseY, missile.target.getZ() + Math.sin(angle) * 42.0);
            } else if (current.getY() < missile.cruiseY - 1.0 && horizontal > 20.0) {
                Vector horizontalDirection = missile.target.toVector().subtract(current.toVector()).setY(0);
                if (horizontalDirection.lengthSquared() < 0.01) horizontalDirection = new Vector(0, 0, 1);
                double run = Math.max(18.0, Math.min(90.0, (missile.cruiseY - current.getY()) * 1.6));
                Vector point = current.toVector().add(horizontalDirection.normalize().multiply(run));
                waypoint = new Location(current.getWorld(), point.getX(), missile.cruiseY, point.getZ());
            } else if (horizontal > 30.0) {
                waypoint = new Location(current.getWorld(), missile.target.getX(), missile.cruiseY, missile.target.getZ());
            } else {
                waypoint = missile.target;
            }
            Vector desired = waypoint.toVector().subtract(current.toVector());
            if (desired.lengthSquared() < 0.01) {
                impact(missile);
                iterator.remove();
                continue;
            }
            double turn = missile.type == MissileType.LOITERING ? 0.16 : 0.28;
            missile.direction.multiply(1.0 - turn).add(desired.normalize().multiply(turn)).normalize();
            Vector movement = missile.direction.clone().multiply(settings.speed());
            Location next = current.clone().add(movement);
            updateTicket(missile, next);
            if (missile.age > 8 && (!next.getBlock().isPassable()
                    || next.distanceSquared(missile.target) <= Math.max(5.0, settings.speed() * settings.speed() * 3.0))) {
                missile.display.teleport(next);
                impact(missile);
                iterator.remove();
                continue;
            }
            face(missile.display, movement);
            missile.display.teleport(next);
            next.getWorld().spawnParticle(Particle.FLAME, next, 3, 0.05, 0.05, 0.05, 0.01);
            next.getWorld().spawnParticle(Particle.SMOKE, next, 2, 0.08, 0.08, 0.08, 0.01);
        }
        if (ticks % 10 == 0) tickAutomaticPro();
    }

    private void tickAutomaticPro() {
        double range = plugin.getConfig().getDouble("missiles.pro.range", 180.0);
        double rangeSquared = range * range;
        long cooldown = plugin.getConfig().getLong("missiles.pro.cooldown-ticks", 60L);
        for (Location pro : plugin.defenses().locations(DefenseStore.Type.AUTO_PRO)) {
            if (!plugin.defenses().isPowered(pro, DefenseStore.Type.AUTO_PRO)) continue;
            String key = key(pro);
            if (proCooldowns.getOrDefault(key, 0L) > ticks) continue;
            ActiveMissile target = null;
            double nearest = rangeSquared;
            for (ActiveMissile candidate : missiles.values()) {
                if (!candidate.display.isValid() || candidate.intercepted
                        || !candidate.display.getWorld().equals(pro.getWorld())) continue;
                double distance = candidate.display.getLocation().distanceSquared(pro);
                if (distance < nearest) { nearest = distance; target = candidate; }
            }
            if (target == null) continue;
            double fuel = plugin.getConfig().getDouble("missiles.pro.fuel-per-shot", 1.0);
            if (plugin.defenses().ammoNear(pro, DefenseStore.AmmoType.MISSILE) <= 0
                    || plugin.defenses().fuelNear(pro) < fuel) continue;
            if (!plugin.defenses().consumeAmmoNear(pro, DefenseStore.AmmoType.MISSILE)
                    || !plugin.defenses().consumeFuelNear(pro, fuel)) continue;
            proCooldowns.put(key, ticks + cooldown);
            boolean hit = ThreadLocalRandom.current().nextDouble()
                    < plugin.getConfig().getDouble("missiles.pro.hit-chance", 0.65);
            fireInterceptor(pro.clone().add(0, 1.4, 0), target, hit);
        }
    }

    private void alertRadar(ActiveMissile missile) {
        UUID id = missile.display.getUniqueId();
        if (radarCooldowns.getOrDefault(id, 0L) > ticks) return;
        Location location = missile.display.getLocation();
        Location radar = plugin.defenses().detectingRadar(location);
        if (radar == null) return;
        radarCooldowns.put(id, ticks + plugin.getConfig().getLong("defense.radar-alert-cooldown-seconds", 20L) * 20L);
        double radius = plugin.getConfig().getDouble("defense.radar-alert-recipient-radius", 512.0);
        String warning = "ВНИМАНИЕ! РАКЕТНАЯ ОПАСНОСТЬ В ЗОНЕ " + location.getBlockX() + " "
                + location.getBlockY() + " " + location.getBlockZ() + ". Предположительный тип: "
                + missile.type.displayName() + ".";
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(radar.getWorld())
                    && player.getLocation().distanceSquared(radar) <= radius * radius) {
                plugin.message(player, warning);
                player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 1.0f, 0.55f);
            }
        }
    }

    private void fireInterceptor(Location start, ActiveMissile target, boolean hit) {
        ItemDisplay interceptor = start.getWorld().spawn(start, ItemDisplay.class, display -> {
            display.setItemStack(plugin.items().antiAirMissile());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setPersistent(false);
        });
        start.getWorld().playSound(start, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.6f, 1.25f);
        new BukkitRunnable() {
            int age;
            @Override public void run() {
                if (++age > 80 || !interceptor.isValid() || !target.display.isValid() || target.intercepted) {
                    interceptor.remove(); cancel(); return;
                }
                Vector direction = target.display.getLocation().toVector().subtract(interceptor.getLocation().toVector());
                if (direction.lengthSquared() <= 6.25) {
                    if (hit) target.intercepted = true;
                    else interceptor.getWorld().spawnParticle(Particle.SMOKE, interceptor.getLocation(), 15, .2, .2, .2, .03);
                    interceptor.remove(); cancel(); return;
                }
                Vector movement = direction.normalize().multiply(3.2);
                face(interceptor, movement);
                interceptor.teleport(interceptor.getLocation().add(movement));
                interceptor.getWorld().spawnParticle(Particle.END_ROD, interceptor.getLocation(), 2, .03, .03, .03, 0);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void impact(ActiveMissile missile) {
        Location location = missile.display.getLocation();
        MissileSettings settings = settings(missile.type);
        remove(missile);
        World world = location.getWorld();
        boolean breakBlocks = plugin.getConfig().getBoolean("flight.break-blocks", true);
        world.createExplosion(location, settings.explosionPower(), true, breakBlocks);
        if (missile.type == MissileType.ORESHNIK) {
            for (int i = 0; i < 4; i++) {
                Location sub = location.clone().add(ThreadLocalRandom.current().nextDouble(-5, 5), 0,
                        ThreadLocalRandom.current().nextDouble(-5, 5));
                world.createExplosion(sub, settings.explosionPower() * 0.42f, true, breakBlocks);
            }
        } else if (missile.type == MissileType.FLAMINGO) {
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                Location fire = location.clone().add(x, 0, z);
                if (!fire.getBlock().isEmpty()) fire.add(0, 1, 0);
                if (fire.getBlock().isEmpty() && ThreadLocalRandom.current().nextDouble() < 0.18) {
                    fire.getBlock().setType(org.bukkit.Material.FIRE, false);
                }
            }
        } else if (missile.type == MissileType.ISKANDER) {
            world.createExplosion(location.clone().add(0, 1, 0), settings.explosionPower() * 0.55f, false, false);
        }
    }

    private MissileSettings settings(MissileType type) {
        String path = "missiles.types." + type.id() + ".";
        return new MissileSettings(plugin.getConfig().getDouble(path + "range", 2000.0),
                plugin.getConfig().getDouble(path + "speed", 2.0),
                (float) plugin.getConfig().getDouble(path + "explosion-power", 18.0),
                plugin.getConfig().getDouble(path + "fuel", 3.0));
    }

    private void updateTicket(ActiveMissile missile, Location location) {
        Chunk chunk = location.getChunk();
        if (chunk.equals(missile.ticket)) return;
        chunk.addPluginChunkTicket(plugin);
        if (missile.ticket != null) missile.ticket.removePluginChunkTicket(plugin);
        missile.ticket = chunk;
    }

    private void remove(ActiveMissile missile) {
        radarCooldowns.remove(missile.display.getUniqueId());
        missile.display.remove();
        if (missile.ticket != null) missile.ticket.removePluginChunkTicket(plugin);
        missile.ticket = null;
    }

    private void face(ItemDisplay display, Vector movement) {
        if (movement.lengthSquared() < 0.0001) return;
        Location facing = display.getLocation();
        facing.setDirection(movement);
        display.setRotation(facing.getYaw(), facing.getPitch());
    }

    private String key(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private static final class ActiveMissile {
        final ItemDisplay display;
        final MissileType type;
        final UUID owner;
        final Location target;
        final double cruiseY;
        final Vector direction = new Vector(0, 0.65, 0.35).normalize();
        Chunk ticket;
        int age;
        int loiterTicks;
        boolean intercepted;

        ActiveMissile(ItemDisplay display, MissileType type, UUID owner, Location target, double cruiseY) {
            this.display = display; this.type = type; this.owner = owner; this.target = target; this.cruiseY = cruiseY;
        }
    }
}
