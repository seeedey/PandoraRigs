package com.surgesmp.pandorarigs;

import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One rig as the config describes it: which skin's models it wears, how big it is worn, and what it
 * is when it is set loose as a boss. The id is also the model prefix — {@code pandorarigs:<id>_head}.
 */
public record RigType(String id, String display, float scale, Boss boss) {

    /** Model ids end up in resource-pack paths, which allow only these. */
    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");

    public record Boss(EntityType mob, double health, double damage, double speed, float scale,
                       Material weapon, BarColor bar) {
    }

    /** Reads {@code rigs.<id>}, falling back to {@code defaults} for anything it leaves out. */
    static RigType read(String id, ConfigurationSection rig, ConfigurationSection defaults) {
        ConfigurationSection b = rig.getConfigurationSection("boss");
        ConfigurationSection db = defaults == null ? null : defaults.getConfigurationSection("boss");
        Boss boss = new Boss(
                entity(str(b, db, "mob", "HUSK")),
                num(b, db, "health", 300),
                num(b, db, "damage", 10),
                num(b, db, "speed", 0.28),
                (float) num(b, db, "scale", 1.6),
                material(str(b, db, "weapon", "")),
                bar(str(b, db, "bar-color", "PURPLE")));
        float scale = (float) rig.getDouble("scale", defaults == null ? 1.0 : defaults.getDouble("scale", 1.0));
        return new RigType(id, rig.getString("display", id), scale, boss);
    }

    private static String str(ConfigurationSection a, ConfigurationSection b, String key, String fallback) {
        if (a != null && a.isSet(key)) {
            return a.getString(key);
        }
        return b != null ? b.getString(key, fallback) : fallback;
    }

    private static double num(ConfigurationSection a, ConfigurationSection b, String key, double fallback) {
        if (a != null && a.isSet(key)) {
            return a.getDouble(key);
        }
        return b != null ? b.getDouble(key, fallback) : fallback;
    }

    private static EntityType entity(String raw) {
        try {
            EntityType type = EntityType.valueOf(raw.toUpperCase(Locale.ROOT));
            return type.isAlive() && type.isSpawnable() ? type : EntityType.HUSK;
        } catch (IllegalArgumentException ex) {
            return EntityType.HUSK;
        }
    }

    private static Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Material m = Material.matchMaterial(raw);
        return m != null && m.isItem() && !m.isAir() ? m : null;
    }

    private static BarColor bar(String raw) {
        try {
            return BarColor.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return BarColor.PURPLE;
        }
    }
}
