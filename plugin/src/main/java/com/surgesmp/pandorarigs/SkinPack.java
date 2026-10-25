package com.surgesmp.pandorarigs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Turns skins into the resource pack the rigs wear: six bone models per skin, cut out of it with the
 * vanilla box unwrap.
 *
 * <p>This is Surge's {@code tools/rig.py} moved into the plugin, so a rig can be made in game rather
 * than with Python and a pack release. The conventions are the ones {@link Rig} is written against —
 * one skin pixel is one model unit, every bone's pivot sits at model (8, 8, 8), the figure faces
 * north in model space — and the arms are Pandora's slim, three-pixel ones, lifted two pixels so the
 * shoulder turns where a real one does. A classic skin is narrowed to slim on the way in.
 */
final class SkinPack {

    static final String NAMESPACE = "pandorarigs";

    /** Skin pixels to model-JSON uv units, for a 64x64 skin. */
    private static final double UV = 16.0 / 64;
    private static final double OVERLAY_INFLATE = 0.25;

    /** Size, base layer offset, overlay offset, which way it hangs off the pivot, and the shoulder lift. */
    private record Box(String name, int w, int h, int d, int bu, int bv, int ou, int ov, boolean up, int lift) {
    }

    private static final Box[] BONES = {
            new Box("head", 8, 8, 8, 0, 0, 32, 0, true, 0),
            new Box("body", 8, 12, 4, 16, 16, 16, 32, true, 0),
            new Box("arm_right", 3, 12, 4, 40, 16, 40, 32, false, 2),
            new Box("arm_left", 3, 12, 4, 32, 48, 48, 48, false, 2),
            new Box("leg_right", 4, 12, 4, 0, 16, 0, 32, false, 0),
            new Box("leg_left", 4, 12, 4, 16, 48, 0, 48, false, 0),
    };

    private SkinPack() {
    }

    // ---------------------------------------------------------------- skins in

    /**
     * Reads a skin and returns it as a 64x64 slim skin. {@code slim} is what the skin's owner says it
     * is (null when nobody says); without that the empty arm columns decide.
     */
    static BufferedImage normalise(BufferedImage raw, Boolean slim) throws IOException {
        if (raw.getWidth() != 64 || (raw.getHeight() != 64 && raw.getHeight() != 32)) {
            throw new IOException("a skin is 64x64 (or old 64x32); this one is " + raw.getWidth() + "x" + raw.getHeight());
        }
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        skin.getGraphics().drawImage(raw, 0, 0, null);
        if (raw.getHeight() == 32) {
            // The old format has one arm and one leg; both sides wore them.
            copy(skin, 0, 16, 16, 16, 16, 48);
            copy(skin, 40, 16, 16, 16, 32, 48);
        }
        boolean isSlim = slim != null ? slim : emptyColumns(skin);
        return isSlim ? skin : toSlim(skin);
    }

