package com.surgesmp.pandorarigs;

import com.surgesmp.pandorarigs.Rig.Bone;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.EnumSet;
import java.util.Set;

import static com.surgesmp.pandorarigs.Rig.Bone.ARM_LEFT;
import static com.surgesmp.pandorarigs.Rig.Bone.ARM_RIGHT;
import static com.surgesmp.pandorarigs.Rig.Bone.BODY;
import static com.surgesmp.pandorarigs.Rig.Bone.HEAD;
import static com.surgesmp.pandorarigs.Rig.Bone.LEG_LEFT;
import static com.surgesmp.pandorarigs.Rig.Bone.LEG_RIGHT;

/**
 * What Pandora's body is doing, tick by tick.
 *
 * <p>The King's animator has one state at a time, handed over by the fight. That model does not
 * survive contact with a person: somebody wearing Pandora can walk, swing and talk at once, and
 * start or stop any of them on any tick. So this builds each tick's pose in <b>three layers</b>:
 *
 * <ol>
 *   <li><b>Locomotion</b> — the whole body, from movement: idle, walk, run, sneak, jump, fall,
 *       hover, fly, swim, climb, sit.</li>
 *   <li><b>Action</b> — the arms and torso only, from events: a swing, speech, a cast. It has a
 *       weight that rises and falls, so it lays over the legs rather than replacing them — you can
 *       swing while running. An emote is the same layer with the whole body in its mask.</li>
 *   <li><b>Additive</b> — the head following the camera, breathing, a flinch, a landing squash.
 *       Added on top of whatever the first two decided.</li>
 * </ol>
 *
 * <p>Then every bone is eased toward the result, which is what turns a layer switching on in one
 * tick into a limb that moves there over several.
 *
 * <h2>Angle conventions, because every number below depends on them</h2>
 *
 * The rig faces +Z at a yaw of 0; its right side is -X. On a hanging limb, <b>negative X swings it
 * forward</b>. On the torso or the head, <b>positive X tips it forward</b> — a bow, or looking down.
 * <b>Positive Y turns toward its own left.</b> On an arm, Z away from the body is <b>negative on
 * the right and positive on the left</b>. Rotation order is Y, then X, then Z.
 */
final class Animator {

    enum Mode {
        /** Somebody is wearing it and it moves with them. */
        DRIVEN,
        /** Nobody is. It kneels where it was left and watches whoever comes near. */
        DORMANT,
        /** A cutscene is posing it by hand. */
        SCRIPTED
    }

    /** The upper body — what an action is allowed to touch. */
    private static final Set<Bone> UPPER = EnumSet.of(BODY, HEAD, ARM_RIGHT, ARM_LEFT);

    /** Blocks travelled per full stride (two steps), at scale 1. */
    private static final double STRIDE = 2.3;

    /** Ticks for one swing, from the wind-up to the arm coming back. */
    private static final int SWING_TICKS = 8;

    /** Hooks for the things a body does to the world: footfalls and landings. */
    interface Feel {
        void footstep(Location foot, boolean sprinting);

        void landed(Location feet, float impact);
    }

    private final Rig rig;
    private final Motion motion = new Motion();
    private Feel feel;

    private Mode mode = Mode.DORMANT;
    private int age;

    // ---- driven
    private float bodyYaw;
    private boolean bodyYawSet;
    private boolean backwards;
    private double phase;
    private double lastCos;
    private boolean airborne;
    private double fallSpeed;
    private int idle;
    private float lastPitch;

    // ---- actions
    private int swing = -1;
    private boolean swingLeft;
    private int hitstop;
    private int speaking;
    private int nod;
    private int cast = -1;
    private int flinch;
    private float flinchSide;
    private int landing = -1;
    private float impact;
    private Emote emote;
    private int emoteAge;
    private Location pointAt;

    // ---- dormant
    private Location watching;

    // ---- scripted
    private Pose script = new Pose();
    private float scriptRate = 0.2f;

    Animator(Rig rig) {
        this.rig = rig;
    }

