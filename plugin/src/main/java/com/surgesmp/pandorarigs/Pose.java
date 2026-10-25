package com.surgesmp.pandorarigs;

import com.surgesmp.pandorarigs.Rig.Bone;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * One whole-body pose as numbers: three angles per bone, plus how the root is lifted, leant and
 * banked.
 *
 * <p>The animator builds one of these per layer and blends them, then eases the rig toward the
 * result. Blending happens here, in Euler angles, rather than in quaternions, because the layers
 * are only ever a few tens of degrees apart and a linear blend of angles is something a person can
 * read and tune; the slerp in {@link Rig#ease} is what keeps the final approach smooth.
 */
final class Pose {

    private final Map<Bone, float[]> angles = new EnumMap<>(Bone.class);

    /** Pixels up. */
    float lift;
    /** Whole-body lean forward, degrees about the hips. */
    float pitch;
    /** Whole-body bank, degrees. Positive tips toward the right hand, which is a right turn. */
    float roll;

    Pose() {
        for (Bone bone : Bone.values()) {
            angles.put(bone, new float[3]);
        }
    }

    Pose copy() {
        Pose out = new Pose();
        for (Bone bone : Bone.values()) {
            float[] a = angles.get(bone);
            out.angles.put(bone, new float[]{a[0], a[1], a[2]});
        }
        out.lift = lift;
        out.pitch = pitch;
        out.roll = roll;
        return out;
    }

    Pose set(Bone bone, float x, float y, float z) {
        float[] a = angles.get(bone);
        a[0] = x;
        a[1] = y;
        a[2] = z;
        return this;
    }

    Pose add(Bone bone, float x, float y, float z) {
        float[] a = angles.get(bone);
        a[0] += x;
        a[1] += y;
        a[2] += z;
        return this;
    }

    Pose lift(float pixels) {
        this.lift = pixels;
        return this;
    }

    float[] get(Bone bone) {
        return angles.get(bone);
    }

    /** Moves this pose {@code weight} of the way toward {@code other}, every bone and the root. */
    Pose blend(Pose other, float weight) {
        return blend(other, weight, null);
    }

    /**
     * The same, limited to {@code mask}. A null mask means everything, including the root. A mask
     * blends only its bones and leaves the root alone — an arm gesture has no business tipping the
     * whole body over.
     */
    Pose blend(Pose other, float weight, Set<Bone> mask) {
        float w = Math.max(0f, Math.min(1f, weight));
        if (w <= 0f) {
            return this;
        }
        for (Bone bone : Bone.values()) {
            if (mask != null && !mask.contains(bone)) {
                continue;
            }
            float[] a = angles.get(bone);
            float[] b = other.angles.get(bone);
            for (int i = 0; i < 3; i++) {
                a[i] += (b[i] - a[i]) * w;
            }
        }
        if (mask == null) {
            lift += (other.lift - lift) * w;
            pitch += (other.pitch - pitch) * w;
            roll += (other.roll - roll) * w;
        }
        return this;
    }
}
