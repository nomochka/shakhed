package ru.shakhed.drones;

import org.bukkit.Material;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum DroneType {
    SHAKHED("shakhed", "Шахед", Material.FIREWORK_ROCKET, true),
    FP1("fp-1", "FP-1", Material.CROSSBOW, true),
    GERAN4("geran-4", "Герань-4", Material.NETHERITE_HOE, true),
    JET("jet", "Реактивный дрон", Material.BREEZE_ROD, true),
    COPTER("copter", "Обычный коптер", Material.SPYGLASS, false);

    private final String id;
    private final String displayName;
    private final Material material;
    private final boolean requiresLaunchPad;

    DroneType(String id, String displayName, Material material, boolean requiresLaunchPad) {
        this.id = id;
        this.displayName = displayName;
        this.material = material;
        this.requiresLaunchPad = requiresLaunchPad;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public Material material() { return material; }
    public boolean requiresLaunchPad() { return requiresLaunchPad; }

    public static Optional<DroneType> byId(String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.id.equals(id) || type.name().equalsIgnoreCase(id)).findFirst();
    }
}