    void feel(Feel feel) {
        this.feel = feel;
    }

    Mode mode() {
        return mode;
    }

    void mode(Mode next) {
        if (next == mode) {
            return;
        }
        mode = next;
        motion.reset();
        emote = null;
        swing = -1;
        cast = -1;
        landing = -1;
        if (next == Mode.DRIVEN) {
            bodyYawSet = false;
        }
    }

    /** Where the current body is turned. The avatar reads it for anything aimed "in front". */
    float bodyYaw() {
        return bodyYaw;
    }

    // ---------------------------------------------------------------- triggers

    /** Left click. Alternates hands, so a flurry reads as a flurry. */
    void swing() {
        if (swing >= 0 && swing < SWING_TICKS / 2) {
            return;
        }
        swingLeft = !swingLeft;
        swing = 0;
        cancelEmoteUnlessHeld();
    }

    /** The swing connected. Holds the frame for two ticks, which is most of what makes it land. */
    void connected() {
        hitstop = 2;
    }

    void flinch(float side) {
        flinch = 6;
        flinchSide = side;
    }

    /** Talking for this many ticks. The voice calls it per line. */
    void speak(int ticks) {
        speaking = Math.max(speaking, ticks);
    }

    /** One nod — the voice calls this on every full stop. */
    void nod() {
        nod = 7;
    }

    /** Both hands up, for a size change or anything else that should look like it cost something. */
    void cast() {
        cast = 0;
    }

    void emote(Emote next, Location pointAt) {
        this.emote = next;
        this.emoteAge = 0;
        this.pointAt = pointAt;
    }

    Emote emote() {
        return emote;
    }

    void stopEmote() {
        emote = null;
    }

    private void cancelEmoteUnlessHeld() {
        if (emote != null && emote != Emote.RISE) {
            emote = null;
        }
    }

    /** Who the dormant body is looking at, or null to bow its head. */
    void watch(Location who) {
        this.watching = who;
    }

    /** A cutscene's pose for this tick and how fast to get there. */
    void script(Pose pose, float rate) {
        this.script = pose;
        this.scriptRate = rate;
    }

    // ---------------------------------------------------------------- the tick

    /** One tick. {@code driver} is who is wearing it, or null. */
    void tick(LivingEntity driver) {
        age++;
        Pose target;
        float rate;
        switch (mode) {
            case DRIVEN -> {
                if (driver == null) {
                    return;
                }
                motion.sample(driver);
                target = driven();
                rate = 0.55f;
            }
            case SCRIPTED -> {
                target = script;
                rate = scriptRate;
            }
            default -> {
                target = dormant();
                rate = 0.07f;
            }
        }
        write(target, rate);
    }

    private void write(Pose pose, float rate) {
        for (Bone bone : Bone.values()) {
            float[] a = pose.get(bone);
            rig.ease(bone, a[0], a[1], a[2], rate);
        }
        rig.lift(rig.lift() + (pose.lift - rig.lift()) * Math.min(1f, rate * 1.4f));
        currentPitch += (pose.pitch - currentPitch) * rate;
        currentRoll += (pose.roll - currentRoll) * rate;
        float yaw = mode == Mode.DRIVEN ? bodyYaw : rig.yaw();
        rig.orient(yaw, currentPitch, currentRoll);
        rig.apply();
    }

    /** The root's lean and bank as they are now, eased toward each tick's target. */
    private float currentPitch;
    private float currentRoll;

    /** Sets the whole body's yaw directly — for a standing body placed somewhere by hand. */
    void face(float yaw) {
        this.bodyYaw = yaw;
        this.bodyYawSet = true;
        rig.orient(yaw, currentPitch, currentRoll);
    }

    // ---------------------------------------------------------------- driven

    private Pose driven() {
        turnBody();

        Pose pose;
        if (motion.riding) {
            pose = sit();
        } else if (motion.swimming || motion.gliding) {
            pose = swim();
        } else if (motion.climbing) {
            pose = climb();
        } else if (motion.flying) {
            pose = motion.speed > 0.06 ? fly() : hover();
        } else if (!motion.onGround && !motion.inWater) {
            pose = air();
        } else if (motion.inWater) {
            pose = tread();
        } else {
            pose = ground();
        }
        landingWatch();

        actions(pose);
        additive(pose);
        return pose;
    }

