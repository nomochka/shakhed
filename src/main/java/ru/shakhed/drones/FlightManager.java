package ru.shakhed.drones;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.Input;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class FlightManager {
    private final ShakhedDronesPlugin plugin;
    private final Map<UUID, Flight> flights = new HashMap<>();
    private final Map<UUID, UUID> pilots = new HashMap<>();
    private final Map<UUID, Long> antiAirCooldowns = new HashMap<>();
    private final Map<UUID, Input> copterInputs = new HashMap<>();
    private final Map<UUID, Long> radarAlertCooldowns = new HashMap<>();
    private BukkitTask task;
    private long tickCounter;

    public FlightManager(ShakhedDronesPlugin plugin) { this.plugin = plugin; }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        flights.values().forEach(flight -> {
            cleanupFlight(flight);
            flight.display.remove();
        });
        flights.clear();
        pilots.clear();
    }

    public void launch(Interaction interaction, Player player, DroneType type, Location target,
                       double cruiseAltitude, double effectiveRange) {
        String displayId = interaction.getPersistentDataContainer().get(plugin.displayKey, PersistentDataType.STRING);
        Entity entity = displayId == null ? null : Bukkit.getEntity(UUID.fromString(displayId));
        if (!(entity instanceof ItemDisplay display)) {
            plugin.message(player, "Модель дрона потеряна; установите дрон заново.");
            interaction.remove();
            return;
        }
        PersistentDataContainer data = interaction.getPersistentDataContainer();
        Vector direction = new Vector(
                data.getOrDefault(plugin.directionXKey, PersistentDataType.DOUBLE, 0.0),
                data.getOrDefault(plugin.directionYKey, PersistentDataType.DOUBLE, 0.0),
                data.getOrDefault(plugin.directionZKey, PersistentDataType.DOUBLE, 1.0)
        ).normalize();
        if (type == DroneType.COPTER) {
            direction = player.getEyeLocation().getDirection().normalize();
            if (direction.getY() < 0.08) direction.setY(0.08).normalize();
        } else if (target != null) {
            direction = new Location(target.getWorld(), target.getX(), cruiseAltitude, target.getZ()).toVector()
                    .subtract(interaction.getLocation().toVector()).normalize();
        }
        int ammo = data.getOrDefault(plugin.ammoKey, PersistentDataType.INTEGER, 0);
        boolean antipersonnel = "antipersonnel".equals(data.get(plugin.payloadKey, PersistentDataType.STRING));
        interaction.remove();
        Flight flight = new Flight(display, type, player.getUniqueId(), direction, ammo,
                plugin.settings(type).health(), target, cruiseAltitude, effectiveRange, antipersonnel,
                type == DroneType.COPTER ? player.getLocation().clone() : null, player.isInvulnerable());
        flights.put(display.getUniqueId(), flight);
        if (type == DroneType.COPTER) {
            pilots.put(player.getUniqueId(), display.getUniqueId());
            player.setInvulnerable(true);
            display.addPassenger(player);
        }
        updateChunkTicket(flight, display.getLocation());
        plugin.message(player, type.displayName() + " запущен. Дальность: " + (int) plugin.settings(type).range() + " блоков."
                + (type == DroneType.COPTER ? " F — сброс TNT." : ""));
    }

    public boolean dropBomb(Player player) {
        UUID flightId = pilots.get(player.getUniqueId());
        Flight flight = flightId == null ? null : flights.get(flightId);
        if (flight == null || flight.mode != FlightMode.FLYING || flight.type != DroneType.COPTER) return false;
        if (!plugin.items().isController(player.getInventory().getItemInMainHand())) {
            plugin.message(player, "Возьмите пульт в основную руку.");
            return true;
        }
        if (flight.ammo <= 0) {
            plugin.message(player, "Боезапас коптера пуст.");
            return true;
        }
        TNTPrimed tnt = flight.display.getWorld().spawn(flight.display.getLocation().add(0, -0.5, 0), TNTPrimed.class);
        tnt.setFuseTicks(plugin.getConfig().getInt("flight.dropped-tnt-fuse-ticks", 50));
        tnt.setSource(player);
        tnt.setVelocity(new Vector(0, -0.15, 0));
        flight.ammo--;
        plugin.message(player, "TNT сброшен. Осталось: " + flight.ammo);
        return true;
    }

    public void updateCopterInput(Player player, Input input) {
        if (pilots.containsKey(player.getUniqueId())) copterInputs.put(player.getUniqueId(), input);
    }

    public boolean isCopterPilot(Player player, Entity vehicle) {
        UUID flightId = pilots.get(player.getUniqueId());
        Flight flight = flightId == null ? null : flights.get(flightId);
        return flight != null && !flight.pilotReturned && flightId.equals(vehicle.getUniqueId());
    }

    public void exitCopter(Player player) {
        UUID flightId = pilots.get(player.getUniqueId());
        Flight flight = flightId == null ? null : flights.get(flightId);
        if (flight != null) restorePilot(flight);
    }

    public void fireAntiAir(Player player, Location gun, DefenseStore.AmmoType ammoType) {
        long readyAt = antiAirCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (tickCounter < readyAt) return;
        if (!plugin.defenses().consumeAmmoNear(gun, ammoType)) {
            plugin.message(player, ammoType == DefenseStore.AmmoType.BULLET
                    ? "Рядом с ПВО нет пуль." : "Рядом с ПВО нет ракет.");
            return;
        }
        antiAirCooldowns.put(player.getUniqueId(), tickCounter
                + plugin.getConfig().getInt(ammoType == DefenseStore.AmmoType.BULLET
                ? "defense.anti-air-cooldown-ticks" : "defense.anti-air-missile-cooldown-ticks",
                ammoType == DefenseStore.AmmoType.BULLET ? 12 : 40));

        Location start = gun.clone().add(0, 1.0, 0);
        Vector direction = player.getEyeLocation().getDirection().normalize();
        double range = plugin.getConfig().getDouble("defense.anti-air-range", 140.0);
        Flight target = null;
        double targetDistance = range + 1;
        for (Flight candidate : flights.values()) {
            if (!candidate.display.getWorld().equals(start.getWorld())) continue;
            Vector offset = candidate.display.getLocation().toVector().subtract(start.toVector());
            double along = offset.dot(direction);
            if (along < 0 || along > range) continue;
            double perpendicularSquared = Math.max(0, offset.lengthSquared() - along * along);
            double lockRadiusSquared = ammoType == DefenseStore.AmmoType.BULLET ? 2.25 : 36.0;
            if (perpendicularSquared <= lockRadiusSquared && along < targetDistance) {
                target = candidate;
                targetDistance = along;
            }
        }
        start.getWorld().playSound(start, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 1.8f);
        animateAntiAirProjectile(start, direction, range, target, player, ammoType);
    }

    private void animateAntiAirProjectile(Location start, Vector initialDirection, double range, Flight target,
                                          Player shooter, DefenseStore.AmmoType ammoType) {
        boolean missile = ammoType == DefenseStore.AmmoType.MISSILE;
        ItemDisplay projectile = start.getWorld().spawn(start, ItemDisplay.class, display -> {
            display.setItemStack(missile ? plugin.items().antiAirMissile() : plugin.items().antiAirBullet());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setPersistent(false);
        });
        new BukkitRunnable() {
            private Vector direction = initialDirection.clone();
            private double travelled;

            @Override
            public void run() {
                if (!projectile.isValid() || travelled >= range) {
                    projectile.remove();
                    cancel();
                    return;
                }
                if (missile && target != null && target.display.isValid() && !target.destroyed) {
                    Vector homing = target.display.getLocation().toVector().subtract(projectile.getLocation().toVector()).normalize();
                    direction.multiply(0.72).add(homing.multiply(0.28)).normalize();
                }
                double speed = missile ? plugin.getConfig().getDouble("defense.missile-speed", 2.5) : 6.0;
                Location next = projectile.getLocation().add(direction.clone().multiply(speed));
                travelled += speed;
                faceMovement(projectile, direction);
                projectile.teleport(next);
                next.getWorld().spawnParticle(missile ? Particle.FLAME : Particle.END_ROD,
                        next, missile ? 3 : 1, 0.03, 0.03, 0.03, 0.01);
                if (target != null && target.display.isValid()
                        && target.display.getLocation().distanceSquared(next) <= speed * speed + 2.25) {
                    applyAntiAirHit(target, shooter, missile);
                    projectile.remove();
                    cancel();
                } else if (!next.getBlock().isPassable()) {
                    if (missile) next.getWorld().createExplosion(next, 2.0f, false, false, shooter);
                    projectile.remove();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void applyAntiAirHit(Flight target, Player shooter, boolean missile) {
        target.health -= plugin.getConfig().getDouble(missile
                ? "defense.missile-damage" : "defense.anti-air-damage", missile ? 16.0 : 6.0);
        target.display.getWorld().spawnParticle(Particle.CRIT, target.display.getLocation(),
                missile ? 30 : 15, 0.25, 0.25, 0.25, 0.1);
        if (missile) target.display.getWorld().createExplosion(target.display.getLocation(), 2.0f, false, false, shooter);
        if (target.health <= 0) {
            target.destroyed = true;
            plugin.message(shooter, "Цель поражена!");
        } else {
            plugin.message(shooter, "Попадание. Прочность цели: " + Math.max(0, (int) Math.ceil(target.health)));
        }
    }

    private void tick() {
        tickCounter++;
        if (tickCounter % 20 == 1) plugin.defenses().tickPower();
        Iterator<Map.Entry<UUID, Flight>> iterator = flights.entrySet().iterator();
        while (iterator.hasNext()) {
            Flight flight = iterator.next().getValue();
            if (!flight.display.isValid()) {
                removePilot(flight);
                iterator.remove();
                continue;
            }
            DroneSettings settings = plugin.settings(flight.type);
            flight.ageTicks++;
            if (tickCounter % 20 == 0) alertFromRadar(flight);
            if (flight.type == DroneType.COPTER) {
                Player pilot = Bukkit.getPlayer(flight.owner);
                if (pilot != null && !flight.display.getPassengers().contains(pilot)) restorePilot(flight);
                if (pilot != null && flight.ageTicks % 10 == 0) {
                    int percent = (int) Math.max(0, Math.ceil(100.0 * (flight.maxRange - flight.travelled) / flight.maxRange));
                    pilot.sendActionBar(Component.text("БАТАРЕЯ КОПТЕРА: " + percent + "% • TNT: " + flight.ammo,
                            percent > 20 ? NamedTextColor.GREEN : NamedTextColor.RED));
                }
            }
            if (flight.destroyed) {
                impact(flight, flight.display.getLocation());
                removePilot(flight);
                iterator.remove();
                continue;
            }
            if (flight.mode == FlightMode.FLYING && flight.travelled >= flight.maxRange) {
                flight.mode = flight.type == DroneType.COPTER ? FlightMode.FALLING : FlightMode.DIVING;
                Player owner = Bukkit.getPlayer(flight.owner);
                if (owner != null) plugin.message(owner, flight.type == DroneType.COPTER
                        ? "Батарея разряжена — коптер падает!" : "Топливо закончилось — дрон пикирует!");
            }

            if (flight.mode == FlightMode.FLYING && flight.target != null && !flight.navigationLost) {
                Location current = flight.display.getLocation();
                double horizontal = Math.hypot(current.getX() - flight.target.getX(), current.getZ() - flight.target.getZ());
                if (!flight.altitudeReached && current.getY() >= flight.cruiseY - 1.0) flight.altitudeReached = true;
                Location waypoint;
                if (!flight.altitudeReached) {
                    Vector towardTarget = flight.target.toVector().subtract(current.toVector()).setY(0);
                    if (towardTarget.lengthSquared() < 0.01) towardTarget = new Vector(0, 0, 1);
                    double climb = Math.max(0, flight.cruiseY - current.getY());
                    double climbRun = Math.max(12.0, Math.min(80.0, climb * 1.5));
                    Vector angledPoint = current.toVector().add(towardTarget.normalize().multiply(climbRun));
                    waypoint = new Location(current.getWorld(), angledPoint.getX(), flight.cruiseY, angledPoint.getZ());
                } else if (horizontal > 24.0) {
                    waypoint = new Location(current.getWorld(), flight.target.getX(), flight.cruiseY, flight.target.getZ());
                } else {
                    waypoint = flight.target;
                }
                Vector guidance = waypoint.toVector().subtract(current.toVector());
                if (guidance.lengthSquared() > 0.01) {
                    flight.direction.multiply(0.82).add(guidance.normalize().multiply(0.18)).normalize();
                }
                if (current.distanceSquared(flight.target) <= Math.max(6.25, settings.speed() * settings.speed() * 4.0)) {
                    impact(flight, flight.target);
                    removePilot(flight);
                    iterator.remove();
                    continue;
                }
            }

            boolean jammed = flight.mode == FlightMode.FLYING
                    && (plugin.defenses().isJammed(flight.display.getLocation())
                    || plugin.isPortableJammed(flight.display.getLocation()));
            if (jammed) flight.jamTicks++;
            else flight.jamTicks = Math.max(0, flight.jamTicks - 2);
            if (jammed && tickCounter % 10 == 0) {
                if (flight.type.requiresLaunchPad()) flight.navigationLost = true;
                double error = plugin.getConfig().getDouble("defense.ew-course-error", 0.08);
                flight.direction.add(new Vector(
                        ThreadLocalRandom.current().nextDouble(-error, error),
                        ThreadLocalRandom.current().nextDouble(-error * 0.4, error * 0.4),
                        ThreadLocalRandom.current().nextDouble(-error, error))).normalize();
                Player owner = Bukkit.getPlayer(flight.owner);
                if (owner != null) owner.sendActionBar(Component.text("СИГНАЛ ПОДАВЛЕН СИСТЕМОЙ РЭБ", NamedTextColor.RED));
            }
            if (flight.jamTicks > 60 && flight.mode == FlightMode.FLYING) {
                flight.mode = flight.type == DroneType.COPTER ? FlightMode.FALLING : FlightMode.DIVING;
            }
            if (flight.mode == FlightMode.FALLING) {
                flight.direction.setY(Math.max(-1.0, flight.direction.getY() - 0.10)).multiply(0.98);
            } else if (flight.mode == FlightMode.DIVING) {
                double horizontalLength = Math.hypot(flight.direction.getX(), flight.direction.getZ());
                if (horizontalLength < 0.05) {
                    flight.direction.setX(0.65).setZ(0.0);
                } else {
                    flight.direction.setX(flight.direction.getX() / horizontalLength * 0.72);
                    flight.direction.setZ(flight.direction.getZ() / horizontalLength * 0.72);
                }
                flight.direction.setY(-0.69).normalize();
            }

            double speed = settings.speed();
            if (flight.mode == FlightMode.FALLING) speed = Math.min(1.2, 0.25 + flight.fallTicks++ * 0.025);
            if (flight.mode == FlightMode.DIVING) speed = Math.min(settings.speed() * 1.8, settings.speed() + flight.fallTicks++ * 0.025);
            Vector movement = flight.type == DroneType.COPTER && flight.mode == FlightMode.FLYING
                    ? copterMovement(flight, settings.speed(), jammed)
                    : flight.direction.clone().normalize().multiply(speed);
            Location next = flight.display.getLocation().add(movement);
            updateChunkTicket(flight, next);

            if ((flight.ageTicks > 12 && collides(flight, next)) || next.getY() <= next.getWorld().getMinHeight()) {
                impact(flight, next);
                removePilot(flight);
                iterator.remove();
                continue;
            }
            if (movement.lengthSquared() > 0.0001) faceMovement(flight.display, movement);
            flight.display.teleport(next);
            if (flight.mode == FlightMode.FLYING) {
                flight.travelled += movement.length();
                if (flight.type == DroneType.COPTER) {
                    flight.travelled += plugin.getConfig().getDouble("flight.copter-idle-range-drain-per-tick", 0.01);
                }
            }
            particles(flight);
        }
    }

    private Vector copterMovement(Flight flight, double speed, boolean jammed) {
        Player pilot = Bukkit.getPlayer(flight.owner);
        Input input = copterInputs.get(flight.owner);
        if (pilot == null || input == null || jammed) return new Vector(0, 0, 0);
        Vector forward = pilot.getEyeLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 0.001) forward = new Vector(0, 0, 1);
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        Vector result = new Vector();
        if (input.isForward()) result.add(forward);
        if (input.isBackward()) result.subtract(forward);
        if (input.isRight()) result.add(right);
        if (input.isLeft()) result.subtract(right);
        if (input.isJump()) result.setY(result.getY() + 1.0);
        if (input.isSneak()) result.setY(result.getY() - 1.0);
        if (result.lengthSquared() < 0.001) return result;
        flight.direction.copy(result).normalize();
        return result.normalize().multiply(speed * (input.isSprint() ? 1.45 : 1.0));
    }

    private void alertFromRadar(Flight flight) {
        UUID id = flight.display.getUniqueId();
        if (radarAlertCooldowns.getOrDefault(id, 0L) > tickCounter) return;
        Location drone = flight.display.getLocation();
        Location radar = plugin.defenses().detectingRadar(drone);
        if (radar == null) return;
        long cooldown = plugin.getConfig().getLong("defense.radar-alert-cooldown-seconds", 20L) * 20L;
        radarAlertCooldowns.put(id, tickCounter + Math.max(20L, cooldown));
        double recipientRadius = plugin.getConfig().getDouble("defense.radar-alert-recipient-radius", 512.0);
        double recipientRadiusSquared = recipientRadius * recipientRadius;
        String warning = "ВНИМАНИЕ! БЕСПИЛОТНАЯ ОПАСНОСТЬ В ЗОНЕ "
                + drone.getBlockX() + " " + drone.getBlockY() + " " + drone.getBlockZ()
                + ". Предположительный тип: " + flight.type.displayName() + ".";
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(radar.getWorld())
                    && player.getLocation().distanceSquared(radar) <= recipientRadiusSquared) {
                plugin.message(player, warning);
                player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 1.0f, 0.65f);
            }
        }
    }

    private boolean collides(Flight flight, Location next) {
        if (!next.getBlock().isPassable()) return true;
        return next.getWorld().getNearbyEntities(next, 0.55, 0.55, 0.55).stream()
                .anyMatch(entity -> !entity.getUniqueId().equals(flight.display.getUniqueId())
                        && !entity.getUniqueId().equals(flight.owner)
                        && entity instanceof LivingEntity);
    }

    private void faceMovement(ItemDisplay display, Vector movement) {
        Location location = display.getLocation();
        location.setDirection(movement);
        display.setRotation(location.getYaw(), location.getPitch());
    }

    private void particles(Flight flight) {
        if (!plugin.getConfig().getBoolean("flight.particles", true)) return;
        Particle particle = flight.type == DroneType.JET ? Particle.FLAME
                : flight.mode == FlightMode.FALLING ? Particle.SMOKE
                : Particle.CAMPFIRE_COSY_SMOKE;
        flight.display.getWorld().spawnParticle(particle, flight.display.getLocation(), 2, 0.05, 0.05, 0.05, 0.01);
    }

    private void impact(Flight flight, Location location) {
        restorePilot(flight);
        flight.display.eject();
        flight.display.remove();
        World world = location.getWorld();
        float power = plugin.settings(flight.type).explosionPower()
                + (float) (flight.ammo * plugin.getConfig().getDouble("flight.tnt-power-bonus", 1.5));
        if (flight.antipersonnel) {
            power *= (float) plugin.getConfig().getDouble("defense.antipersonnel-explosion-multiplier", 0.45);
        }
        boolean breakBlocks = plugin.getConfig().getBoolean("flight.break-blocks", true);
        Player owner = Bukkit.getPlayer(flight.owner);
        world.createExplosion(location, power, true, breakBlocks, owner);

        if (flight.antipersonnel) {
            double radius = plugin.getConfig().getDouble("defense.antipersonnel-radius", 9.0);
            double maxDamage = plugin.getConfig().getDouble("defense.antipersonnel-max-damage", 24.0);
            for (Player player : world.getPlayers()) {
                double distance = player.getLocation().distance(location);
                if (distance > radius) continue;
                player.damage(maxDamage * Math.max(0.25, 1.0 - distance / radius), owner);
            }
        }

        if (flight.type == DroneType.FP1) {
            world.getNearbyLivingEntities(location, 4.0).stream().filter(entity -> !entity.getUniqueId().equals(flight.owner))
                    .forEach(entity -> entity.damage(10.0, owner));
        } else if (flight.type == DroneType.GERAN4) {
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                Location fire = location.clone().add(x, 0, z);
                if (fire.getBlock().getType().isAir() && fire.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                    fire.getBlock().setType(Material.FIRE);
                }
            }
        } else if (flight.type == DroneType.JET) {
            world.createExplosion(location.clone().add(0, 1, 0), Math.max(2.0f, power * 0.45f), false, breakBlocks, owner);
        }
    }

    private void removePilot(Flight flight) {
        cleanupFlight(flight);
        pilots.remove(flight.owner, flight.display.getUniqueId());
        copterInputs.remove(flight.owner);
        radarAlertCooldowns.remove(flight.display.getUniqueId());
    }

    private void cleanupFlight(Flight flight) {
        restorePilot(flight);
        if (flight.ticketChunk != null) {
            flight.ticketChunk.removePluginChunkTicket(plugin);
            flight.ticketChunk = null;
        }
    }

    private void restorePilot(Flight flight) {
        if (flight.pilotReturned || flight.pilotOrigin == null) return;
        Player player = Bukkit.getPlayer(flight.owner);
        if (player == null) return;
        flight.pilotReturned = true;
        player.leaveVehicle();
        player.teleport(flight.pilotOrigin);
        player.setInvulnerable(flight.pilotWasInvulnerable);
    }

    private void updateChunkTicket(Flight flight, Location location) {
        Chunk chunk = location.getChunk();
        if (flight.ticketChunk != null && flight.ticketChunk.equals(chunk)) return;
        chunk.addPluginChunkTicket(plugin);
        if (flight.ticketChunk != null) flight.ticketChunk.removePluginChunkTicket(plugin);
        flight.ticketChunk = chunk;
    }

    private enum FlightMode { FLYING, DIVING, FALLING }

    private static final class Flight {
        private final ItemDisplay display;
        private final DroneType type;
        private final UUID owner;
        private final Vector direction;
        private double travelled;
        private int ammo;
        private int fallTicks;
        private double health;
        private boolean destroyed;
        private boolean navigationLost;
        private int jamTicks;
        private final boolean antipersonnel;
        private final Location target;
        private final double cruiseY;
        private final double maxRange;
        private final Location pilotOrigin;
        private final boolean pilotWasInvulnerable;
        private boolean pilotReturned;
        private Chunk ticketChunk;
        private int ageTicks;
        private boolean altitudeReached;
        private FlightMode mode = FlightMode.FLYING;

        private Flight(ItemDisplay display, DroneType type, UUID owner, Vector direction, int ammo,
                       double health, Location target, double cruiseY, double maxRange, boolean antipersonnel,
                       Location pilotOrigin, boolean pilotWasInvulnerable) {
            this.display = display;
            this.type = type;
            this.owner = owner;
            this.direction = direction;
            this.ammo = ammo;
            this.health = health;
            this.target = target == null ? null : target.clone();
            this.cruiseY = cruiseY;
            this.maxRange = Math.max(1.0, maxRange);
            this.antipersonnel = antipersonnel;
            this.pilotOrigin = pilotOrigin;
            this.pilotWasInvulnerable = pilotWasInvulnerable;
        }
    }
}
