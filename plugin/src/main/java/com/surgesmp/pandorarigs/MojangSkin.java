package com.surgesmp.pandorarigs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A player's skin straight from Mojang's public profile API. Not Paper's profile completion, which
 * on an offline-mode server makes up an offline profile with no skin rather than asking Mojang.
 * Blocking: call it off the main thread.
 */
record MojangSkin(URL url, boolean slim) {

    static MojangSkin lookup(String name) throws IOException {
        JsonObject who = get("https://api.mojang.com/users/profiles/minecraft/"
                + URLEncoder.encode(name, StandardCharsets.UTF_8));
        if (who == null || !who.has("id")) {
            throw new IOException("no player called " + name);
        }
        JsonObject profile = get("https://sessionserver.mojang.com/session/minecraft/profile/"
                + who.get("id").getAsString());
        if (profile == null || !profile.has("properties")) {
            throw new IOException("Mojang has no profile for " + name);
        }
        for (var p : profile.getAsJsonArray("properties")) {
            JsonObject prop = p.getAsJsonObject();
            if (!"textures".equals(prop.get("name").getAsString())) {
                continue;
            }
            String json = new String(Base64.getDecoder().decode(prop.get("value").getAsString()), StandardCharsets.UTF_8);
            JsonObject textures = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures");
            JsonObject skin = textures == null ? null : textures.getAsJsonObject("SKIN");
            if (skin == null) {
                break;
            }
            boolean slim = skin.has("metadata")
                    && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
            return new MojangSkin(URI.create(skin.get("url").getAsString()).toURL(), slim);
        }
        throw new IOException(name + " wears a default skin, which has no file to cut up");
    }

    /** The response as JSON, or null on a 204/404 (Mojang's "nobody by that name"). */
    private static JsonObject get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        int code = c.getResponseCode();
        if (code == 204 || code == 404) {
            return null;
        }
        if (code != 200) {
            throw new IOException("Mojang answered " + code + (code == 429 ? " (rate limited, try again in a minute)" : ""));
        }
        try (InputStream in = c.getInputStream()) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
