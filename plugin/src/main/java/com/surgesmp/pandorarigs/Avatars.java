package com.surgesmp.pandorarigs;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Players wearing a rig. Their own body goes invisible, the rig rides them, and everyone else sees
 * the rig walk, swing and hold what they hold. Inventory, health and game mode are left alone —
 * this is a costume, not Surge's Pandora avatar with its stash and kit.
 */
final class Avatars {

    private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    private final PandoraRigs plugin;
    private final Map<UUID, Body> worn = new HashMap<>();
    /** On the player while they wear a rig — survives a crash, so the invisibility can be taken off on rejoin. */
    private final NamespacedKey marker;
    private final NamespacedKey modifierKey;
    private int tick;

    Avatars(PandoraRigs plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "avatar");
        this.modifierKey = new NamespacedKey(plugin, "avatar_scale");
    }

    Body of(Player player) {
        return worn.get(player.getUniqueId());
    }

    boolean wearing(Player player) {
        return worn.containsKey(player.getUniqueId());
    }

    void become(Player player, RigType type) {
        leave(player);
        Body body = new Body(plugin, type, type.scale());
        body.wear(player);
        if (plugin.getConfig().getBoolean("avatar.hide-head-from-self", true)) {
            body.rig.hideFrom(player, EnumSet.of(Rig.Bone.HEAD));
        }
        worn.put(player.getUniqueId(), body);
        player.getPersistentDataContainer().set(marker, PersistentDataType.STRING, type.id());
        dress(player, type.scale());
    }

    /** Takes the rig off and gives them their own body back. Safe on someone not wearing one. */
    void leave(Player player) {
        Body body = worn.remove(player.getUniqueId());
        if (body != null) {
            body.remove();
        }
        undress(player);
    }

    void leaveAll() {
        for (UUID id : new java.util.ArrayList<>(worn.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                leave(p);
            } else {
                worn.remove(id).remove();
            }
        }
    }

    /** On join: if they were wearing one when the server went down, put them back to themselves. */
    void repair(Player player) {
        if (player.getPersistentDataContainer().has(marker)) {
            undress(player);
        }
    }

    /** Resizes a worn rig, and the hitbox under it. */
    void scale(Player player, float scale) {
        Body body = of(player);
        if (body == null) {
            return;
        }
        body.rig.scale(scale);
        body.animator.cast();
        modifier(player, scale);
    }

    private void dress(Player player, float scale) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION,
                0, false, false, false));
        modifier(player, scale);
    }

    private void modifier(Player player, float scale) {
        AttributeInstance size = player.getAttribute(Attribute.SCALE);
        if (size == null) {
            return;
        }
        size.removeModifier(modifierKey);
        // Transient, so a crash mid-costume does not leave anyone the wrong size for good.
        size.addTransientModifier(new AttributeModifier(modifierKey, scale - 1.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
    }

    private void undress(Player player) {
        if (!player.getPersistentDataContainer().has(marker)) {
            return;
        }
        player.getPersistentDataContainer().remove(marker);
        PotionEffect invis = player.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (invis != null && invis.isInfinite()) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
        AttributeInstance size = player.getAttribute(Attribute.SCALE);
        if (size != null) {
            size.removeModifier(modifierKey);
        }
        // Everyone was told their hands and armour were empty; tell them the truth again.
        for (Player viewer : player.getWorld().getPlayers()) {
            if (!viewer.equals(player)) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    if (slot != EquipmentSlot.BODY && slot != EquipmentSlot.SADDLE) {
                        viewer.sendEquipmentChange(player, slot, player.getInventory().getItem(slot));
                    }
                }
            }
        }
    }

    void tick() {
        tick++;
        for (Map.Entry<UUID, Body> entry : worn.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || player.isDead()) {
                continue;
            }
            Body body = entry.getValue();
            ItemStack main = real(player.getInventory().getItemInMainHand());
            ItemStack off = real(player.getInventory().getItemInOffHand());
            body.hold(main, off);
            if (tick % 20 == 0 && !player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                // Milk, or a command. The rig over a visible player is two bodies.
                dress(player, body.rig.scale());
            }
            hideReal(player, main != null || off != null, tick % 10 == 0);
            body.tick();
        }
    }

    private static ItemStack real(ItemStack stack) {
        return stack == null || stack.getType().isAir() ? null : stack;
    }

    /**
     * An invisible player still draws what it holds and wears, at its own invisible body — not where
     * the rig's hands are. So everyone else is told those slots are empty; the item they see is the
     * one the rig carries. Hands every tick while something is held (it changes with every scroll),
     * armour twice a second.
     */
    private void hideReal(Player player, boolean holding, boolean armour) {
        if (!holding && !armour) {
            return;
        }
        ItemStack air = ItemStack.empty();
        Location at = player.getLocation();
        for (Player viewer : player.getWorld().getPlayers()) {
            if (viewer.equals(player) || viewer.getLocation().distanceSquared(at) > 160 * 160) {
                continue;
            }
            if (holding) {
                viewer.sendEquipmentChange(player, EquipmentSlot.HAND, air);
                viewer.sendEquipmentChange(player, EquipmentSlot.OFF_HAND, air);
            }
            if (armour) {
                for (EquipmentSlot slot : ARMOUR) {
                    viewer.sendEquipmentChange(player, slot, air);
                }
            }
        }
    }

    Iterable<Body> bodies() {
        return worn.values();
    }
}
