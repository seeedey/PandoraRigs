package com.surgesmp.pandorarigs;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pandora's body: six item displays wearing the {@code surge:pandora_*} bone models, posed with
 * the same forward kinematics as the Hollow King's.
 *
 * <p>The conventions are the King's, and {@code tools/rig.py} generates the models against them —
 * one skin pixel is one model unit is 1/16 of a block at scale 1, every bone's pivot sits at model
 * (8, 8, 8), and at identity the figure faces the way a yaw of 0 faces. See
 * {@link com.surgesmp.surgeelements.boss.KingRig} for why each of those is true. The skin is slim,
 * the same 3-pixel arms the King has, so the pivots below are his too.
 *
 * <h2>Where it differs from the King's rig, and why</h2>
 *
 * <p><b>Every bone is positioned by its transformation, not by teleporting it.</b> The King puts
 * each display <em>at</em> its joint every tick. Pandora's displays all sit at one anchor and carry
 * their joint as a translation. That one change is what makes the rest possible:
 *
 * <ul>
 *   <li><b>It can ride a player.</b> While somebody is Pandora, the six displays are passengers of
 *       that player. A passenger's position is the client's own business — it is glued to its
 *       vehicle on every frame, with no server round trip — so the body never trails the person
 *       wearing it, which a teleported body always does by its interpolation window. The anchor is
 *       then the passenger seat, which is the top of the player's hitbox, and the translation is
 *       measured down from there.</li>
 *   <li><b>Joint movement interpolates.</b> A translation is part of the transformation, and the
 *       transformation is what the client smooths. A teleported joint only glides if the teleport
 *       duration is set and the move is short.</li>
 * </ul>
 *
 * <p><b>Riding has one hole, and {@link #heal} is the whole of the answer to it.</b> Paper refuses
 * to teleport a player who has passengers to another world, and a death, a portal or the vanilla
 * {@code /tp} across dimensions all throw passengers off. So once a tick the rig checks it is
 * still on its rider in the rider's world, and if it is not, it builds itself again there.
 * Same-world teleports are fine — 1.21.11 carries passengers with them.
 */
public final class Rig {

    /** Skin pixels to blocks, before {@link #scale}. */
    static final float PIXEL = 1 / 16f;

    /**
     * How far away a bone is still drawn, as a multiple of 64 blocks. The King's number, for the
     * King's reason: at the default of 1 somebody with Entity Distance at 50% loses the body at 32
     * blocks, which reads as a god flickering out.
     */
    private static final float VIEW_RANGE = 4f;

    /**
     * How far below the shoulder a held item is carried, in skin pixels. The hand is about ten
     * down; the rest slides the grip into the fist rather than leaving it at the wrist — the same
     * correction the King's sword needed ({@code KingRig.GRIP_REACH}), for the same reason: an item
     * display draws its model centred on itself.
     */
    private static final float GRIP = 13f;

    /** Turns a held item to hang from the fist rather than stand up out of it. The King's BLADE_DOWN. */
    private static final Quaternionf HELD_FLIP = new Quaternionf().rotateX((float) Math.PI);

    /** The point the whole body leans and banks about: the hips, not the soles of the feet. */
    private static final Vector3f HIPS = new Vector3f(0, 12, 0);

    /** The bones with a glow layer: {@code surge:<models>_<bone>_glow} (surgepack v26, {@code tools/pandora_aura.py}). */
    private static final Set<Bone> GLOW_BONES = EnumSet.of(Bone.HEAD, Bone.BODY, Bone.ARM_RIGHT, Bone.ARM_LEFT);

    /**
     * Where the halo's middle sits, in skin pixels from the head joint in the head's own frame: up
     * over the crown and back behind it. {@code tools/pandora_aura.py} draws its preview with the
     * same numbers.
     */
    private static final Vector3f HALO_AT = new Vector3f(0, 11.5f, 2.5f);
    /** Tips the halo's back edge up, so it reads as a painted ring behind the head, not a hat. */
    private static final float HALO_TILT = (float) Math.toRadians(-25);
    /** Ticks for one turn of the halo; the sigil turns a third as fast the other way. */
    private static final long HALO_TURN = 120;
    /** The sigil's width over the body's scale: about twice her shoulders. */
    private static final float SIGIL_SIZE = 1.15f;
    /** The halo's outline, when the body is outlined. White, so she is not one flat lavender shape. */
    private static final Color HALO_OUTLINE = Color.fromRGB(0xF8F5FF);

    /**
     * One limb. {@code pivot} is the joint in skin pixels off the floor, {@code parent} what
     * carries it. The arms and head ride the torso; the torso and legs ride the root.
     */
    public enum Bone {
        BODY("body", 0, 12, 0, null),
        HEAD("head", 0, 24, 0, BODY),
        ARM_RIGHT("arm_right", -5.5f, 22, 0, BODY),
        ARM_LEFT("arm_left", 5.5f, 22, 0, BODY),
        LEG_RIGHT("leg_right", -2, 12, 0, null),
        LEG_LEFT("leg_left", 2, 12, 0, null);

        private final String model;
        private final Vector3f pivot;
        private final Bone parent;

        Bone(String model, float x, float y, float z, Bone parent) {
            this.model = model;
            this.pivot = new Vector3f(x, y, z);
            this.parent = parent;
        }

        /** This bone's model in a set named {@code prefix}: {@code <namespace>:<prefix>_<bone>}. */
        public NamespacedKey key(String namespace, String prefix) {
            return new NamespacedKey(namespace, prefix + "_" + model);
        }

        public Vector3f pivot() {
            return new Vector3f(pivot);
        }
    }

    private final Plugin plugin;
    /**
     * Whose skin the six bones wear: the {@code <prefix>} in {@code surge:<prefix>_head}. Pandora's
     * by default; the Eclipse wears the same skeleton with its own set, generated by the same
     * {@code tools/rig.py}.
     */
    private final String models;
    private final String namespace;
    private final Map<Bone, ItemDisplay> bones = new EnumMap<>(Bone.class);
    private final Map<Bone, Quaternionf> local = new EnumMap<>(Bone.class);
    /** Extra per-bone offsets in blocks, world axes — how the Rising flies each limb in. */
    private final Map<Bone, Vector3f> offsets = new EnumMap<>(Bone.class);
    private final Set<Bone> hidden = EnumSet.noneOf(Bone.class);

    /**
     * What it holds: 0 is the right hand, 1 the left. Not bones — each hangs off the end of an arm,
     * like the King's sword — but they ride, heal and vanish with the bones.
     */
    private final ItemDisplay[] hands = new ItemDisplay[2];
    private final ItemStack[] held = new ItemStack[2];

    /** Last computed joint positions (skin pixels, from the feet, root-rotated) and rotations. */
    private final Map<Bone, Vector3f> where = new EnumMap<>(Bone.class);
    private final Map<Bone, Quaternionf> world = new EnumMap<>(Bone.class);

    /** Who may not see which bones — the driver's own head, if that is switched on. */
    private final Map<UUID, Set<Bone>> hiddenFrom = new java.util.HashMap<>();

    /** Where the feet are when the rig stands on its own. Ignored while it rides somebody. */
    private Location feet;
    private LivingEntity rider;

    private float yaw;
    private float pitch;
    private float roll;
    /** Pixels up. A crouch is negative, the Rise emote is very positive. */
    private float lift;
    private float scale = 1.5f;
    private int interpolation = 2;
    private boolean glowing;

    /**
     * The aura's displays: a lit copy of each {@link #GLOW_BONES} bone wearing only its eyes and
     * runes, the halo over the head and the sigil under the feet. All off until {@link #aura} turns
     * them on. A display's brightness is per entity, which is why the lit parts are entities of
     * their own and not part of the bones.
     */
    private final Map<Bone, ItemDisplay> glows = new EnumMap<>(Bone.class);
    private ItemDisplay halo;
    private ItemDisplay sigil;
    private boolean glowLayerOn;
    private boolean haloOn;
    private boolean sigilOn;
    /** 0..1: how far the halo has grown in. */
    private float haloScale = 1f;
    /** 0..1 of the sigil's size; {@link #breath} (-1..1) swells it six percent either way. */
    private float sigilScale = 1f;
    private float breath;

    /** Every display a rig spawns carries this, so a leftover can be found and cleared after a crash. */
    public static NamespacedKey ownedKey(Plugin plugin) {
        return new NamespacedKey(plugin, "rig_part");
    }

    /**
     * @param namespace the resource-pack namespace the models live in ({@code rig} for packs built by
     *                  {@code tools/rig.py})
     * @param models    the set's prefix: bone {@code <namespace>:<models>_head} and so on
     */
    public Rig(Plugin plugin, String namespace, String models) {
        this.plugin = plugin;
        this.namespace = namespace;
        this.models = models;
        for (Bone bone : Bone.values()) {
            local.put(bone, new Quaternionf());
            offsets.put(bone, new Vector3f());
        }
    }

    // ---------------------------------------------------------------- building it

    /** Puts the body in the world, standing on its own at {@code feet}. */
    public void spawn(Location feet, float scale) {
        remove();
        this.feet = flat(feet);
        this.scale = scale;
        this.rider = null;
        for (Bone bone : Bone.values()) {
            bones.put(bone, spawnBone(this.feet, bone));
        }
        for (int i = 0; i < 2; i++) {
            hands[i] = spawnHand(this.feet, i);
        }
        spawnAura(this.feet);
        apply();
    }

    private void spawnAura(Location at) {
        if (glowLayerOn) {
            for (Bone bone : GLOW_BONES) {
                glows.put(bone, spawnLit(at, hidden.contains(bone) ? null : glowKey(bone), false));
            }
        }
        if (haloOn) {
            halo = spawnLit(at, new NamespacedKey(namespace, models + "_halo"), glowing);
            halo.setGlowColorOverride(HALO_OUTLINE);
        }
        if (sigilOn) {
            sigil = spawnLit(at, new NamespacedKey(namespace, "sigil_" + models), false);
        }
    }

    private NamespacedKey glowKey(Bone bone) {
        return new NamespacedKey(namespace, models + "_" + bone.model + "_glow");
    }

    /** An aura display: like a bone, but lit at full brightness whatever the world light is. */
    private ItemDisplay spawnLit(Location at, NamespacedKey model, boolean outlined) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(model == null ? new ItemStack(Material.AIR) : modelled(model));
            d.setBillboard(Display.Billboard.FIXED);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(2);
            d.setInterpolationDuration(interpolation);
            d.setViewRange(VIEW_RANGE);
            d.setPersistent(false);
            d.setGlowing(outlined);
            d.getPersistentDataContainer().set(ownedKey(plugin), PersistentDataType.STRING, models);
        });
    }

    private void removeAura() {
        List<ItemDisplay> gone = new ArrayList<>(glows.values());
        gone.add(halo);
        gone.add(sigil);
        for (ItemDisplay display : gone) {
            if (display != null && display.isValid()) {
                if (rider != null) {
                    rider.removePassenger(display);
                }
                display.remove();
            }
        }
        glows.clear();
        halo = null;
        sigil = null;
    }

    /**
     * Which aura pieces the body wears. Off by default, and only Pandora's set has the models. Safe
     * to call before or after {@link #spawn}; a change rebuilds just the aura.
     */
    public void aura(boolean halo, boolean glowLayer, boolean sigil) {
        if (halo == haloOn && glowLayer == glowLayerOn && sigil == sigilOn) {
            return;
        }
        this.haloOn = halo;
        this.glowLayerOn = glowLayer;
        this.sigilOn = sigil;
        if (!valid()) {
            return;
        }
        removeAura();
        spawnAura(bones.get(Bone.BODY).getLocation());
        if (rider != null) {
            mount(rider);
        } else {
            apply();
        }
    }

    /** How far the halo has grown in, 0..1. Takes effect on the next {@link #apply}. */
    public void haloScale(float f) {
        this.haloScale = Math.max(0f, f);
    }

    /** The sigil's size, 0..1 of full, before the breath. */
    public void sigilScale(float f) {
        this.sigilScale = Math.max(0f, f);
    }

    /** The breath phase, -1..1, which the sigil swells with. */
    public void breath(float f) {
        this.breath = f;
    }

    /** Whether a bone is drawn and back at its joint: where the aura may draw from. */
    public boolean settled(Bone bone) {
        return !hidden.contains(bone) && offsets.get(bone).lengthSquared() < 0.01f;
    }

    private ItemDisplay spawnHand(Location at, int i) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(held[i] == null ? new ItemStack(Material.AIR) : held[i].clone());
            d.setBillboard(Display.Billboard.FIXED);
            // The vanilla in-hand transform, so a sword, a torch or a block each sit in the fist the
            // way they do in a player's.
            d.setItemDisplayTransform(i == 0 ? ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND
                    : ItemDisplay.ItemDisplayTransform.THIRDPERSON_LEFTHAND);
            d.setTeleportDuration(2);
            d.setInterpolationDuration(interpolation);
            d.setViewRange(VIEW_RANGE);
            d.setPersistent(false);
            d.getPersistentDataContainer().set(ownedKey(plugin), PersistentDataType.STRING, models);
        });
    }

    /** Every display the body is made of: six bones, two hands and whatever aura it wears. */
    private List<ItemDisplay> parts() {
        List<ItemDisplay> out = new ArrayList<>(bones.values());
        for (ItemDisplay hand : hands) {
            if (hand != null) {
                out.add(hand);
            }
        }
        out.addAll(glows.values());
        if (halo != null) {
            out.add(halo);
        }
        if (sigil != null) {
            out.add(sigil);
        }
        return out;
    }

    /** How many displays {@link #parts} holds when nothing is missing. */
    private int expectedParts() {
        return Bone.values().length + hands.length + (glowLayerOn ? GLOW_BONES.size() : 0)
                + (haloOn ? 1 : 0) + (sigilOn ? 1 : 0);
    }

    /**
     * Puts items in its hands — whatever the person wearing it is holding. Null or air is an empty
     * hand. Only an actual change touches the display, so this is safe to call every tick.
     */
    public void hold(ItemStack main, ItemStack off) {
        held(0, main);
        held(1, off);
    }

    private void held(int i, ItemStack stack) {
        ItemStack want = stack == null || stack.getType().isAir() ? null : stack;
        ItemStack have = held[i];
        if (want == null ? have == null : have != null && have.isSimilar(want)) {
            return;
        }
        held[i] = want == null ? null : want.clone();
        if (hands[i] != null && hands[i].isValid()) {
            hands[i].setItemStack(want == null ? new ItemStack(Material.AIR) : want.clone());
        }
    }

    private ItemDisplay spawnBone(Location at, Bone bone) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(hidden.contains(bone) ? new ItemStack(Material.AIR) : modelled(bone.key(namespace, models)));
            // FIXED, because any billboard would undo every rotation in this file.
            d.setBillboard(Display.Billboard.FIXED);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTeleportDuration(2);
            d.setInterpolationDuration(interpolation);
            d.setViewRange(VIEW_RANGE);
            d.setPersistent(false);
            d.setGlowing(glowing);
            d.getPersistentDataContainer().set(ownedKey(plugin), PersistentDataType.STRING, models);
        });
    }

    private static ItemStack modelled(NamespacedKey key) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(key);
        stack.setItemMeta(meta);
        return stack;
    }

    /** A copy with the yaw and pitch zeroed. A display's own rotation stacks on its transformation. */
    private static Location flat(Location at) {
        Location out = at.clone();
        out.setYaw(0);
        out.setPitch(0);
        return out;
    }

    public boolean valid() {
        ItemDisplay body = bones.get(Bone.BODY);
        return body != null && body.isValid();
    }

    /** How many of the six are still in the world. For {@code /pandora status}. */
    public int liveParts() {
        int live = 0;
        for (ItemDisplay display : bones.values()) {
            if (display != null && display.isValid()) {
                live++;
            }
        }
        return live;
    }

    // ---------------------------------------------------------------- riding

    /** Puts the whole body on {@code player}'s back, which from then on is where it is. */
    public void mount(LivingEntity player) {
        if (!valid() || !sameWorld(player)) {
            spawn(player.getLocation(), scale);
        }
        this.rider = player;
        for (ItemDisplay display : parts()) {
            display.setTeleportDuration(0);
            display.teleport(flat(player.getLocation()));
            player.addPassenger(display);
        }
        reapplyHiding();
        apply();
    }

    /** Takes it off whoever is wearing it and leaves it standing where they stood. */
    public void unmount() {
        LivingEntity was = rider;
        this.rider = null;
        if (was == null) {
            return;
        }
        Location at = flat(was.getLocation());
        for (ItemDisplay display : parts()) {
            if (display.isValid()) {
                was.removePassenger(display);
                display.leaveVehicle();
                display.setTeleportDuration(2);
                display.teleport(at);
            }
        }
        for (UUID viewer : new HashSet<>(hiddenFrom.keySet())) {
            Player p = plugin.getServer().getPlayer(viewer);
            if (p != null) {
                for (ItemDisplay display : parts()) {
                    p.showEntity(plugin, display);
                }
            }
        }
        hiddenFrom.clear();
        this.feet = at;
        apply();
    }

    public LivingEntity rider() {
        return rider;
    }

    public boolean mounted() {
        return rider != null;
    }

    private boolean sameWorld(LivingEntity player) {
        ItemDisplay body = bones.get(Bone.BODY);
        return body != null && body.isValid() && body.getWorld().equals(player.getWorld());
    }

    /**
     * Rebuilds the body on its rider if anything knocked it off.
     *
     * <p>A death ejects passengers. So does changing dimension, whether by portal or by the vanilla
     * {@code /tp}. And an unloaded chunk can take a display with it. None of those fire anything
     * the rig could listen for in one place, so it simply checks, once a tick, that all six are
     * still valid, still in the rider's world, and still riding them — and if not, starts again.
     *
     * @return true if it had to rebuild
     */
    public boolean heal() {
        if (rider == null) {
            if (feet != null && !valid()) {
                spawn(feet, scale);
                return true;
            }
            return false;
        }
        if (!rider.isValid() || rider.isDead()) {
            return false;
        }
        boolean broken = false;
        for (ItemDisplay display : parts()) {
            if (display == null || !display.isValid() || display.getVehicle() != rider) {
                broken = true;
                break;
            }
        }
        if (!broken && parts().size() == expectedParts()) {
            return false;
        }
        LivingEntity keep = rider;
        // spawn() starts from nothing, hiding included, so who could not see what is carried over.
        Map<UUID, Set<Bone>> hides = new java.util.HashMap<>(hiddenFrom);
        rider = null;
        spawn(keep.getLocation(), scale);
        hiddenFrom.putAll(hides);
        mount(keep);
        return true;
    }

    // ---------------------------------------------------------------- who sees what

    /** Hides some bones from one viewer. Used to take the head off the driver's own screen. */
    public void hideFrom(Player viewer, Set<Bone> which) {
        hiddenFrom.put(viewer.getUniqueId(), EnumSet.copyOf(which));
        reapplyHiding();
    }

    private void reapplyHiding() {
        for (Map.Entry<UUID, Set<Bone>> entry : hiddenFrom.entrySet()) {
            Player viewer = plugin.getServer().getPlayer(entry.getKey());
            if (viewer == null) {
                continue;
            }
            for (Bone bone : entry.getValue()) {
                List<ItemDisplay> with = new ArrayList<>();
                with.add(bones.get(bone));
                with.add(glows.get(bone));
                if (bone == Bone.HEAD) {
                    with.add(halo);
                }
                for (ItemDisplay display : with) {
                    if (display != null && display.isValid()) {
                        viewer.hideEntity(plugin, display);
                    }
                }
            }
        }
    }

    /** Takes a bone out of sight (or puts it back) for everybody. The Rising assembles it. */
    public void show(Bone bone, boolean visible) {
        if (visible) {
            hidden.remove(bone);
        } else {
            hidden.add(bone);
        }
        ItemDisplay display = bones.get(bone);
        if (display != null && display.isValid()) {
            display.setItemStack(visible ? modelled(bone.key(namespace, models)) : new ItemStack(Material.AIR));
        }
        ItemDisplay lit = glows.get(bone);
        if (lit != null && lit.isValid()) {
            lit.setItemStack(visible ? modelled(glowKey(bone)) : new ItemStack(Material.AIR));
        }
    }

    public void showAll() {
        for (Bone bone : Bone.values()) {
            show(bone, true);
        }
    }

    /** Outlines the bones, the hands and the halo. The glow layer and the sigil are never outlined. */
    public void glow(boolean on) {
        this.glowing = on;
        List<ItemDisplay> outlined = new ArrayList<>(bones.values());
        outlined.add(hands[0]);
        outlined.add(hands[1]);
        outlined.add(halo);
        for (ItemDisplay display : outlined) {
            if (display != null && display.isValid()) {
                display.setGlowing(on);
            }
        }
    }

    /** The displays themselves, for team colouring a glow. */
    public Iterable<ItemDisplay> displays() {
        return bones.values();
    }

    // ---------------------------------------------------------------- posing

    /** Where it stands and which way it faces, when nobody is wearing it. */
    public void place(Location feet) {
        this.feet = flat(feet);
    }

    /** Which way the whole body is turned, and how far it leans and banks. Degrees. */
    public void orient(float yaw, float pitch, float roll) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
    }

    public float yaw() {
        return yaw;
    }

    public void lift(float pixels) {
        this.lift = pixels;
    }

    public float lift() {
        return lift;
    }

    /** One bone's rotation in its parent's frame, snapped. Degrees, applied Y then X then Z. */
    public void pose(Bone bone, float x, float y, float z) {
        local.get(bone).set(euler(x, y, z));
    }

    /**
     * Eases one bone toward a rotation. {@code rate} is how much of the remaining distance to
     * close this tick — 1 snaps, 0.2 arrives in about half a second.
     */
    public void ease(Bone bone, float x, float y, float z, float rate) {
        local.get(bone).slerp(euler(x, y, z), Math.max(0f, Math.min(1f, rate)));
    }

    private static Quaternionf euler(float x, float y, float z) {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(y))
                .rotateX((float) Math.toRadians(x))
                .rotateZ((float) Math.toRadians(z));
    }

    /** An extra offset for one bone, in blocks, world axes. Zero is where it belongs. */
    public void offset(Bone bone, Vector3f blocks) {
        offsets.get(bone).set(blocks);
    }

    public void clearOffsets() {
        for (Vector3f v : offsets.values()) {
            v.zero();
        }
    }

    public float scale() {
        return scale;
    }

    public void scale(float scale) {
        this.scale = scale;
    }

    /** Ticks the client spends getting to each new pose. 1-3; more is floaty, 0 is a slideshow. */
    public void interpolation(int ticks) {
        this.interpolation = Math.max(0, ticks);
    }

    /**
     * Walks the skeleton and writes all six displays. Called once a tick, after the pose is set.
     *
     * <p>Forward kinematics and nothing else. The root turns about the hips; a bone's rotation is
     * its parent's times its own; its position is its parent's plus its offset from the parent's
     * joint, carried by the parent's rotation. Then each position is moved from "pixels above the
     * feet" to "blocks from wherever this display actually is", which is the feet when it stands on
     * its own and the top of the rider's hitbox when it is being worn.
     */
    public void apply() {
        if (!valid()) {
            return;
        }
        Quaternionf root = new Quaternionf()
                .rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch))
                .rotateZ((float) Math.toRadians(roll));

        where.clear();
        world.clear();
        for (Bone bone : Bone.values()) {
            Vector3f position;
            Quaternionf parentRot;
            if (bone.parent == null) {
                parentRot = new Quaternionf(root);
                position = root.transform(new Vector3f(bone.pivot).sub(HIPS)).add(HIPS);
            } else {
                parentRot = new Quaternionf(world.get(bone.parent));
                Vector3f offset = new Vector3f(bone.pivot).sub(bone.parent.pivot);
                position = new Vector3f(where.get(bone.parent)).add(parentRot.transform(offset));
            }
            Quaternionf rotation = new Quaternionf(parentRot).mul(local.get(bone));
            world.put(bone, rotation);
            where.put(bone, position);
        }

        float seat = rider != null ? (float) rider.getHeight() : 0f;
        if (rider == null && feet != null) {
            for (ItemDisplay display : parts()) {
                Location at = display.getLocation();
                if (at.getWorld() != feet.getWorld() || at.distanceSquared(feet) > 1.0e-6) {
                    display.teleport(feet);
                }
            }
        }
        for (Bone bone : Bone.values()) {
            Vector3f p = where.get(bone);
            Vector3f translation = new Vector3f(
                    p.x * PIXEL * scale,
                    (p.y + lift) * PIXEL * scale - seat,
                    p.z * PIXEL * scale).add(offsets.get(bone));
            write(bones.get(bone), translation, world.get(bone));
            write(glows.get(bone), translation, world.get(bone));
        }
        // What it holds, off the end of each arm, turned with the arm so it swings with it.
        for (int i = 0; i < 2; i++) {
            Bone arm = i == 0 ? Bone.ARM_RIGHT : Bone.ARM_LEFT;
            Quaternionf rotation = world.get(arm);
            Vector3f grip = new Vector3f(where.get(arm)).add(rotation.transform(new Vector3f(0, -GRIP, 0)));
            Vector3f translation = new Vector3f(
                    grip.x * PIXEL * scale,
                    (grip.y + lift) * PIXEL * scale - seat,
                    grip.z * PIXEL * scale).add(offsets.get(arm));
            write(hands[i], translation, new Quaternionf(rotation).mul(HELD_FLIP));
        }
        applyAura(seat);
    }

    /**
     * The halo rides the head: its joint, then {@link #HALO_AT} in the head's frame, tilted, then
     * spun. The sigil lies flat at the feet and turns slowly the other way. Both turn by the world's
     * clock, so an extra {@link #apply} in one tick does not speed them up.
     */
    private void applyAura(float seat) {
        if (halo == null && sigil == null) {
            return;
        }
        Location base = feet();
        long now = base == null ? 0 : base.getWorld().getGameTime();
        float turn = (float) (2 * Math.PI * (now % HALO_TURN) / HALO_TURN);
        if (halo != null) {
            Quaternionf head = world.get(Bone.HEAD);
            float bob = (float) Math.sin(now * 0.08) * 0.5f;
            Vector3f at = new Vector3f(where.get(Bone.HEAD))
                    .add(head.transform(new Vector3f(HALO_AT.x, HALO_AT.y + bob, HALO_AT.z)));
            Vector3f translation = new Vector3f(
                    at.x * PIXEL * scale,
                    (at.y + lift) * PIXEL * scale - seat,
                    at.z * PIXEL * scale).add(offsets.get(Bone.HEAD));
            Quaternionf rotation = new Quaternionf(head).rotateX(HALO_TILT).rotateY(turn);
            write(halo, translation, rotation, hidden.contains(Bone.HEAD) ? 0f : scale * haloScale);
        }
        if (sigil != null) {
            // Just off the floor so it does not flicker with it; gone while she is well up in the air.
            boolean grounded = lift * PIXEL * scale <= 1f && !hidden.contains(Bone.BODY);
            float size = grounded ? SIGIL_SIZE * scale * sigilScale * (1 + 0.06f * breath) : 0f;
            write(sigil, new Vector3f(0, 0.04f - seat, 0), new Quaternionf().rotateY(-turn / 3), size);
        }
    }

    private void write(ItemDisplay display, Vector3f translation, Quaternionf rotation) {
        write(display, translation, rotation, scale);
    }

    private void write(ItemDisplay display, Vector3f translation, Quaternionf rotation, float size) {
        if (display == null || !display.isValid()) {
            return;
        }
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(interpolation);
        display.setTransformation(new Transformation(
                translation,
                new Quaternionf(rotation),
                new Vector3f(size, size, size),
                new Quaternionf()));
    }

    // ---------------------------------------------------------------- where things are

    /** Where the feet are in the world right now. */
    public Location feet() {
        if (rider != null) {
            return rider.getLocation();
        }
        return feet == null ? null : feet.clone();
    }

    public World world() {
        Location at = feet();
        return at == null ? null : at.getWorld();
    }

    /**
     * A point on the body in world space: a bone's joint plus {@code along} pixels in that bone's
     * own frame. {@code point(ARM_RIGHT, 0, -10, 0)} is the right hand.
     */
    public Location point(Bone bone, float x, float y, float z) {
        Location base = feet();
        Vector3f joint = where.get(bone);
        Quaternionf rotation = world.get(bone);
        if (base == null || joint == null || rotation == null) {
            return base;
        }
        Vector3f p = new Vector3f(joint).add(rotation.transform(new Vector3f(x, y, z)));
        return base.clone().add(p.x * PIXEL * scale, (p.y + lift) * PIXEL * scale, p.z * PIXEL * scale);
    }

    public Location rightHand() {
        return point(Bone.ARM_RIGHT, 0, -10, 0);
    }

    public Location leftHand() {
        return point(Bone.ARM_LEFT, 0, -10, 0);
    }

    /** The middle of the chest: what powers are cast from and what the eye is drawn to. */
    public Location chest() {
        return point(Bone.BODY, 0, 6, 0);
    }

    /** Just over the crown, where a speech bubble hangs. */
    public Location crown() {
        return point(Bone.HEAD, 0, 12, 0);
    }

    /** How tall it stands, in blocks. */
    public double height() {
        return 32 * PIXEL * scale;
    }

    // ---------------------------------------------------------------- tearing down

    public void remove() {
        LivingEntity was = rider;
        rider = null;
        for (ItemDisplay display : parts()) {
            if (display != null && display.isValid()) {
                if (was != null) {
                    was.removePassenger(display);
                }
                display.remove();
            }
        }
        bones.clear();
        glows.clear();
        halo = null;
        sigil = null;
        hands[0] = null;
        hands[1] = null;
        hiddenFrom.clear();
    }

    /** Whether this entity is one of the six. The listener asks, so nothing punches a bone. */
    public boolean owns(Entity entity) {
        for (ItemDisplay display : parts()) {
            if (display.equals(entity)) {
                return true;
            }
        }
        return false;
    }
}