    /**
     * The body turns the way a vanilla body does: toward where it is going, never more than 50°
     * off where the head is looking, and when standing still only once the head has gone past
     * that. Walking backwards keeps the body facing forward and runs the legs in reverse.
     *
     * <p>The turn rate is capped. Partly because a body that snaps looks like a camera; mostly
     * because each joint's translation is interpolated in a straight line, so a 180° flick in two
     * ticks would pull every limb through the chest on its way round.
     */
    private void turnBody() {
        float head = motion.yaw;
        if (!bodyYawSet) {
            bodyYaw = head;
            bodyYawSet = true;
        }
        float target;
        boolean moving = motion.speed > 0.02 && !(motion.flying && motion.speed < 0.06);
        if (moving) {
            float rel = Motion.wrap(motion.travelYaw() - head);
            backwards = Math.abs(rel) > 105;
            if (backwards) {
                rel = Motion.wrap(rel + 180);
            }
            target = head + clamp(rel, -50, 50);
        } else {
            backwards = false;
            float rel = Motion.wrap(head - bodyYaw);
            target = Math.abs(rel) > 50 ? head - Math.signum(rel) * 50 : bodyYaw;
        }
        float diff = Motion.wrap(target - bodyYaw);
        float cap = moving ? 18 : 14;
        bodyYaw = Motion.wrap(bodyYaw + clamp(diff * 0.4f, -cap, cap));
    }

    private Pose ground() {
        Pose pose = new Pose();
        double stride = STRIDE * rig.scale();
        phase += (backwards ? -1 : 1) * (motion.stepped / stride) * Math.PI * 2;

        float gait = (float) Math.min(1.35, motion.speed / 0.2);
        float amp;
        if (motion.sneaking) {
            amp = 20 * Math.min(1f, gait * 1.8f);
        } else if (motion.sprinting) {
            amp = 50 * Math.min(1f, gait);
        } else {
            amp = 34 * Math.min(1f, gait);
        }
        float s = (float) Math.sin(phase);
        float c = (float) Math.cos(phase);

        // Footfalls land when the legs are furthest apart, which is where cos crosses zero.
        if (amp > 10 && Math.signum(c) != Math.signum(lastCos) && feel != null) {
            Bone forward = s > 0 ? LEG_RIGHT : LEG_LEFT;
            feel.footstep(rig.point(forward, 0, -12, 0), motion.sprinting);
        }
        lastCos = c;

        float legSwing = s * amp;
        pose.set(LEG_RIGHT, -legSwing, 0, 0);
        pose.set(LEG_LEFT, legSwing, 0, 0);
        // The hips drop exactly as far as a straight leg at that angle needs, so the feet stay on
        // the floor at every point of the stride rather than sinking through it or hovering.
        pose.lift = -12f * (1f - (float) Math.cos(Math.toRadians(Math.abs(legSwing))));

        float armSwing = legSwing * (motion.sprinting ? 1.05f : 0.75f);
        float bias = motion.sprinting ? -14 : 0;
        pose.set(ARM_RIGHT, armSwing + bias, 0, -5 - gait * 2);
        pose.set(ARM_LEFT, -armSwing + bias, 0, 5 + gait * 2);
        pose.set(BODY, motion.sprinting ? 13 : 3 * gait, -legSwing * 0.12f, 0);

        float still = 1f - Math.min(1f, gait * 3);
        if (still > 0) {
            breathe(pose, still);
            idle++;
            if (idle > 200) {
                fidget(pose, still);
            }
        } else {
            idle = 0;
        }

        if (motion.sneaking) {
            pose.add(BODY, 24, 0, 0);
            pose.add(ARM_RIGHT, -14, 0, -8);
            pose.add(ARM_LEFT, -14, 0, 8);
            pose.lift -= 2.5f;
        }
        return pose;
    }