    /** A slim skin leaves the last two columns of the right arm's texture empty; a classic one fills them. */
    private static boolean emptyColumns(BufferedImage skin) {
        for (int x = 54; x <= 55; x++) {
            for (int y = 20; y < 32; y++) {
                if ((skin.getRGB(x, y) >>> 24) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void copy(BufferedImage img, int sx, int sy, int w, int h, int dx, int dy) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(dx + x, dy + y, img.getRGB(sx + x, sy + y));
            }
        }
    }

    /**
     * Classic (4-pixel) arms to slim (3-pixel), both layers. Each arm loses the column nearest the body
     * from its front, back, top and bottom; the outside of the arm, where the detail is, stays whole.
     */
    static BufferedImage toSlim(BufferedImage src) {
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(src, 0, 0, null);
        arm(src, out, 40, 16, true);
        arm(src, out, 40, 32, true);
        arm(src, out, 32, 48, false);
        arm(src, out, 48, 48, false);
        return out;
    }

    private static void arm(BufferedImage src, BufferedImage out, int u, int v, boolean right) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                out.setRGB(u + x, v + y, 0);
            }
        }
        int innerFront = right ? 3 : 0;
        int innerBack = right ? 0 : 3;
        narrow(src, out, u + 4, v, 4, 4, innerFront, u + 4, v);          // top
        narrow(src, out, u + 8, v, 4, 4, innerFront, u + 7, v);          // bottom
        narrow(src, out, u, v + 4, 4, 12, -1, u, v + 4);                 // outer side
        narrow(src, out, u + 4, v + 4, 4, 12, innerFront, u + 4, v + 4); // front
        narrow(src, out, u + 8, v + 4, 4, 12, -1, u + 7, v + 4);         // inner side
        narrow(src, out, u + 12, v + 4, 4, 12, innerBack, u + 11, v + 4); // back
    }

    /** Copies a w×h strip from (sx, sy) to (dx, dy) leaving out column {@code drop} (-1 keeps all). */
    private static void narrow(BufferedImage src, BufferedImage out, int sx, int sy, int w, int h, int drop,
                               int dx, int dy) {
        int col = 0;
        for (int x = 0; x < w; x++) {
            if (x == drop) {
                continue;
            }
            for (int y = 0; y < h; y++) {
                out.setRGB(dx + col, dy + y, src.getRGB(sx + x, sy + y));
            }
            col++;
        }
    }

    // ---------------------------------------------------------------- the pack out

    /**
     * Writes the whole pack — every skin in {@code skins} named in {@code ids} — to {@code zip}.
     * Entries carry a fixed timestamp, so the same skins always give the same sha1 and nobody
     * downloads a pack that did not change.
     *
     * @return the zip's sha1
     */
    static byte[] build(File skins, Collection<String> ids, File zip) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            put(out, "pack.mcmeta", """
                    {
                      "pack": {
                        "description": "PandoraRigs — rig bone models",
                        "pack_format": 75,
                        "supported_formats": {"min_inclusive": 46, "max_inclusive": 99},
                        "min_format": 46,
                        "max_format": 99
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            for (String id : ids) {
                File png = new File(skins, id + ".png");
                if (!png.isFile()) {
                    continue;
                }
                // Already normalised when it was saved, by /rig create or by the bundled copy.
                put(out, "assets/" + NAMESPACE + "/textures/item/" + id + ".png", Files.readAllBytes(png.toPath()));
                for (Box bone : BONES) {
                    String name = id + "_" + bone.name;
                    put(out, "assets/" + NAMESPACE + "/models/item/" + name + ".json",
                            model(id, bone).getBytes(StandardCharsets.UTF_8));
                    put(out, "assets/" + NAMESPACE + "/items/" + name + ".json",
                            ("{\"model\": {\"type\": \"minecraft:model\", \"model\": \"" + NAMESPACE + ":item/" + name
                                    + "\"}}").getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        byte[] data = bytes.toByteArray();
        try (OutputStream file = Files.newOutputStream(zip.toPath())) {
            file.write(data);
        }
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException(ex);
        }
    }

    private static void put(ZipOutputStream out, String path, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(315532800000L);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    /** One bone: the base box and the overlay box just outside it, both textured out of the skin. */
    private static String model(String id, Box bone) {
        String tex = NAMESPACE + ":item/" + id;
        return "{\"textures\": {\"skin\": \"" + tex + "\", \"particle\": \"" + tex + "\"}, \"elements\": ["
                + element(bone, bone.bu, bone.bv, 0) + ", "
                + element(bone, bone.ou, bone.ov, OVERLAY_INFLATE) + "]}";
    }

    private static String element(Box b, int u, int v, double inflate) {
        double x0 = 8 - b.w / 2.0 - inflate, x1 = 8 + b.w / 2.0 + inflate;
        double z0 = 8 - b.d / 2.0 - inflate, z1 = 8 + b.d / 2.0 + inflate;
        double y0, y1;
        if (b.up) {
            y0 = 8 - inflate + b.lift;
            y1 = 8 + b.h + inflate + b.lift;
        } else {
            y0 = 8 - b.h - inflate + b.lift;
            y1 = 8 + inflate + b.lift;
        }
        int w = b.w, h = b.h, d = b.d;
        // The standard box unwrap from (u, v): top and bottom on the first d rows, then right,
        // front, left, back along the next h. North is the front, where the face is painted.
        return "{\"from\": [" + n(x0) + ", " + n(y0) + ", " + n(z0) + "], \"to\": [" + n(x1) + ", " + n(y1) + ", "
                + n(z1) + "], \"faces\": {"
                + face("north", u + d, v + d, u + d + w, v + d + h) + ", "
                + face("south", u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h) + ", "
                + face("east", u, v + d, u + d, v + d + h) + ", "
                + face("west", u + d + w, v + d, u + 2 * d + w, v + d + h) + ", "
                + face("up", u + d, v, u + d + w, v + d) + ", "
                // vanilla draws the underside flipped, which the swapped v pair says.
                + face("down", u + d + w, v + d, u + d + 2 * w, v) + "}}";
    }

    private static String face(String side, int u0, int v0, int u1, int v1) {
        return "\"" + side + "\": {\"uv\": [" + n(u0 * UV) + ", " + n(v0 * UV) + ", " + n(u1 * UV) + ", " + n(v1 * UV)
                + "], \"texture\": \"#skin\"}";
    }

    private static String n(double v) {
        return String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    static void save(BufferedImage skin, File to) throws IOException {
        to.getParentFile().mkdirs();
        ImageIO.write(skin, "png", to);
    }
}
