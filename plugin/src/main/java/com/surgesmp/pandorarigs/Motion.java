package com.surgesmp.pandorarigs;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * What the person wearing Pandora is doing with their body, read once a tick.
 *
 * <p>Everything the animator knows about movement comes through here, and all of it is something
 * the server can actually see: where the player is this tick against last tick, which way they
 * are looking, and the handful of flags the client reports — on the ground, sneaking, sprinting,
 * flying, swimming, climbing, riding.
 *
 * <p><b>Velocity is smoothed and distance is not.</b> The client sends its position when it has
 * moved, which is not quite every server tick, so a raw per-tick delta stutters between nothing
 * and double. Speed — which picks the gait and sets the stride amplitude — is averaged over a few
 * ticks so the gait does not flicker. The distance actually covered is kept raw, because it is what
 * turns the walk cycle, and summed over a stride the raw number is exactly right.
 */
final class Motion {

    /** A move longer than this in one tick was a teleport, not a step. */
    private static final double TELEPORT = 4.0;

    private Location last;
    private final Vector smoothed = new Vector();
    private float lastYaw;

    /** Horizontal distance covered this tick, raw, in blocks. */
    double stepped;
    /** Horizontal speed, smoothed, blocks per tick. */
    double speed;
    /** Vertical speed this tick, blocks per tick. Up is positive. */
    double vy;
    /** Smoothed horizontal velocity, for which way the body is travelling. */
    Vector velocity = new Vector();

    boolean onGround;
    boolean flying;
    boolean gliding;
    boolean swimming;
    boolean inWater;
    boolean climbing;
    boolean sneaking;
    boolean sprinting;
    boolean riding;

    /** Where the head is pointed. Minecraft convention: yaw 0 is south, pitch positive is down. */
    float yaw;
    float pitch;
    /** How fast the view is turning, degrees per tick. Drives the bank in flight. */
    float yawRate;

    @SuppressWarnings("deprecation")
    void sample(LivingEntity player) {
        Location now = player.getLocation();
        if (last != null && last.getWorld() == now.getWorld()) {
            double dx = now.getX() - last.getX();
            double dy = now.getY() - last.getY();
            double dz = now.getZ() - last.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal > TELEPORT || Math.abs(dy) > TELEPORT) {
                dx = dy = dz = 0;
                horizontal = 0;
            }
            stepped = horizontal;
            vy = dy;
            smoothed.multiply(0.5).add(new Vector(dx, 0, dz).multiply(0.5));
        } else {
            stepped = 0;
            vy = 0;
            smoothed.zero();
        }
        last = now;
        velocity = smoothed.clone();
        speed = velocity.length();

        onGround = player.isOnGround();
        flying = player instanceof Player p && p.isFlying();
        gliding = player.isGliding();
        swimming = player.isSwimming();
        inWater = player.isInWater();
        climbing = player.isClimbing();
        sneaking = player.isSneaking();
        sprinting = player instanceof Player p ? p.isSprinting() : speed > 0.2;
        riding = player.isInsideVehicle();

        yawRate = wrap(now.getYaw() - lastYaw);
        lastYaw = now.getYaw();
        yaw = now.getYaw();
        pitch = now.getPitch();
    }

    /** Forgets the last position, so the next sample after a teleport reads as standing still. */
    void reset() {
        last = null;
        smoothed.zero();
    }

    /** The yaw the body is travelling toward, Minecraft convention. Meaningless when still. */
    float travelYaw() {
        return (float) Math.toDegrees(Math.atan2(-velocity.getX(), velocity.getZ()));
    }

    /** Wraps an angle difference into -180..180. */
    static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }
}
