package ru.shakhed.drones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public final class ItemFactory {
    public static final String KIND_DRONE = "drone";
    public static final String KIND_PAD = "launch_pad";
    public static final String KIND_BATTERY = "battery";
    public static final String KIND_CONTROLLER = "controller";
    public static final String KIND_EW = "ew_station";
    public static final String KIND_EW_BATTERY = "ew_battery";
    public static final String KIND_AA = "anti_air";
    public static final String KIND_WIRE = "ew_wire";
    public static final String KIND_BULLET_CRATE = "bullet_crate";
    public static final String KIND_MISSILE_CRATE = "missile_crate";
    public static final String KIND_PORTABLE_EW = "portable_ew";
    public static final String KIND_PORTABLE_BATTERY = "portable_ew_battery";
    public static final String KIND_ANTIPERSONNEL = "antipersonnel_payload";
    public static final String KIND_RADAR = "radar";
    public static final String KIND_MISSILE_STATION = "missile_station";
    public static final String KIND_FUEL_CANISTER = "fuel_canister";
    public static final String KIND_AUTO_PRO = "auto_pro";
    public static final String KIND_STRIKE_MISSILE = "strike_missile";

    private final ShakhedDronesPlugin plugin;
    private final NamespacedKey kindKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey ammoCountKey;

    public ItemFactory(ShakhedDronesPlugin plugin) {
        this.plugin = plugin;
        this.kindKey = new NamespacedKey(plugin, "item_kind");
        this.typeKey = new NamespacedKey(plugin, "drone_type");
        this.ammoCountKey = new NamespacedKey(plugin, "ammo_count");
    }

    public ItemStack drone(DroneType type) {
        ItemStack item = new ItemStack(type.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(type.displayName(), NamedTextColor.RED));
        meta.lore(List.of(
                Component.text(type.requiresLaunchPad() ? "Требуется стартовая площадка" : "Требуется батарея", NamedTextColor.GRAY),
                Component.text("ПКМ по блоку — установить", NamedTextColor.DARK_GRAY)
        ));
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, KIND_DRONE);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
        meta.setItemModel(new NamespacedKey("shakheddrones", type.id()));
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack launchPad() {
        return tagged(Material.LODESTONE, "Стартовая площадка", KIND_PAD,
                "Установите, затем поставьте на неё крупный дрон", "launch-pad");
    }

    public ItemStack battery() {
        return tagged(Material.LIGHTNING_ROD, "Батарея коптера", KIND_BATTERY,
                "ПКМ по установленному коптеру", "copter-battery");
    }

    public ItemStack controller() {
        return tagged(Material.COMPASS, "Пульт управления коптером", KIND_CONTROLLER,
                "Держите в руке для запуска, наведения и сброса TNT", "controller");
    }

    public ItemStack ewStation() {
        return tagged(Material.BEACON, "Станция РЭБ", KIND_EW,
                "Создаёт вокруг себя зону подавления дронов", "ew-station");
    }

    public ItemStack ewBattery() {
        return tagged(Material.REDSTONE_BLOCK, "Батарея станции РЭБ", KIND_EW_BATTERY,
                "Соедините батарею со станцией дорожкой красной пыли", "ew-battery");
    }

    public ItemStack radar() {
        return tagged(Material.DAYLIGHT_DETECTOR, "Локатор обнаружения", KIND_RADAR,
                "Обнаруживает пролетающие дроны; подключается проводом к батарее РЭБ", "ew-station");
    }

    public ItemStack missileStation() {
        return tagged(Material.HEAVY_CORE, "Ракетная пусковая станция 3×3", KIND_MISSILE_STATION,
                "Требует свободную площадку 3×3, питание и топливные канистры", "missile-station");
    }

    public ItemStack fuelCanister() {
        return tagged(Material.COPPER_BLOCK, "Топливная канистра", KIND_FUEL_CANISTER,
                "Установите рядом с ракетной станцией или автоматической ПРО", "fuel-canister");
    }

    public ItemStack autoPro() {
        return tagged(Material.OBSERVER, "Автоматическая ПРО", KIND_AUTO_PRO,
                "Перехватывает ракеты не со 100% вероятностью; требует питание, топливо и контейнер ракет", "auto-pro");
    }

    public ItemStack strikeMissile(MissileType type) {
        ItemStack item = tagged(Material.FIREWORK_ROCKET, type.displayName(), KIND_STRIKE_MISSILE,
                "ПКМ этой ракетой по запитанной станции после /drone target", "missile-" + type.id());
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
        item.setItemMeta(meta);
        return item;
    }

    public MissileType missileType(ItemStack item) {
        if (!KIND_STRIKE_MISSILE.equals(kind(item))) return null;
        return MissileType.byId(item.getItemMeta().getPersistentDataContainer()
                .get(typeKey, PersistentDataType.STRING)).orElse(null);
    }

    public ItemStack antiAir() {
        return tagged(Material.DISPENSER, "Управляемая зенитка", KIND_AA,
                "ПКМ — занять место; ЛКМ — выстрелить по направлению взгляда", "anti-air");
    }

    public ItemStack wire() {
        return tagged(Material.REDSTONE, "Провод РЭБ", KIND_WIRE,
                "Прокладывается как красная пыль", "wire");
    }

    public ItemStack bulletCrate() {
        return ammoCrate(false, plugin.getConfig().getInt("defense.bullet-crate-capacity", 500));
    }

    public ItemStack missileCrate() {
        return ammoCrate(true, plugin.getConfig().getInt("defense.missile-crate-capacity", 6));
    }

    public ItemStack ammoCrate(boolean missiles, int count) {
        ItemStack item = tagged(missiles ? Material.CHISELED_COPPER : Material.BARREL,
                missiles ? "Контейнер ракет" : "Ящик ленты",
                missiles ? KIND_MISSILE_CRATE : KIND_BULLET_CRATE,
                "Боезапас: " + count + (missiles ? "/6 ракет" : "/500 выстрелов"),
                missiles ? "missile-crate" : "bullet-crate");
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(ammoCountKey, PersistentDataType.INTEGER, count);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack antiAirBullet() {
        return tagged(Material.IRON_NUGGET, "Зенитный снаряд", "projectile", "", "anti-air-bullet");
    }

    public ItemStack antiAirMissile() {
        return tagged(Material.FIREWORK_ROCKET, "Зенитная ракета", "projectile", "", "anti-air-missile");
    }

    public ItemStack portableEw() {
        return tagged(Material.RECOVERY_COMPASS, "Переносной РЭБ", KIND_PORTABLE_EW,
                "ПКМ — включить/выключить; расходует батареи из инвентаря", "portable-ew");
    }

    public ItemStack portableBattery() {
        return tagged(Material.COPPER_INGOT, "Батарея переносного РЭБ", KIND_PORTABLE_BATTERY,
                "Расходуется работающим переносным РЭБ", "portable-battery");
    }

    public boolean isPortableEw(ItemStack item) { return KIND_PORTABLE_EW.equals(kind(item)); }
    public boolean isPortableBattery(ItemStack item) { return KIND_PORTABLE_BATTERY.equals(kind(item)); }
    public boolean isPortablePowerCell(ItemStack item) {
        String itemKind = kind(item);
        return KIND_PORTABLE_BATTERY.equals(itemKind) || KIND_EW_BATTERY.equals(itemKind) || KIND_BATTERY.equals(itemKind);
    }

    public ItemStack antipersonnelPayload() {
        return tagged(Material.TRIPWIRE_HOOK, "Противопехотный боеприпас", KIND_ANTIPERSONNEL,
                "Меньше разрушений, больше урона игрокам", "antipersonnel-payload");
    }

    public int ammoCount(ItemStack item, int fallback) {
        if (item == null || !item.hasItemMeta()) return fallback;
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(ammoCountKey, PersistentDataType.INTEGER, fallback);
    }

    public boolean isController(ItemStack item) { return KIND_CONTROLLER.equals(kind(item)); }

    private ItemStack tagged(Material material, String name, String kind, String lore, String model) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.YELLOW));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, kind);
        meta.setItemModel(new NamespacedKey("shakheddrones", model));
        item.setItemMeta(meta);
        return item;
    }

    public String kind(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
    }

    public DroneType droneType(ItemStack item) {
        if (!KIND_DRONE.equals(kind(item))) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return id == null ? null : DroneType.byId(id).orElse(null);
    }
}