    /** Standing breath and a slow shift of weight from one hip to the other. */
    private void breathe(Pose pose, float weight) {
        float breath = (float) Math.sin(age * 0.07) * 1.6f * weight;
        float shift = (float) Math.sin(age * 0.021) * weight;
        pose.add(BODY, breath, 0, shift * 2.2f);
        pose.add(ARM_RIGHT, -breath * 0.6f, 0, -2 * weight);
        pose.add(ARM_LEFT, -breath * 0.6f, 0, 2 * weight);
        pose.add(LEG_RIGHT, 0, 0, shift * 2.5f);
        pose.add(LEG_LEFT, 0, 0, shift * 2.5f);
        pose.lift += -Math.abs(shift) * 0.4f;
    }

    /**
     * Somebody left the controls alone. Only while the camera is still, too, because every one of
     * these moves the head, and the head belongs to the camera the moment the driver touches it.
     */
    private void fidget(Pose pose, float weight) {
        boolean cameraStill = Math.abs(motion.yawRate) < 0.5f && Math.abs(motion.pitch - lastPitch) < 0.5f;
        lastPitch = motion.pitch;
        if (!cameraStill) {
            idle = 150;
            return;
        }
        int cycle = (idle / 140) % 3;
        float t = (idle % 140) / 140f;
        float w = (float) Math.sin(Math.PI * Math.min(1, t * 1.6)) * weight;
        switch (cycle) {
            case 0 -> pose.add(HEAD, 6 * w, 0, (float) Math.sin(t * Math.PI * 4) * 12 * w);
            case 1 -> pose.add(HEAD, -4 * w, 55 * w, 0);
            default -> {
                pose.add(ARM_RIGHT, -24 * w, 0, -12 * w + (float) Math.sin(age * 0.9) * 4 * w);
                pose.add(HEAD, 14 * w, -18 * w, 0);
            }
        }
    }

    private Pose air() {
        Pose pose = new Pose();
        float t = (float) clamp((0.12 - motion.vy) / 0.55, 0, 1);
        Pose rising = new Pose()
                .set(LEG_RIGHT, -38, 0, -3)
                .set(LEG_LEFT, 16, 0, 3)
                .set(ARM_RIGHT, -46, 0, -22)
                .set(ARM_LEFT, -40, 0, 22)
                .set(BODY, -3, 0, 0);
        float flail = (float) Math.sin(age * 0.45) * 9;
        Pose falling = new Pose()
                .set(LEG_RIGHT, -10 + flail, 0, -6)
                .set(LEG_LEFT, 8 - flail, 0, 6)
                .set(ARM_RIGHT, -16 - flail, 0, -64)
                .set(ARM_LEFT, -16 + flail, 0, 64)
                .set(BODY, 7, 0, 0);
        pose.blend(rising, 1f).blend(falling, t);
        return pose;
    }

    /**
     * Airborne to grounded, and how hard. Measured from the fastest the body was falling rather
     * than the last tick's speed, which is usually already zero by the time the flag flips.
     */
    private void landingWatch() {
        boolean air = !motion.onGround && !motion.flying && !motion.inWater && !motion.climbing
                && !motion.riding && !motion.gliding;
        if (air) {
            airborne = true;
            fallSpeed = Math.min(fallSpeed, motion.vy);
            return;
        }
        if (airborne) {
            airborne = false;
            float hard = (float) clamp(-fallSpeed / 1.4, 0, 1);
            fallSpeed = 0;
            if (hard > 0.12f) {
                impact = hard;
                landing = 0;
                if (feel != null) {
                    feel.landed(rig.feet(), hard);
                }
            }
        }
    }

    private Pose hover() {
        float breath = (float) Math.sin(age * 0.08);
        return new Pose()
                .set(BODY, 5 + breath * 2, 0, 0)
                .set(ARM_RIGHT, 8 + breath * 3, 0, -15)
                .set(ARM_LEFT, 8 - breath * 3, 0, 15)
                .set(LEG_RIGHT, 9 + breath * 2, 0, -3)
                .set(LEG_LEFT, 16 - breath * 2, 0, 3)
                .lift(breath * 1.3f);
    }

