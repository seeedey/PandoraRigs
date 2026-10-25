package com.surgesmp.pandorarigs;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * One rig in the world and the animator that moves it, plus who (if anyone) is driving it. Avatars,
 * bosses and statues are all one of these; they differ only in what drives it and what happens
 * around it.
 */
final class Body implements Animator.Feel {

    final RigType type;
    final Rig rig;
    final Animator animator;
    /** Who wears it — a player, a boss mob — or null for a statue. */
    LivingEntity driver;

    Body(PandoraRigs plugin, RigType type, float scale) {
        this.type = type;
        this.rig = new Rig(plugin, SkinPack.NAMESPACE, type.id());
        this.rig.scale(scale);
        this.animator = new Animator(rig);
        this.animator.feel(this);
    }

    /** Puts it on {@code who} and lets their movement drive it. */
    void wear(LivingEntity who) {
        driver = who;
        if (!rig.valid()) {
            rig.spawn(who.getLocation(), rig.scale());
        }
        rig.mount(who);
        animator.mode(Animator.Mode.DRIVEN);
    }

    /** Stands it alone at {@code at}, kneeling and watching whoever comes near. */
    void stand(Location at) {
        driver = null;
        rig.spawn(at, rig.scale());
        animator.mode(Animator.Mode.DORMANT);
        animator.face(at.getYaw());
    }

    void tick() {
        if (driver != null) {
            if (!driver.isValid() && !(driver instanceof Player p && p.isOnline())) {
                return;
            }
            if (driver.isDead()) {
                return;
            }
            rig.heal();
            animator.tick(driver);
        } else {
            rig.heal();
            animator.watch(nearest());
            animator.tick(null);
        }
    }

    void hold(ItemStack main, ItemStack off) {
        rig.hold(main, off);
    }

    void remove() {
        rig.remove();
        driver = null;
    }

    private Location nearest() {
        Location feet = rig.feet();
        if (feet == null) {
            return null;
        }
        Player best = null;
        double bestD = 12 * 12;
        for (Player p : feet.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(feet);
            if (d < bestD) {
                best = p;
                bestD = d;
            }
        }
        return best == null ? null : best.getLocation();
    }

    // ---------------------------------------------------------------- the body's effect on the world

    @Override
    public void footstep(Location foot, boolean sprinting) {
        if (foot == null || foot.getWorld() == null) {
            return;
        }
        float scale = rig.scale();
        // A normal-sized body walks quietly; the footfall only gets heavy as it grows.
        if (scale >= 1.3f) {
            foot.getWorld().playSound(foot, Sound.ENTITY_IRON_GOLEM_STEP, (sprinting ? 0.7f : 0.45f) * scale / 1.5f,
                    sprinting ? 0.9f : 0.75f);
        }
        Block under = foot.clone().subtract(0, 0.2, 0).getBlock();
        if (under.getType().isSolid()) {
            foot.getWorld().spawnParticle(Particle.BLOCK, foot.clone().add(0, 0.05, 0), sprinting ? 6 : 3,
                    0.15, 0.02, 0.15, 0.05, under.getBlockData());
        }
    }

    @Override
    public void landed(Location feet, float impact) {
        if (feet == null || feet.getWorld() == null) {
            return;
        }
        Block under = feet.clone().subtract(0, 0.3, 0).getBlock();
        if (under.getType().isSolid()) {
            feet.getWorld().spawnParticle(Particle.BLOCK, feet.clone().add(0, 0.1, 0), (int) (8 + impact * 30),
                    0.5 + impact, 0.05, 0.5 + impact, 0.1, under.getBlockData());
        }
        if (impact > 0.55f) {
            feet.getWorld().playSound(feet, Sound.ENTITY_IRON_GOLEM_STEP, 1.2f, 0.5f);
            feet.getWorld().playSound(feet, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 0.6f);
        }
    }
}
