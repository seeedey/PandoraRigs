package com.surgesmp.pandorarigs;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Serves the rig pack and hands it to players.
 *
 * <p>The pack goes out under its own fixed id, so it stacks with whatever pack the server already
 * sends instead of replacing it — and resending it after a new rig replaces only this one.
 */
final class PackHost {

    /** The same for every server, so a client that already has this pack swaps it rather than adding a second. */
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("pandorarigs:pack".getBytes());

    private final PandoraRigs plugin;
    private final File zip;
    private HttpServer server;
    /** The hash of the zip this plugin built, and of the one at pack.external-url when that is set. */
    private byte[] sha1;
    private byte[] externalSha1;

    PackHost(PandoraRigs plugin, File zip) {
        this.plugin = plugin;
        this.zip = zip;
    }

    void start() {
        stop();
        if (!plugin.getConfig().getBoolean("pack.send", true)) {
            return;
        }
        String ext = external();
        if (!ext.isEmpty()) {
            fetchExternal(ext);
            return;
        }
        int port = plugin.getConfig().getInt("pack.port", 8125);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    if (!zip.isFile()) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    byte[] data = Files.readAllBytes(zip.toPath());
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(200, data.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(data);
                    }
                }
            });
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            plugin.getLogger().info("Serving the rig pack on port " + port + " at " + url());
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not serve the rig pack on port " + port + ": " + ex.getMessage()
                    + " — set pack.port to a free port, or pack.external-url.");
            server = null;
        }
    }

    /**
     * The hash a client checks has to be of the file at the url, not of our own build, which an
     * uploaded zip from the Maker site (or any other) will not match byte for byte. So it is fetched.
     */
    private void fetchExternal(String ext) {
        externalSha1 = null;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (InputStream in = URI.create(ext).toURL().openStream()) {
                byte[] hash = MessageDigest.getInstance("SHA-1").digest(in.readAllBytes());
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    externalSha1 = hash;
                    plugin.getLogger().info("Using the rig pack at " + ext + " (sha1 " + HexFormat.of().formatHex(hash) + ")");
                    sendAll();
                });
            } catch (Exception ex) {
                plugin.getLogger().severe("Could not fetch pack.external-url " + ext + ": " + ex
                        + " — nobody is sent the rig pack until that url works.");
            }
        });
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    void hash(byte[] sha1) {
        this.sha1 = sha1;
    }

    boolean ready() {
        return current() != null && (server != null || !external().isEmpty());
    }

    private byte[] current() {
        return external().isEmpty() ? sha1 : externalSha1;
    }

    private String external() {
        return plugin.getConfig().getString("pack.external-url", "").trim();
    }

    /** The url, with the hash on the end so a client never serves itself a stale cached copy. */
    String url() {
        String ext = external();
        String hex = sha1 == null ? "" : HexFormat.of().formatHex(sha1);
        if (!ext.isEmpty()) {
            return ext;
        }
        String host = plugin.getConfig().getString("pack.host", "auto");
        if (host == null || host.isBlank() || host.equalsIgnoreCase("auto")) {
            host = plugin.getServer().getIp();
            if (host == null || host.isBlank()) {
                host = "127.0.0.1";
            }
        }
        return "http://" + host + ":" + plugin.getConfig().getInt("pack.port", 8125) + "/rigs-" + hex + ".zip";
    }

    void send(Player player) {
        // Off when the models are merged into the server's own pack instead.
        if (!plugin.getConfig().getBoolean("pack.send", true) || !ready()) {
            return;
        }
        player.setResourcePack(PACK_ID, url(), current(),
                MiniMessage.miniMessage().deserialize(plugin.getConfig().getString("pack.prompt", "")),
                plugin.getConfig().getBoolean("pack.required", false));
    }

    void sendAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            send(player);
        }
    }
}