    private Pose fly() {
        Pose pose = new Pose()
                .set(BODY, 2, 0, 0)
                .set(ARM_RIGHT, 34, 0, -26)
                .set(ARM_LEFT, 34, 0, 26)
                .set(LEG_RIGHT, 16, 0, -4)
                .set(LEG_LEFT, 23, 0, 4);
        float climb = (float) clamp(motion.vy * 40, -20, 20);
        pose.pitch = (float) clamp(motion.speed / 0.7 * 38, 10, 42) - climb;
        pose.roll = clamp(motion.yawRate * 1.6f, -32, 32);
        return pose;
    }

    private Pose swim() {
        float c = (float) (Math.sin(age * 0.22) + 1) / 2f;
        float kick = (float) Math.sin(age * 0.44) * 16;
        Pose pose = new Pose()
                .set(ARM_RIGHT, -150 + 55 * c, 0, -(8 + 62 * c))
                .set(ARM_LEFT, -150 + 55 * c, 0, 8 + 62 * c)
                .set(LEG_RIGHT, kick, 0, -3)
                .set(LEG_LEFT, -kick, 0, 3)
                .set(BODY, 0, 0, 0);
        pose.pitch = (float) clamp(72 + motion.pitch * 0.6, 20, 115);
        pose.lift = -9;
        return pose;
    }

    private Pose tread() {
        float s = (float) Math.sin(age * 0.12);
        return new Pose()
                .set(LEG_RIGHT, -14 * s, 0, -3)
                .set(LEG_LEFT, 14 * s, 0, 3)
                .set(ARM_RIGHT, 20 * s, 0, -36)
                .set(ARM_LEFT, -20 * s, 0, 36)
                .set(BODY, 4, 0, 0)
                .lift(-2 + s * 0.8f);
    }

    private Pose climb() {
        float s = (float) Math.sin(age * 0.33);
        return new Pose()
                .set(ARM_RIGHT, -148 + 24 * s, 0, -6)
                .set(ARM_LEFT, -148 - 24 * s, 0, 6)
                .set(LEG_RIGHT, -24 - 20 * s, 0, -2)
                .set(LEG_LEFT, -24 + 20 * s, 0, 2)
                .set(BODY, -6, 0, 0);
    }

    private Pose sit() {
        return new Pose()
                .set(LEG_RIGHT, -84, 0, -7)
                .set(LEG_LEFT, -84, 0, 7)
                .set(ARM_RIGHT, -16, 0, -7)
                .set(ARM_LEFT, -16, 0, 7)
                .set(BODY, -2, 0, 0);
    }

    // ---------------------------------------------------------------- the action layer

