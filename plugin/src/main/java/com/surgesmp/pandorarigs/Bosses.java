package com.surgesmp.pandorarigs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rigs nobody is wearing: bosses, which are an invisible mob with the rig riding it, and statues,
 * which kneel where they were put and watch whoever walks up.
 *
 * <p>The mob is the boss — its AI, its hitbox, its health. The rig is only what it looks like. That
 * keeps every vanilla fight mechanic (knockback, shields, sweeping, arrows) working with no code
 * here, and means any melee mob in the config is a different boss.
 */
final class Bosses {

    private record Boss(Mob mob, Body body, BossBar bar) {
    }

    private final PandoraRigs plugin;
    private final NamespacedKey tag;
    private final Map<UUID, Boss> bosses = new HashMap<>();
    private final List<Body> statues = new ArrayList<>();

    Bosses(PandoraRigs plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "boss");
    }

    NamespacedKey tag() {
        return tag;
    }

    // ---------------------------------------------------------------- bosses

    Mob boss(RigType type, Location at) {
        RigType.Boss spec = type.boss();
        Component name = MiniMessage.miniMessage().deserialize(type.display());
        Entity spawned = at.getWorld().spawn(at, spec.mob().getEntityClass(), e -> {
            if (!(e instanceof Mob m)) {
                return;
            }
            m.setInvisible(true);
            m.setSilent(true);
            m.setPersistent(false);
            m.setRemoveWhenFarAway(false);
            m.setCanPickupItems(false);
            m.customName(name);
            m.setCustomNameVisible(false);
            if (m instanceof Ageable a) {
                a.setAdult();
            }
            if (m instanceof Zombie z) {
                z.setShouldBurnInDay(false);
            }
            EntityEquipment eq = m.getEquipment();
            eq.clear();
            base(m, Attribute.MAX_HEALTH, spec.health());
            m.setHealth(spec.health());
            base(m, Attribute.ATTACK_DAMAGE, spec.damage());
            base(m, Attribute.MOVEMENT_SPEED, spec.speed());
            base(m, Attribute.SCALE, spec.scale());
            base(m, Attribute.FOLLOW_RANGE, 40);
            base(m, Attribute.KNOCKBACK_RESISTANCE, 0.6);
            m.getPersistentDataContainer().set(tag, PersistentDataType.STRING, type.id());
        });
        if (!(spawned instanceof Mob mob)) {
            spawned.remove();
            return null;
        }
        Body body = new Body(plugin, type, spec.scale());
        body.wear(mob);
        if (spec.weapon() != null) {
            body.hold(new ItemStack(spec.weapon()), null);
        }
        BossBar bar = plugin.getServer().createBossBar(
                LegacyComponentSerializer.legacySection().serialize(name), spec.bar(), BarStyle.SEGMENTED_10);
        bosses.put(mob.getUniqueId(), new Boss(mob, body, bar));
        return mob;
    }

    private static void base(LivingEntity e, Attribute attribute, double value) {
        AttributeInstance a = e.getAttribute(attribute);
        if (a != null) {
            a.setBaseValue(value);
        }
    }

    Body body(Entity mob) {
        Boss b = bosses.get(mob.getUniqueId());
        return b == null ? null : b.body;
    }

    /** The mob died or was removed: the body goes with it. */
    void gone(Entity mob) {
        Boss b = bosses.remove(mob.getUniqueId());
        if (b != null) {
            b.body.remove();
            b.bar.removeAll();
        }
    }

    // ---------------------------------------------------------------- statues

    void statue(RigType type, Location at) {
        Body body = new Body(plugin, type, type.scale());
        body.stand(at);
        statues.add(body);
    }

    // ---------------------------------------------------------------- upkeep

    void tick() {
        for (Iterator<Boss> it = bosses.values().iterator(); it.hasNext(); ) {
            Boss b = it.next();
            if (!b.mob.isValid() || b.mob.isDead()) {
                b.body.remove();
                b.bar.removeAll();
                it.remove();
                continue;
            }
            b.body.tick();
            AttributeInstance max = b.mob.getAttribute(Attribute.MAX_HEALTH);
            b.bar.setProgress(Math.max(0, Math.min(1, b.mob.getHealth() / (max == null ? 20 : max.getValue()))));
            Location at = b.mob.getLocation();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                boolean near = p.getWorld() == at.getWorld() && p.getLocation().distanceSquared(at) < 48 * 48;
                if (near && !b.bar.getPlayers().contains(p)) {
                    b.bar.addPlayer(p);
                } else if (!near && b.bar.getPlayers().contains(p)) {
                    b.bar.removePlayer(p);
                }
            }
        }
        for (Body statue : statues) {
            statue.tick();
        }
    }

    /**
     * Removes bosses and statues within {@code radius} of {@code at}, or all of them when {@code at}
     * is null. Returns how many went.
     */
    int clear(Location at, double radius) {
        int n = 0;
        for (Iterator<Boss> it = bosses.values().iterator(); it.hasNext(); ) {
            Boss b = it.next();
            if (within(b.mob.getLocation(), at, radius)) {
                b.body.remove();
                b.bar.removeAll();
                b.mob.remove();
                it.remove();
                n++;
            }
        }
        for (Iterator<Body> it = statues.iterator(); it.hasNext(); ) {
            Body s = it.next();
            if (within(s.rig.feet(), at, radius)) {
                s.remove();
                it.remove();
                n++;
            }
        }
        return n;
    }

    private static boolean within(Location l, Location at, double radius) {
        return at == null || (l != null && l.getWorld() == at.getWorld() && l.distanceSquared(at) <= radius * radius);
    }

    int bossCount() {
        return bosses.size();
    }

    int statueCount() {
        return statues.size();
    }
}
