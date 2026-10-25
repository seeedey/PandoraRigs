package com.surgesmp.pandorarigs;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * PandoraRigs: Pandora's six-bone display rig, for any skin.
 *
 * <p>A rig is made from a skin with {@code /rig create}. Then anyone with the permission can
 * {@code /rig become} it, and an op can set it loose as a {@code /rig boss} or stand it up as a
 * {@code /rig statue}. The models live in a small pack this plugin builds and serves itself.
 */
public final class PandoraRigs extends JavaPlugin {

    private final Map<String, RigType> types = new LinkedHashMap<>();
    private File skins;
    private PackHost pack;
    private Avatars avatars;
    private Bosses bosses;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        skins = new File(getDataFolder(), "skins");
        skins.mkdirs();
        unpackBundled("pandora");

        pack = new PackHost(this, new File(getDataFolder(), "rigs-pack.zip"));
        avatars = new Avatars(this);
        bosses = new Bosses(this);
        load();
        rebuildPack(null);
        pack.start();

        // Every display is non-persistent and so is every boss, but a hard crash between a save and
        // a remove can still leave one in a chunk. Anything tagged that loads in is a leftover.
        for (var world : getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                sweep(e);
            }
        }

        getServer().getPluginManager().registerEvents(new RigListener(this), this);
        RigCommand command = new RigCommand(this);
        getCommand("rig").setExecutor(command);
        getCommand("rig").setTabCompleter(command);
        getServer().getScheduler().runTaskTimer(this, () -> {
            avatars.tick();
            bosses.tick();
        }, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (avatars != null) {
            avatars.leaveAll();
        }
        if (bosses != null) {
            bosses.clear(null, 0);
        }
        if (pack != null) {
            pack.stop();
        }
    }

    /** Removes a rig display or boss mob nobody owns any more. */
    void sweep(Entity e) {
        if (e instanceof ItemDisplay && e.getPersistentDataContainer().has(Rig.ownedKey(this))) {
            e.remove();
        } else if (e.getPersistentDataContainer().has(bosses.tag()) && bosses.body(e) == null) {
            e.remove();
        }
    }

    /** Reads the rigs from config. A rig whose skin is missing is skipped with a warning. */
    void load() {
        reloadConfig();
        types.clear();
        ConfigurationSection rigs = getConfig().getConfigurationSection("rigs");
        ConfigurationSection defaults = getConfig().getConfigurationSection("defaults");
        if (rigs == null) {
            return;
        }
        for (String id : rigs.getKeys(false)) {
            if (!RigType.ID.matcher(id).matches()) {
                getLogger().warning("Rig id '" + id + "' must be lowercase letters, digits and _ — skipped.");
                continue;
            }
            if (!new File(skins, id + ".png").isFile()) {
                getLogger().warning("Rig '" + id + "' has no skins/" + id + ".png — skipped.");
                continue;
            }
            types.put(id, RigType.read(id, rigs.getConfigurationSection(id), defaults));
        }
    }

    /** Rebuilds the pack from every loaded rig, off the main thread, then resends it to everyone. */
    void rebuildPack(Consumer<String> done) {
        Collection<String> ids = new ArrayList<>(types.keySet());
        Runnable work = () -> {
            String error = null;
            try {
                byte[] sha1 = SkinPack.build(skins, ids, new File(getDataFolder(), "rigs-pack.zip"));
                if (done == null) {
                    // On the main thread already (startup): set it now so the url logged next is whole.
                    pack.hash(sha1);
                } else {
                    getServer().getScheduler().runTask(this, () -> {
                        pack.hash(sha1);
                        pack.sendAll();
                    });
                }
            } catch (IOException ex) {
                error = ex.getMessage();
                getLogger().severe("Could not build the rig pack: " + error);
            }
            if (done != null) {
                String e = error;
                getServer().getScheduler().runTask(this, () -> done.accept(e));
            }
        };
        if (done == null) {
            work.run();
        } else {
            getServer().getScheduler().runTaskAsynchronously(this, work);
        }
    }

    /** Puts a skin that ships in the jar into skins/, normalised, the first time the plugin runs. */
    private void unpackBundled(String id) {
        File to = new File(skins, id + ".png");
        if (to.isFile()) {
            return;
        }
        try (InputStream in = getResource("skins/" + id + ".png")) {
            if (in == null) {
                return;
            }
            BufferedImage raw = ImageIO.read(in);
            SkinPack.save(SkinPack.normalise(raw, null), to);
        } catch (IOException ex) {
            getLogger().warning("Could not unpack the bundled " + id + " skin: " + ex.getMessage());
        }
    }

    Map<String, RigType> types() {
        return types;
    }

    File skins() {
        return skins;
    }

    PackHost pack() {
        return pack;
    }

    Avatars avatars() {
        return avatars;
    }

    Bosses bosses() {
        return bosses;
    }
}