    private void actions(Pose pose) {
        if (emote != null) {
            boolean moved = motion.speed > 0.035 || (!motion.onGround && !motion.flying);
            if (moved && emote != Emote.RISE) {
                emote = null;
            }
        }
        if (emote != null) {
            emoteAge++;
            float in = Math.min(1f, emoteAge / 8f);
            float out = emote.held() ? 1f : Math.min(1f, (emote.ticks() - emoteAge) / 8f);
            float weight = Math.max(0f, Math.min(in, out));
            pose.blend(emotePose(emote, emoteAge), weight);
            if (!emote.held() && emoteAge >= emote.ticks()) {
                emote = null;
            }
            return;
        }

        if (landing >= 0) {
            Pose hit = landingPose(landing);
            float heavy = impact > 0.55f ? 1f : 0f;
            int length = heavy > 0 ? 26 : 9;
            float w = landing < length / 2 ? 1f : (length - landing) / (length / 2f);
            pose.blend(hit, Math.max(0, w));
            landing++;
            if (landing >= length) {
                landing = -1;
            }
        }

        if (speaking > 0) {
            speaking--;
            float w = Math.min(1f, speaking / 6f) * 0.75f;
            Pose talk = new Pose()
                    .set(ARM_RIGHT, -34 + (float) Math.sin(age * 0.17) * 13, 0, -10 + (float) Math.sin(age * 0.11) * 5)
                    .set(ARM_LEFT, -8 + (float) Math.sin(age * 0.13) * 5, 0, 6)
                    .set(BODY, pose.get(BODY)[0], pose.get(BODY)[1] + (float) Math.sin(age * 0.09) * 4, 0);
            pose.blend(talk, w, EnumSet.of(ARM_RIGHT, ARM_LEFT, BODY));
        }

        if (cast >= 0) {
            float t = cast / 22f;
            float w = (float) Math.sin(Math.PI * Math.min(1f, t));
            Pose up = new Pose()
                    .set(ARM_RIGHT, -150, 0, -28)
                    .set(ARM_LEFT, -150, 0, 28)
                    .set(BODY, -8, 0, 0)
                    .set(HEAD, -20, 0, 0);
            pose.blend(up, w, UPPER);
            cast++;
            if (cast > 22) {
                cast = -1;
            }
        }

        if (swing >= 0) {
            if (hitstop > 0) {
                hitstop--;
            } else {
                swing++;
            }
            float t = swing / (float) SWING_TICKS;
            pose.blend(swingPose(t, swingLeft), t < 0.75f ? 1f : (1f - t) / 0.25f, UPPER);
            if (swing >= SWING_TICKS) {
                swing = -1;
            }
        }
    }

    /**
     * A backhand, on whichever arm's turn it is: a quick wind-up out to the side with the torso
     * turning away, then the arm driven across and up with the torso turning into it.
     */
    private Pose swingPose(float t, boolean left) {
        float armX;
        float armZ;
        float bodyY;
        if (t < 0.3f) {
            float k = smooth(t / 0.3f);
            armX = -40 * k;
            armZ = -26 * k;
            bodyY = 18 * k;
        } else {
            float k = Math.min(1f, (t - 0.3f) / 0.3f);
            k = k * k;
            armX = -40 - 62 * k;
            armZ = -26 + 48 * k;
            bodyY = 18 - 42 * k;
        }
        float side = left ? -1 : 1;
        Pose pose = new Pose();
        Bone arm = left ? ARM_LEFT : ARM_RIGHT;
        Bone other = left ? ARM_RIGHT : ARM_LEFT;
        pose.set(arm, armX, 0, armZ * side);
        pose.set(other, 12, 0, 8 * side);
        pose.set(BODY, 6, bodyY * side, 0);
        pose.set(HEAD, 0, -bodyY * side * 0.6f, 0);
        return pose;
    }

    /** The landing: a crouch for a short drop, and a three-point landing for a long one. */
    private Pose landingPose(int tick) {
        if (impact > 0.55f) {
            return new Pose()
                    .set(LEG_RIGHT, -80, 0, -4)
                    .set(LEG_LEFT, 26, 0, 4)
                    .set(BODY, 36, 0, 0)
                    .set(HEAD, -26, 0, 0)
                    .set(ARM_RIGHT, -10, 0, -6)
                    .set(ARM_LEFT, 30, 0, 26)
                    .lift(-6.5f);
        }
        float k = impact;
        return new Pose()
                .set(LEG_RIGHT, -10 * k, 0, -4 * k)
                .set(LEG_LEFT, -10 * k, 0, 4 * k)
                .set(BODY, 16 * k, 0, 0)
                .set(ARM_RIGHT, -8 * k, 0, -18 * k)
                .set(ARM_LEFT, -8 * k, 0, 18 * k)
                .lift(-3.2f * k);
    }

