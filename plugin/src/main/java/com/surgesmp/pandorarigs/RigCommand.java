package com.surgesmp.pandorarigs;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** {@code /rig} — everything is one command so the tab list stays one word long. */
final class RigCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("help", "list", "create", "delete", "become", "leave", "emote",
            "scale", "boss", "statue", "clear", "pack", "reload");

    private final PandoraRigs plugin;

    RigCommand(PandoraRigs plugin) {
        this.plugin = plugin;
    }

    private static void say(CommandSender to, String mini) {
        to.sendMessage(MiniMessage.miniMessage().deserialize("<#B8A6E8>[Rigs]</#B8A6E8> <gray>" + mini));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(sender);
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "become" -> become(sender, args);
            case "leave" -> leave(sender, args);
            case "emote" -> emote(sender, args);
            case "scale" -> scale(sender, args);
            case "boss" -> boss(sender, args);
            case "statue" -> statue(sender, args);
            case "clear" -> clear(sender, args);
            case "pack" -> {
                if (sender instanceof Player p) {
                    plugin.pack().send(p);
                    say(p, "Pack sent: <white>" + plugin.pack().url());
                } else {
                    say(sender, plugin.pack().url());
                }
            }
            case "reload" -> {
                if (!perm(sender, "pandorarigs.admin")) {
                    return true;
                }
                plugin.load();
                plugin.pack().start();
                plugin.rebuildPack(err -> say(sender, err == null
                        ? "Reloaded " + plugin.types().size() + " rigs and resent the pack."
                        : "<red>Reloaded, but the pack failed: " + err));
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        say(s, "<white>/" + label + " list</white> — the rigs there are");
        say(s, "<white>/" + label + " become <rig> [player]</white> — wear one; <white>leave</white> to stop");
        say(s, "<white>/" + label + " emote <name></white> — " + String.join(", ",
                Arrays.stream(Emote.values()).map(Emote::id).toList()));
        say(s, "<white>/" + label + " scale <size></white> — resize the rig you wear (0.5–4)");
        say(s, "<white>/" + label + " create <id> <player name | file.png> [slim|classic]</white> — make a rig from a skin");
        say(s, "<white>/" + label + " boss <rig> [mob]</white> — set one loose as a boss");
        say(s, "<white>/" + label + " statue <rig></white> — stand one here; <white>clear [radius]</white> removes them");
    }

    private boolean perm(CommandSender s, String node) {
        if (s.hasPermission(node)) {
            return true;
        }
        say(s, "<red>You can't do that.");
        return false;
    }

    private RigType type(CommandSender s, String[] args, int i) {
        if (args.length <= i) {
            say(s, "<red>Which rig? <gray>" + String.join(", ", plugin.types().keySet()));
            return null;
        }
        RigType t = plugin.types().get(args[i].toLowerCase(Locale.ROOT));
        if (t == null) {
            say(s, "<red>No rig called " + args[i] + ".");
        }
        return t;
    }

    private void list(CommandSender s) {
        if (plugin.types().isEmpty()) {
            say(s, "No rigs yet. <white>/rig create <id> <player></white> makes one.");
            return;
        }
        for (RigType t : plugin.types().values()) {
            say(s, "<white>" + t.id() + "</white> " + t.display() + " <dark_gray>size " + t.scale() + ", boss "
                    + t.boss().mob().name().toLowerCase(Locale.ROOT) + " " + (int) t.boss().health() + "hp");
        }
        say(s, "<dark_gray>" + plugin.bosses().bossCount() + " bosses, " + plugin.bosses().statueCount()
                + " statues out.");
    }

    // ---------------------------------------------------------------- making one

    private void create(CommandSender s, String[] args) {
        if (!perm(s, "pandorarigs.create")) {
            return;
        }
        if (args.length < 3) {
            say(s, "<red>/rig create <id> <player name | file.png in plugins/PandoraRigs/skins/> [slim|classic]");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!RigType.ID.matcher(id).matches()) {
            say(s, "<red>An id is lowercase letters, digits and _ (up to 32).");
            return;
        }
        String source = args[2];
        Boolean forced = args.length > 3 ? (args[3].equalsIgnoreCase("slim") ? Boolean.TRUE
                : args[3].equalsIgnoreCase("classic") ? Boolean.FALSE : null) : null;
        say(s, "Fetching the skin…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BufferedImage raw;
                Boolean slim = forced;
                if (source.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    File file = new File(plugin.skins(), source);
                    if (!file.getCanonicalPath().startsWith(plugin.skins().getCanonicalPath()) || !file.isFile()) {
                        throw new IOException("no file skins/" + source);
                    }
                    raw = ImageIO.read(file);
                } else {
                    MojangSkin skin = MojangSkin.lookup(source);
                    if (slim == null) {
                        slim = skin.slim();
                    }
                    raw = ImageIO.read(skin.url());
                }
                if (raw == null) {
                    throw new IOException("that is not an image");
                }
                SkinPack.save(SkinPack.normalise(raw, slim), new File(plugin.skins(), id + ".png"));
                plugin.getServer().getScheduler().runTask(plugin, () -> finishCreate(s, id));
            } catch (IOException | RuntimeException ex) {
                plugin.getLogger().warning("/rig create " + id + " " + source + " failed: " + ex);
                plugin.getServer().getScheduler().runTask(plugin, () -> say(s, "<red>Couldn't make it: " + ex.getMessage()));
            }
        });
    }

    private void finishCreate(CommandSender s, String id) {
        if (!plugin.getConfig().isConfigurationSection("rigs." + id)) {
            plugin.getConfig().set("rigs." + id + ".display", "<bold>" + id + "</bold>");
        }
        plugin.saveConfig();
        plugin.load();
        plugin.rebuildPack(err -> say(s, err == null
                ? "Made <white>" + id + "</white> and sent the pack. <white>/rig become " + id
                        + "</white> or <white>/rig boss " + id + "</white>. Tune it under rigs." + id + " in config.yml."
                : "<red>Saved the skin, but the pack failed: " + err));
    }

    private void delete(CommandSender s, String[] args) {
        if (!perm(s, "pandorarigs.create")) {
            return;
        }
        RigType t = type(s, args, 1);
        if (t == null) {
            return;
        }
        plugin.getConfig().set("rigs." + t.id(), null);
        plugin.saveConfig();
        // The skin is kept as skins/<id>.png — /rig create <id> <id>.png brings it back.
        plugin.load();
        plugin.rebuildPack(err -> say(s, "Deleted <white>" + t.id() + "</white>. Its skin is still in skins/."));
    }

    // ---------------------------------------------------------------- wearing one

    private Player target(CommandSender s, String[] args, int i) {
        if (args.length > i) {
            if (!perm(s, "pandorarigs.become.others")) {
                return null;
            }
            Player p = plugin.getServer().getPlayerExact(args[i]);
            if (p == null) {
                say(s, "<red>" + args[i] + " isn't online.");
            }
            return p;
        }
        if (s instanceof Player p) {
            return p;
        }
        say(s, "<red>Name a player.");
        return null;
    }

    private void become(CommandSender s, String[] args) {
        if (!perm(s, "pandorarigs.become")) {
            return;
        }
        RigType t = type(s, args, 1);
        Player p = t == null ? null : target(s, args, 2);
        if (p == null) {
            return;
        }
        plugin.avatars().become(p, t);
        say(p, "You are " + t.display() + "<gray>. <white>/rig leave</white> to be yourself again.");
        if (s != p) {
            say(s, p.getName() + " is now " + t.display() + ".");
        }
    }

    private void leave(CommandSender s, String[] args) {
        if (!perm(s, "pandorarigs.become")) {
            return;
        }
        Player p = target(s, args, 1);
        if (p == null) {
            return;
        }
        if (!plugin.avatars().wearing(p)) {
            say(s, "<red>" + (s == p ? "You aren't" : p.getName() + " isn't") + " wearing a rig.");
            return;
        }
        plugin.avatars().leave(p);
        say(p, "You're yourself again.");
    }

    private void emote(CommandSender s, String[] args) {
        if (!(s instanceof Player p) || !perm(s, "pandorarigs.become")) {
            return;
        }
        Body body = plugin.avatars().of(p);
        if (body == null) {
            say(p, "<red>Wear a rig first.");
            return;
        }
        Emote e = args.length > 1 ? Emote.byId(args[1]) : null;
        if (e == null) {
            say(p, "<red>Emotes: " + String.join(", ", Arrays.stream(Emote.values()).map(Emote::id).toList()));
            return;
        }
        Location aim = p.getEyeLocation().add(p.getLocation().getDirection().multiply(6));
        body.animator.emote(e, aim);
    }

    private void scale(CommandSender s, String[] args) {
        if (!(s instanceof Player p) || !perm(s, "pandorarigs.become")) {
            return;
        }
        if (!plugin.avatars().wearing(p) || args.length < 2) {
            say(p, "<red>/rig scale <size>, while wearing a rig.");
            return;
        }
        try {
            float size = Float.parseFloat(args[1]);
            float max = p.hasPermission("pandorarigs.admin") ? 8f : 4f;
            plugin.avatars().scale(p, Math.max(0.5f, Math.min(max, size)));
        } catch (NumberFormatException ex) {
            say(p, "<red>That isn't a number.");
        }
    }

    // ---------------------------------------------------------------- setting one loose

    private void boss(CommandSender s, String[] args) {
        if (!(s instanceof Player p) || !perm(s, "pandorarigs.spawn")) {
            return;
        }
        RigType t = type(s, args, 1);
        if (t == null) {
            return;
        }
        if (args.length > 2) {
            try {
                EntityType mob = EntityType.valueOf(args[2].toUpperCase(Locale.ROOT));
                if (!mob.isAlive() || !mob.isSpawnable()) {
                    throw new IllegalArgumentException();
                }
                t = new RigType(t.id(), t.display(), t.scale(), new RigType.Boss(mob, t.boss().health(),
                        t.boss().damage(), t.boss().speed(), t.boss().scale(), t.boss().weapon(), t.boss().bar()));
            } catch (IllegalArgumentException ex) {
                say(p, "<red>" + args[2] + " isn't a mob.");
                return;
            }
        }
        Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(4));
        if (plugin.bosses().boss(t, at) == null) {
            say(p, "<red>A " + t.boss().mob().name().toLowerCase(Locale.ROOT) + " can't carry a rig — pick a mob.");
            return;
        }
        say(p, t.display() + "<gray> is loose.");
    }

    private void statue(CommandSender s, String[] args) {
        if (!(s instanceof Player p) || !perm(s, "pandorarigs.spawn")) {
            return;
        }
        RigType t = type(s, args, 1);
        if (t != null) {
            plugin.bosses().statue(t, p.getLocation());
            say(p, "Stood " + t.display() + "<gray> here.");
        }
    }

    private void clear(CommandSender s, String[] args) {
        if (!perm(s, "pandorarigs.spawn")) {
            return;
        }
        Location at = null;
        double radius = 0;
        if (args.length > 1 && s instanceof Player p && !args[1].equalsIgnoreCase("all")) {
            try {
                radius = Double.parseDouble(args[1]);
                at = p.getLocation();
            } catch (NumberFormatException ex) {
                say(s, "<red>/rig clear [radius | all]");
                return;
            }
        }
        say(s, "Removed " + plugin.bosses().clear(at, radius) + ".");
    }

    // ---------------------------------------------------------------- tab

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(SUBS);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "become", "boss", "statue", "delete" -> out.addAll(plugin.types().keySet());
                case "emote" -> Arrays.stream(Emote.values()).map(Emote::id).forEach(out::add);
                case "clear" -> out.addAll(List.of("16", "64", "all"));
                case "leave" -> plugin.getServer().getOnlinePlayers().forEach(p -> out.add(p.getName()));
                default -> {
                }
            }
        } else if (args.length == 3) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "become" -> plugin.getServer().getOnlinePlayers().forEach(p -> out.add(p.getName()));
                case "boss" -> out.addAll(List.of("husk", "zombie", "wither_skeleton", "piglin_brute", "vindicator"));
                case "create" -> {
                    plugin.getServer().getOnlinePlayers().forEach(p -> out.add(p.getName()));
                    String[] files = plugin.skins().list((d, n) -> n.endsWith(".png"));
                    if (files != null) {
                        out.addAll(List.of(files));
                    }
                }
                default -> {
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("create")) {
            out.addAll(List.of("slim", "classic"));
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
