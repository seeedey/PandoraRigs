package com.surgesmp.pandorarigs;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.util.concurrent.ThreadLocalRandom;

/** Turns what happens to a rig's wearer into what the rig does: swings, flinches, talking. */
final class RigListener implements Listener {

    private final PandoraRigs plugin;

    RigListener(PandoraRigs plugin) {
        this.plugin = plugin;
    }

    private Body body(Entity e) {
        if (e instanceof Player p) {
            return plugin.avatars().of(p);
        }
        return plugin.bosses().body(e);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.avatars().repair(event.getPlayer());
        plugin.pack().send(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.avatars().leave(event.getPlayer());
    }

    @EventHandler
    public void onSwing(PlayerArmSwingEvent event) {
        Body body = plugin.avatars().of(event.getPlayer());
        if (body != null) {
            body.animator.swing();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Body attacker = body(event.getDamager());
        if (attacker != null) {
            // A mob has no arm-swing event, so its hit is its swing.
            if (!(event.getDamager() instanceof Player)) {
                attacker.animator.swing();
            }
            attacker.animator.connected();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        Body body = body(event.getEntity());
        if (body != null) {
            body.animator.flinch(ThreadLocalRandom.current().nextBoolean() ? 1f : -1f);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        plugin.bosses().gone(event.getEntity());
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        int length = PlainTextComponentSerializer.plainText().serialize(event.message()).length();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Body body = plugin.avatars().of(player);
            if (body != null) {
                body.animator.speak(Math.min(120, 20 + length * 2));
                body.animator.nod();
            }
        });
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            plugin.sweep(e);
        }
    }
}