    private Pose emotePose(Emote e, int t) {
        float s = (float) Math.sin(age * 0.12);
        return switch (e) {
            case BOW -> new Pose()
                    .set(BODY, 48, 0, 0)
                    .set(HEAD, 16, 0, 0)
                    .set(ARM_RIGHT, -52, 0, 42)
                    .set(ARM_LEFT, 28, 0, 6)
                    .set(LEG_RIGHT, 3, 0, 0)
                    .set(LEG_LEFT, -3, 0, 0);
            case WELCOME -> new Pose()
                    .set(BODY, -9, 0, 0)
                    .set(HEAD, -12, 0, 0)
                    .set(ARM_RIGHT, -34, 0, -78 + s * 4)
                    .set(ARM_LEFT, -34, 0, 78 - s * 4)
                    .set(LEG_RIGHT, 0, 0, -3)
                    .set(LEG_LEFT, 0, 0, 3)
                    .lift(1);
            case KNEEL -> kneel(0);
            case POINT -> point();
            case LAUGH -> {
                float shake = (float) Math.sin(age * 1.4);
                yield new Pose()
                        .set(HEAD, -28 + shake * 4, 0, 0)
                        .set(BODY, -10 + shake * 3, 0, 0)
                        .set(ARM_RIGHT, -30, 0, 32)
                        .set(ARM_LEFT, -30, 0, -32)
                        .lift(shake * 0.5f);
            }
            case JUDGE -> new Pose()
                    .set(ARM_RIGHT, -56, 0, 58)
                    .set(ARM_LEFT, -50, 0, -58)
                    .set(HEAD, 20, 0, 0)
                    .set(BODY, -4, 0, 0)
                    .set(LEG_RIGHT, 0, 0, -4)
                    .set(LEG_LEFT, 0, 0, 4);
            case RISE -> {
                float up = t < 30 ? smooth(t / 30f) : t < 60 ? 1f : 1f - smooth((t - 60) / 20f);
                yield new Pose()
                        .set(ARM_RIGHT, -20, 0, -86)
                        .set(ARM_LEFT, -20, 0, 86)
                        .set(HEAD, -24, 0, 0)
                        .set(BODY, -6, 0, 0)
                        .set(LEG_RIGHT, 10, 0, -2)
                        .set(LEG_LEFT, 14, 0, 2)
                        .lift(28 * up + s * 1.5f * up);
            }
            case WAVE -> new Pose()
                    .set(ARM_RIGHT, -165, 0, -10 + (float) Math.sin(age * 0.55) * 22)
                    .set(ARM_LEFT, 4, 0, 5)
                    .set(BODY, 0, 0, 3)
                    .set(HEAD, 0, -8, 0);
            case THRONE -> new Pose()
                    .set(LEG_RIGHT, -88, 0, -2)
                    .set(LEG_LEFT, -84, 0, -16)
                    .set(BODY, -12, 0, 0)
                    .set(ARM_RIGHT, -40, 0, -18)
                    .set(ARM_LEFT, -40, 0, 18)
                    .set(HEAD, 4, 0, 0)
                    .lift(-4);
        };
    }

    /** Down on one knee. {@code bow} is how far the head hangs, on top of the pose itself. */
    private Pose kneel(float bow) {
        return new Pose()
                .set(LEG_LEFT, -85, 0, 3)
                .set(LEG_RIGHT, 30, 0, -3)
                .set(BODY, 8, 0, 0)
                .set(HEAD, 12 + bow, 0, 0)
                .set(ARM_LEFT, -58, 0, 8)
                .set(ARM_RIGHT, 10, 0, -6)
                .lift(-6.5f);
    }

    /** The right arm straight out at whoever is nearest, or wherever the camera is pointed. */
    private Pose point() {
        float aim = 0;
        float drop = 0;
        Location feet = rig.feet();
        if (pointAt != null && feet != null && pointAt.getWorld() == feet.getWorld()) {
            double dx = pointAt.getX() - feet.getX();
            double dz = pointAt.getZ() - feet.getZ();
            float toward = (float) Math.toDegrees(Math.atan2(-dx, dz));
            aim = clamp(Motion.wrap(toward - bodyYaw), -70, 70);
            double flat = Math.sqrt(dx * dx + dz * dz);
            drop = (float) Math.toDegrees(Math.atan2(feet.getY() + rig.height() * 0.7 - pointAt.getY() - 1, flat));
        } else {
            aim = clamp(Motion.wrap(motion.yaw - bodyYaw), -70, 70);
            drop = motion.pitch;
        }
        return new Pose()
                .set(ARM_RIGHT, -90 + clamp(drop, -40, 50), -aim, 0)
                .set(ARM_LEFT, 6, 0, 6)
                .set(BODY, 0, -aim * 0.25f, 0)
                .set(HEAD, clamp(drop, -40, 50), -aim * 0.75f, 0);
    }

