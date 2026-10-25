package com.surgesmp.pandorarigs;

import java.util.Locale;

/**
 * The things Pandora can do on purpose, as opposed to what its legs do because somebody is
 * walking.
 *
 * <p>{@code ticks} of zero means held: the pose stays until the driver moves, which is what a
 * kneel or a throne wants. Everything else plays once and hands back. Movement cancels any emote
 * except {@link #RISE}, which lifts the body off the ground and would look broken if a step could
 * drop it out of the air.
 */
public enum Emote {
    BOW("Bow", 44),
    WELCOME("Welcome", 56),
    KNEEL("Kneel", 0),
    POINT("Point", 44),
    LAUGH("Laugh", 50),
    JUDGE("Judge", 0),
    RISE("Rise", 80),
    WAVE("Wave", 44),
    THRONE("Throne", 0);

    private final String display;
    private final int ticks;

    Emote(String display, int ticks) {
        this.display = display;
        this.ticks = ticks;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String display() {
        return display;
    }

    public int ticks() {
        return ticks;
    }

    public boolean held() {
        return ticks == 0;
    }

    public Emote next() {
        Emote[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public Emote previous() {
        Emote[] all = values();
        return all[(ordinal() + all.length - 1) % all.length];
    }

    public static Emote byId(String id) {
        if (id == null) {
            return null;
        }
        for (Emote emote : values()) {
            if (emote.id().equalsIgnoreCase(id)) {
                return emote;
            }
        }
        return null;
    }
}
