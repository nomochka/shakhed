package ru.shakhed.drones;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

public final class DroneListener implements Listener {
    private static final String ENTITY_KIND = "installed_drone";
    private final ShakhedDronesPlugin plugin;
    private final Map<UUID, GunControl> controlledGuns = new HashMap<>();
    private final Map<UUID, Location> pendingWire = new HashMap<>();

    public DroneListener(ShakhedDronesPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickGunControls, 1L, 1L);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlatformPlace(BlockPlaceEvent event) {
        String kind = plugin.items().kind(event.getItemInHand());
        if (ItemFactory.KIND_MISSILE_STATION.equals(kind)) {
            Location center = event.getBlockPlaced().getLocation();
            if (!canReserveMissileFootprint(center)) {
                event.setCancelled(true);
                plugin.message(event.getPlayer(), "Для ракетной станции нужна свободная площадка 3×3 на одном уровне.");
                return;
            }
            reserveMissileFootprint(center);
            plugin.defenses().add(center, DefenseStore.Type.MISSILE_STATION);
            spawnDeviceDisplay(center, plugin.items().missileStation(), 1.35);
            plugin.message(event.getPlayer(), "Ракетная станция 3×3 установлена. Подключите батарею и поставьте рядом канистры.");
        } else if (ItemFactory.KIND_FUEL_CANISTER.equals(kind)) {
            plugin.defenses().addFuelCanister(event.getBlockPlaced().getLocation());
            spawnDeviceDisplay(event.getBlockPlaced().getLocation(), plugin.items().fuelCanister(), 1.05);
            event.getBlockPlaced().setType(Material.BARRIER, false);
            plugin.message(event.getPlayer(), "Топливная канистра установлена.");
        } else if (ItemFactory.KIND_AUTO_PRO.equals(kind)) {
            plugin.defenses().add(event.getBlockPlaced().getLocation(), DefenseStore.Type.AUTO_PRO);
            spawnDeviceDisplay(event.getBlockPlaced().getLocation(), plugin.items().autoPro(), 1.2);
            event.getBlockPlaced().setType(Material.BARRIER, false);
            plugin.message(event.getPlayer(), "Автоматическая ПРО установлена. Подключите питание, топливо и поставьте контейнер ракет.");
        } else if (ItemFactory.KIND_PAD.equals(kind)) {
            plugin.platforms().add(event.getBlockPlaced().getLocation());
            spawnDeviceDisplay(event.getBlockPlaced().getLocation(), plugin.items().launchPad(), 1.02);
            event.getBlockPlaced().setType(Material.BARRIER, false);
            plugin.message(event.getPlayer(), "Стартовая площадка установлена.");
        } else if (ItemFactory.KIND_EW.equals(kind)) {
            plugin.defenses().add(event.getBlockPlaced().getLocation(), DefenseStore.Type.EW);
            spawnDeviceDisplay(event.getBlockPlaced().getLocation(), plugin.items().ewStation(), 1.2);
            event.getBlockPlaced().setType(Material.BARRIER, false);
            plugin.message(event.getPlayer(), "Станция РЭБ установлена. Подключите батарею красной пылью.");
        } else if (ItemFactory.KIND_EW_BATTERY.equals(kind)) {
            plugin.defenses().addBattery(event.getBlockPlaced().getLocation());
            spawnDeviceDisplay(event.getBlockPlaced().getLocation(), plugin.items().ewBattery(), 1.05);
            event.getBlockPlaced().setType(Material.BARRIER, false);
            int seconds = plugin.getConfig().getInt("defense.ew-battery-capacity-seconds", 600);
            plugin.message(event.getPlayer(), "Батарея РЭБ установлена. Заряд: " + seconds + " сек.");
        } else if (ItemFactory.KIND_RADAR.equals(kind)) {
            plugin.defenses().add(event.getBlockPlaced().getLocation(), DefenseStore.Type.RADAR);
            spawnDeviceDisplay(event.getBlockPlaced().getLocation(), plugin.items().radar(), 1.2);
            event.getBlockPlaced().setType(Material.BARRIER, false);
            plugin.message(event.getPlayer(), "Локатор установлен. Подключите его проводом к батарее.");
        } else if (ItemFactory.KIND_AA.equals(kind)) {
            plugin.defenses().add(event.getBlockPlaced().getLocation(), DefenseStore.Type.ANTI_AIR);
            spawnDeviceDisplay(event.getBlockPlaced().getLocation(), plugin.items().antiAir(), 1.2);
            event.getBlockPlaced().setType(Material.BARRIER, false);
            plugin.message(event.getPlayer(), "Зенитка установлена. ПКМ по ней — управление.");
        } else if (ItemFactory.KIND_BULLET_CRATE.equals(kind)) {
            int rounds = plugin.items().ammoCount(event.getItemInHand(), 500);
            plugin.defenses().addAmmoCrate(event.getBlockPlaced().getLocation(), DefenseStore.AmmoType.BULLET, rounds);
            spawnDeviceDisplay(event.getBlockPlaced().getLocation(), plugin.items().ammoCrate(false, rounds), 1.05);
            event.getBlockPlaced().setType(Material.BARRIER, false);
            plugin.message(event.getPlayer(), "Ящик ленты установлен: " + rounds + " выстрелов.");
        } else if (ItemFactory.KIND_MISSILE_CRATE.equals(kind)) {
            int missiles = plugin.items().ammoCount(event.getItemInHand(), 6);
            plugin.defenses().addAmmoCrate(event.getBlockPlaced().getLocation(), DefenseStore.AmmoType.MISSILE, missiles);
            spawnDeviceDisplay(event.getBlockPlaced().getLocation(), plugin.items().ammoCrate(true, missiles), 1.05);
            event.getBlockPlaced().setType(Material.BARRIER, false);
            plugin.message(event.getPlayer(), "Ракетный контейнер установлен: " + missiles + " ракет.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlatformBreak(BlockBreakEvent event) {
        Location stationCenter = plugin.defenses().missileStationCenterAt(event.getBlock().getLocation());
        if (stationCenter != null) {
            destroyMissileStation(stationCenter);
            event.setDropItems(false);
            stationCenter.getWorld().dropItemNaturally(stationCenter, plugin.items().missileStation());
            return;
        }
        removeDeviceDisplay(event.getBlock().getLocation());
        if (plugin.platforms().contains(event.getBlock().getLocation())) {
            plugin.platforms().remove(event.getBlock().getLocation());
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), plugin.items().launchPad());
        } else if (plugin.defenses().is(event.getBlock().getLocation(), DefenseStore.Type.EW)) {
            plugin.defenses().remove(event.getBlock().getLocation());
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), plugin.items().ewStation());
        } else if (plugin.defenses().is(event.getBlock().getLocation(), DefenseStore.Type.ANTI_AIR)) {
            plugin.defenses().remove(event.getBlock().getLocation());
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), plugin.items().antiAir());
        } else if (plugin.defenses().is(event.getBlock().getLocation(), DefenseStore.Type.RADAR)) {
            plugin.defenses().remove(event.getBlock().getLocation());
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), plugin.items().radar());
        } else if (plugin.defenses().isBattery(event.getBlock().getLocation())) {
            plugin.defenses().remove(event.getBlock().getLocation());
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), plugin.items().ewBattery());
        } else if (plugin.defenses().isFuelCanister(event.getBlock().getLocation())) {
            plugin.defenses().remove(event.getBlock().getLocation());
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), plugin.items().fuelCanister());
        } else if (plugin.defenses().is(event.getBlock().getLocation(), DefenseStore.Type.AUTO_PRO)) {
            plugin.defenses().remove(event.getBlock().getLocation());
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), plugin.items().autoPro());
        } else if (plugin.defenses().isAmmoCrate(event.getBlock().getLocation(), DefenseStore.AmmoType.BULLET)) {
            int remaining = plugin.defenses().ammoAt(event.getBlock().getLocation(), DefenseStore.AmmoType.BULLET);
            plugin.defenses().remove(event.getBlock().getLocation());
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), plugin.items().ammoCrate(false, remaining));
        } else if (plugin.defenses().isAmmoCrate(event.getBlock().getLocation(), DefenseStore.AmmoType.MISSILE)) {
            int remaining = plugin.defenses().ammoAt(event.getBlock().getLocation(), DefenseStore.AmmoType.MISSILE);
            plugin.defenses().remove(event.getBlock().getLocation());
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), plugin.items().ammoCrate(true, remaining));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDefenseControl(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (plugin.items().isPortableEw(event.getItem())
                && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            plugin.togglePortableEw(player);
            return;
        }
        if (ItemFactory.KIND_WIRE.equals(plugin.items().kind(event.getItem()))
                && event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            event.setCancelled(true);
            Location clicked = event.getClickedBlock().getLocation();
            Location missileStation = plugin.defenses().missileStationCenterAt(clicked);
            if (missileStation != null) clicked = missileStation;
            if (plugin.defenses().is(clicked, DefenseStore.Type.EW)
                    || plugin.defenses().is(clicked, DefenseStore.Type.RADAR)
                    || plugin.defenses().is(clicked, DefenseStore.Type.MISSILE_STATION)
                    || plugin.defenses().is(clicked, DefenseStore.Type.AUTO_PRO)) {
                pendingWire.put(player.getUniqueId(), clicked);
                plugin.message(player, "Первая точка: станция. Теперь нажмите ПКМ этим проводом по батарее.");
            } else if (plugin.defenses().isBattery(clicked)) {
                Location station = pendingWire.remove(player.getUniqueId());
                if (station == null) plugin.message(player, "Сначала нажмите проводом ПКМ по станции, РЭБ, локатору или ПРО.");
                else if (plugin.defenses().connect(station, clicked)) plugin.message(player, "Станция подключена к батарее.");
                else plugin.message(player, "Не удалось подключить: устройства слишком далеко или выбраны неверно.");
            } else {
                plugin.message(player, "Провод подключает станцию, РЭБ, локатор или ПРО к батарее.");
            }
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && plugin.defenses().is(event.getClickedBlock().getLocation(), DefenseStore.Type.EW)) {
            event.setCancelled(true);
            plugin.message(player, "РЭБ: " + plugin.defenses().powerStatus(event.getClickedBlock().getLocation()) + ".");
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && plugin.defenses().is(event.getClickedBlock().getLocation(), DefenseStore.Type.RADAR)) {
            event.setCancelled(true);
            plugin.message(player, "Локатор: " + plugin.defenses().powerStatus(event.getClickedBlock().getLocation()) + ".");
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Location center = plugin.defenses().missileStationCenterAt(event.getClickedBlock().getLocation());
            if (center != null) {
                event.setCancelled(true);
                MissileType missileType = plugin.items().missileType(event.getItem());
                if (missileType == null) {
                    plugin.message(player, "Ракетная станция: " + plugin.defenses().powerStatus(center)
                            + ". Возьмите ракету и задайте /drone target.");
                    return;
                }
                ShakhedDronesPlugin.DroneTarget target = plugin.target(player.getUniqueId());
                if (target == null) {
                    plugin.message(player, "Сначала задайте цель: /drone target <x> <y> <z> <высота до 140>");
                    return;
                }
                if (plugin.missiles().launch(player, center, missileType, target.location(), target.cruiseAltitude())) {
                    consumeOne(player, event.getItem());
                }
                return;
            }
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && plugin.defenses().is(event.getClickedBlock().getLocation(), DefenseStore.Type.AUTO_PRO)) {
            event.setCancelled(true);
            Location pro = event.getClickedBlock().getLocation();
            plugin.message(player, "Автоматическая ПРО: " + plugin.defenses().powerStatus(pro)
                    + "; ракет рядом: " + plugin.defenses().ammoNear(pro, DefenseStore.AmmoType.MISSILE)
                    + "; топлива рядом: " + (int) plugin.defenses().fuelNear(pro) + ".");
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && plugin.defenses().is(event.getClickedBlock().getLocation(), DefenseStore.Type.ANTI_AIR)) {
            event.setCancelled(true);
            Location gun = event.getClickedBlock().getLocation();
            GunControl previous = controlledGuns.get(player.getUniqueId());
            DefenseStore.AmmoType mode = previous != null && previous.location.equals(gun)
                    ? previous.mode : DefenseStore.AmmoType.BULLET;
            ArmorStand seat;
            if (previous != null && previous.seat != null && Bukkit.getEntity(previous.seat) instanceof ArmorStand existing) {
                seat = existing;
            } else {
                seat = gun.getWorld().spawn(gun.clone().add(0.5, 0.45, 0.5), ArmorStand.class, stand -> {
                    stand.setInvisible(true);
                    stand.setGravity(false);
                    stand.setInvulnerable(true);
                    stand.setPersistent(false);
                    stand.setSmall(true);
                });
            }
            if (!seat.getPassengers().contains(player)) seat.addPassenger(player);
            controlledGuns.put(player.getUniqueId(), new GunControl(gun, mode, seat.getUniqueId()));
            int bullets = plugin.defenses().ammoNear(gun, DefenseStore.AmmoType.BULLET);
            int missiles = plugin.defenses().ammoNear(gun, DefenseStore.AmmoType.MISSILE);
            plugin.message(player, "ПВО: режим " + (mode == DefenseStore.AmmoType.BULLET ? "ПУЛИ" : "РАКЕТЫ")
                    + ", рядом " + bullets + " пуль и " + missiles + " ракет. ЛКМ — огонь, F — смена режима.");
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            GunControl control = controlledGuns.get(player.getUniqueId());
            if (control == null) return;
            Location gun = control.location;
            if (!gun.getWorld().equals(player.getWorld()) || gun.distanceSquared(player.getLocation()) > 36.0
                    || !plugin.defenses().is(gun, DefenseStore.Type.ANTI_AIR)) {
                controlledGuns.remove(player.getUniqueId());
                plugin.message(player, "Вы вышли из управления зениткой.");
                return;
            }
            event.setCancelled(true);
            aimDeviceDisplay(gun, player.getEyeLocation().getDirection());
            plugin.flights().fireAntiAir(player, gun, control.mode);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && controlledGuns.containsKey(event.getPlayer().getUniqueId())) {
            leaveGun(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { leaveGun(event.getPlayer()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.resourcePack().send(event.getPlayer()), 20L);
    }

    private void tickGunControls() {
        for (Map.Entry<UUID, GunControl> entry : new HashMap<>(controlledGuns).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            GunControl control = entry.getValue();
            Entity seat = control.seat == null ? null : Bukkit.getEntity(control.seat);
            if (player == null || seat == null || !seat.getPassengers().contains(player)
                    || !plugin.defenses().is(control.location, DefenseStore.Type.ANTI_AIR)) {
                if (player != null) leaveGun(player);
                else if (seat != null) seat.remove();
                controlledGuns.remove(entry.getKey());
                continue;
            }
            aimDeviceDisplay(control.location, player.getEyeLocation().getDirection());
        }
    }

    private void leaveGun(Player player) {
        GunControl control = controlledGuns.remove(player.getUniqueId());
        if (control == null) return;
        player.leaveVehicle();
        if (control.seat != null && Bukkit.getEntity(control.seat) != null) Bukkit.getEntity(control.seat).remove();
        plugin.message(player, "Вы покинули место оператора ПВО.");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDronePlace(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        DroneType type = plugin.items().droneType(event.getItem());
        if (type == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("shakheddrones.use")) {
            plugin.message(player, "У вас нет права использовать дроны.");
            return;
        }
        if (type.requiresLaunchPad() && !plugin.platforms().contains(event.getClickedBlock().getLocation())) {
            plugin.message(player, "Крупный дрон можно установить только на стартовую площадку.");
            return;
        }
        if (type.requiresLaunchPad() && event.getBlockFace() != org.bukkit.block.BlockFace.UP) {
            plugin.message(player, "Дрон нужно ставить сверху на площадку, установленную на земле.");
            return;
        }

        Location location = type.requiresLaunchPad()
                ? event.getClickedBlock().getLocation().add(0.5, 1.15, 0.5)
                : event.getClickedBlock().getRelative(event.getBlockFace()).getLocation().add(0.5, 0.15, 0.5);
        if (!location.getBlock().isPassable()) {
            plugin.message(player, "Для установки недостаточно места.");
            return;
        }
        Vector direction = player.getEyeLocation().getDirection().normalize();
        spawnInstalledDrone(location, player, type, direction);
        consumeOne(player, event.getItem());
        plugin.message(player, type.displayName() + " установлен. "
                + (type == DroneType.COPTER ? "Вставьте батарею и загрузите TNT." : "Можно загрузить TNT или запустить ПКМ."));
    }

    private void spawnInstalledDrone(Location location, Player player, DroneType type, Vector direction) {
        ItemDisplay display = location.getWorld().spawn(location, ItemDisplay.class, entity -> {
            ItemStack model = plugin.items().drone(type);
            model.setAmount(1);
            entity.setItemStack(model);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setPersistent(false);
            entity.setGlowing(type == DroneType.JET);
        });
        Interaction interaction = location.getWorld().spawn(location, Interaction.class, entity -> {
            entity.setInteractionWidth(type == DroneType.COPTER ? 0.9f : 1.4f);
            entity.setInteractionHeight(type == DroneType.COPTER ? 0.7f : 1.1f);
            entity.setResponsive(true);
            entity.setPersistent(false);
        });
        PersistentDataContainer data = interaction.getPersistentDataContainer();
        data.set(plugin.entityKindKey, PersistentDataType.STRING, ENTITY_KIND);
        data.set(plugin.entityTypeKey, PersistentDataType.STRING, type.id());
        data.set(plugin.ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        data.set(plugin.displayKey, PersistentDataType.STRING, display.getUniqueId().toString());
        data.set(plugin.batteryKey, PersistentDataType.BYTE, (byte) (type == DroneType.COPTER ? 0 : 1));
        data.set(plugin.ammoKey, PersistentDataType.INTEGER, 0);
        data.set(plugin.payloadKey, PersistentDataType.STRING, "standard");
        data.set(plugin.directionXKey, PersistentDataType.DOUBLE, direction.getX());
        data.set(plugin.directionYKey, PersistentDataType.DOUBLE, direction.getY());
        data.set(plugin.directionZKey, PersistentDataType.DOUBLE, direction.getZ());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInstalledDroneClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Interaction interaction)) return;
        PersistentDataContainer data = interaction.getPersistentDataContainer();
        if (!ENTITY_KIND.equals(data.get(plugin.entityKindKey, PersistentDataType.STRING))) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        String ownerId = data.get(plugin.ownerKey, PersistentDataType.STRING);
        if (ownerId == null || !ownerId.equals(player.getUniqueId().toString())) {
            plugin.message(player, "Этот дрон принадлежит другому игроку.");
            return;
        }
        DroneType type = DroneType.byId(data.getOrDefault(plugin.entityTypeKey, PersistentDataType.STRING, ""))
                .orElse(null);
        if (type == null) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (ItemFactory.KIND_ANTIPERSONNEL.equals(plugin.items().kind(hand))) {
            if ("antipersonnel".equals(data.get(plugin.payloadKey, PersistentDataType.STRING))) {
                plugin.message(player, "Противопехотный боеприпас уже установлен.");
                return;
            }
            data.set(plugin.payloadKey, PersistentDataType.STRING, "antipersonnel");
            consumeOne(player, hand);
            plugin.message(player, "Установлен противопехотный боеприпас: разрушение уменьшено, урон игрокам увеличен.");
            return;
        }
        if (type == DroneType.COPTER && ItemFactory.KIND_BATTERY.equals(plugin.items().kind(hand))) {
            if (data.getOrDefault(plugin.batteryKey, PersistentDataType.BYTE, (byte) 0) == 1) {
                plugin.message(player, "Батарея уже установлена.");
                return;
            }
            data.set(plugin.batteryKey, PersistentDataType.BYTE, (byte) 1);
            consumeOne(player, hand);
            plugin.message(player, "Батарея установлена.");
            return;
        }
        if (hand.getType() == Material.TNT) {
            int ammo = data.getOrDefault(plugin.ammoKey, PersistentDataType.INTEGER, 0);
            int max = plugin.getConfig().getInt(type == DroneType.COPTER
                    ? "flight.max-tnt-per-copter" : "flight.max-tnt-per-drone", type == DroneType.COPTER ? 3 : 4);
            if (ammo >= max) {
                plugin.message(player, "Достигнут предел боезапаса: " + max + ".");
                return;
            }
            data.set(plugin.ammoKey, PersistentDataType.INTEGER, ammo + 1);
            consumeOne(player, hand);
            plugin.message(player, "TNT загружен: " + (ammo + 1) + "/" + max + ".");
            return;
        }
        if (type == DroneType.COPTER && data.getOrDefault(plugin.batteryKey, PersistentDataType.BYTE, (byte) 0) == 0) {
            plugin.message(player, "Сначала вставьте батарею.");
            return;
        }
        if (type == DroneType.COPTER && !plugin.items().isController(hand)) {
            plugin.message(player, "Для запуска коптера возьмите пульт управления в основную руку.");
            return;
        }
        Location target = null;
        double cruiseAltitude = interaction.getLocation().getY();
        double effectiveRange = plugin.settings(type).range();
        if (type.requiresLaunchPad()) {
            ShakhedDronesPlugin.DroneTarget specification = plugin.target(player.getUniqueId());
            if (specification == null || !specification.location().getWorld().equals(interaction.getWorld())) {
                plugin.message(player, "Сначала задайте цель: /drone target <x> <y> <z> <высота до 140>");
                return;
            }
            target = specification.location();
            cruiseAltitude = specification.cruiseAltitude();
            double climb = Math.max(0, cruiseAltitude - interaction.getLocation().getY());
            double fuelCost = climb * plugin.getConfig().getDouble("flight.altitude-fuel-per-block", 5.0)
                    + Math.max(0, climb - plugin.getConfig().getDouble("flight.high-climb-threshold", 10.0))
                    * plugin.getConfig().getDouble("flight.high-climb-extra-fuel-per-block", 2.5);
            effectiveRange = Math.max(0, plugin.settings(type).range() - fuelCost);
            double distance = interaction.getLocation().distance(target);
            if (distance > effectiveRange) {
                plugin.message(player, "Цель вне дальности с учётом высоты: " + (int) distance + "/"
                        + (int) effectiveRange + " блоков.");
                return;
            }
            plugin.message(player, "Эшелон " + (int) cruiseAltitude + "; набор высоты израсходовал "
                    + (int) fuelCost + " блоков дальности.");
        }
        plugin.flights().launch(interaction, player, type, target, cruiseAltitude, effectiveRange);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        GunControl control = controlledGuns.get(event.getPlayer().getUniqueId());
        if (control != null) {
            event.setCancelled(true);
            DefenseStore.AmmoType mode = control.mode == DefenseStore.AmmoType.BULLET
                    ? DefenseStore.AmmoType.MISSILE : DefenseStore.AmmoType.BULLET;
            controlledGuns.put(event.getPlayer().getUniqueId(), new GunControl(control.location, mode, control.seat));
            plugin.message(event.getPlayer(), "Режим ПВО: " + (mode == DefenseStore.AmmoType.BULLET ? "ПУЛИ" : "РАКЕТЫ") + ".");
            return;
        }
        if (plugin.flights().dropBomb(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onCopterInput(PlayerInputEvent event) {
        plugin.flights().updateCopterInput(event.getPlayer(), event.getInput());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCopterDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player && plugin.flights().isCopterPilot(player, event.getDismounted())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onGunArmSwing(PlayerAnimationEvent event) {
        GunControl control = controlledGuns.get(event.getPlayer().getUniqueId());
        if (control == null) return;
        if (!fireControlledGun(event.getPlayer(), control)) return;
        event.setCancelled(true);
    }

    private boolean fireControlledGun(Player player, GunControl control) {
        Location gun = control.location;
        Entity seat = control.seat == null ? null : Bukkit.getEntity(control.seat);
        if (seat == null || !seat.getPassengers().contains(player)
                || !plugin.defenses().is(gun, DefenseStore.Type.ANTI_AIR)) return false;
        aimDeviceDisplay(gun, player.getEyeLocation().getDirection());
        plugin.flights().fireAntiAir(player, gun, control.mode);
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPluginDeviceExplosion(EntityExplodeEvent event) {
        double directHitRadius = plugin.getConfig().getDouble("defense.device-direct-hit-radius", 2.25);
        double directHitRadiusSquared = directHitRadius * directHitRadius;
        for (Entity entity : event.getLocation().getWorld().getNearbyEntities(event.getLocation(),
                directHitRadius, directHitRadius, directHitRadius)) {
            if (!(entity instanceof ItemDisplay display)) continue;
            String raw = display.getPersistentDataContainer().get(plugin.deviceBlockKey, PersistentDataType.STRING);
            if (raw == null) continue;
            String[] parts = raw.split(":");
            if (parts.length != 4) continue;
            try {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                Location block = new Location(event.getLocation().getWorld(), x, y, z);
                Location deviceCenter = block.clone().add(0.5, 0.5, 0.5);
                if (deviceCenter.distanceSquared(event.getLocation()) > directHitRadiusSquared) continue;
                Location station = plugin.defenses().missileStationCenterAt(block);
                if (station != null) destroyMissileStation(station);
                else block.getBlock().setType(Material.AIR, false);
                plugin.platforms().remove(block);
                plugin.defenses().remove(block);
                display.remove();
            } catch (NumberFormatException ignored) { }
        }
    }

    private void consumeOne(Player player, ItemStack item) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        item.setAmount(item.getAmount() - 1);
    }

    private boolean canReserveMissileFootprint(Location center) {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (x == 0 && z == 0) continue;
            if (!center.clone().add(x, 0, z).getBlock().isEmpty()) return false;
        }
        return true;
    }

    private void reserveMissileFootprint(Location center) {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            center.clone().add(x, 0, z).getBlock().setType(Material.BARRIER, false);
        }
    }

    private void destroyMissileStation(Location center) {
        removeDeviceDisplay(center);
        plugin.defenses().remove(center);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            Location block = center.clone().add(x, 0, z);
            if (block.getBlock().getType() == Material.BARRIER) block.getBlock().setType(Material.AIR, false);
        }
    }

    private void spawnDeviceDisplay(Location block, ItemStack item, double height) {
        String blockKey = block.getWorld().getUID() + ":" + block.getBlockX() + ":" + block.getBlockY() + ":" + block.getBlockZ();
        block.getWorld().spawn(block.clone().add(0.5, height, 0.5), ItemDisplay.class, display -> {
            display.setItemStack(item);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setPersistent(true);
            display.getPersistentDataContainer().set(plugin.entityKindKey, PersistentDataType.STRING, "device_display");
            display.getPersistentDataContainer().set(plugin.deviceBlockKey, PersistentDataType.STRING, blockKey);
        });
    }

    private void removeDeviceDisplay(Location block) {
        String blockKey = block.getWorld().getUID() + ":" + block.getBlockX() + ":" + block.getBlockY() + ":" + block.getBlockZ();
        block.getWorld().getNearbyEntities(block.clone().add(0.5, 0.7, 0.5), 1.5, 1.5, 1.5).stream()
                .filter(entity -> entity instanceof ItemDisplay)
                .filter(entity -> blockKey.equals(entity.getPersistentDataContainer()
                        .get(plugin.deviceBlockKey, PersistentDataType.STRING)))
                .forEach(Entity::remove);
    }

    private void aimDeviceDisplay(Location block, Vector direction) {
        String blockKey = block.getWorld().getUID() + ":" + block.getBlockX() + ":" + block.getBlockY() + ":" + block.getBlockZ();
        block.getWorld().getNearbyEntities(block.clone().add(0.5, 1.0, 0.5), 1.5, 1.5, 1.5).stream()
                .filter(entity -> entity instanceof ItemDisplay)
                .filter(entity -> blockKey.equals(entity.getPersistentDataContainer()
                        .get(plugin.deviceBlockKey, PersistentDataType.STRING)))
                .map(entity -> (ItemDisplay) entity)
                .forEach(display -> {
                    Location facing = display.getLocation();
                    facing.setDirection(direction);
                    display.setRotation(facing.getYaw(), facing.getPitch());
                });
    }

    private record GunControl(Location location, DefenseStore.AmmoType mode, UUID seat) { }
}