    // ---------------------------------------------------------------- additive

    private void additive(Pose pose) {
        // The head goes where the camera goes. Subtracting what the body and the root have already
        // done to it is what keeps it pointed at the world, not at the torso.
        float[] body = pose.get(BODY);
        float lookYaw = clamp(Motion.wrap(motion.yaw - bodyYaw), -70, 70);
        float lookPitch = clamp(motion.pitch, -60, 70);
        boolean headOwnedByEmote = emote == Emote.POINT || emote == Emote.LAUGH || emote == Emote.BOW;
        if (!headOwnedByEmote) {
            float[] head = pose.get(HEAD);
            head[0] += lookPitch - body[0] - pose.pitch;
            head[1] += -lookYaw - body[1];
        }

        if (nod > 0) {
            pose.add(HEAD, (float) Math.sin(Math.PI * nod / 7.0) * 9, 0, 0);
            nod--;
        }
        if (flinch > 0) {
            float w = flinch / 6f;
            pose.add(BODY, -10 * w, 0, flinchSide * 6 * w);
            pose.add(HEAD, -12 * w, 0, 0);
            pose.add(ARM_RIGHT, 0, 0, -18 * w);
            pose.add(ARM_LEFT, 0, 0, 18 * w);
            flinch--;
        }
    }

    // ---------------------------------------------------------------- dormant

    /**
     * Nobody is wearing it. It kneels, head bowed, and breathes — and when somebody walks up, the
     * head lifts and follows them until they leave. The one thing it does that nobody asked for,
     * and the thing that makes a statue at spawn read as something that is waiting.
     */
    private Pose dormant() {
        float breath = (float) Math.sin(age * 0.05) * 1.6f;
        Pose pose = kneel(24);
        pose.add(BODY, breath, 0, 0);
        pose.add(ARM_LEFT, breath * 0.5f, 0, 0);

        Location feet = rig.feet();
        if (watching != null && feet != null && watching.getWorld() == feet.getWorld()) {
            double dx = watching.getX() - feet.getX();
            double dz = watching.getZ() - feet.getZ();
            double dy = watching.getY() + 1.5 - (feet.getY() + rig.height() * 0.8);
            float toward = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float rel = clamp(Motion.wrap(toward - rig.yaw()), -65, 65);
            float look = (float) Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
            float[] head = pose.get(HEAD);
            head[0] = clamp(look, -40, 50) - pose.get(BODY)[0];
            head[1] = -rel;
            pose.add(BODY, -4, -rel * 0.15f, 0);
        }
        return pose;
    }

    // ---------------------------------------------------------------- scripted poses, shared

    /**
     * An emote's pose {@code t} ticks into it, for a cutscene to hand to {@link #script}. Emotes
     * otherwise only play while somebody drives the body; this is the same table, reachable without
     * one. {@code at} is what POINT aims at (null aims straight ahead).
     */
    Pose scriptedEmote(Emote e, int t, Location at) {
        this.pointAt = at;
        this.bodyYaw = rig.yaw();
        return emotePose(e, t);
    }

    /** The dormant kneel, for cutscenes that want to land on it. */
    Pose kneelingPose() {
        return kneel(24);
    }

    /** Standing tall, arms at the sides. */
    static Pose standing() {
        return new Pose()
                .set(ARM_RIGHT, 0, 0, -4)
                .set(ARM_LEFT, 0, 0, 4);
    }

    // ---------------------------------------------------------------- curves

    private static float smooth(float t) {
        float c = Math.max(0f, Math.min(1f, t));
        return c * c * (3 - 2 * c);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
