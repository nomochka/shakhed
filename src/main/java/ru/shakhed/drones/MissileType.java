package ru.shakhed.drones;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum MissileType {
    FLAMINGO("flamingo", "Фламинго"),
    ORESHNIK("oreshnik", "Орешник"),
    ISKANDER("iskander", "Искандер"),
    LOITERING("loitering", "Баражирующая ракета");

    private final String id;
    private final String displayName;

    MissileType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }

    public static Optional<MissileType> byId(String id) {
        if (id == null) return Optional.empty();
        String normalized = id.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.id.equals(normalized)).findFirst();
    }
}
