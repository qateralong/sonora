package com.qateralong.sonora.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record TrackInfo(String id, String uri, String title, List<String> artists, String album, String artUrl,
                        long durationMs, Path path, byte[] artwork) {
    public boolean local() {
        return this.path != null;
    }

    public String artKey() {
        if (this.path != null) {
            return this.artwork == null ? null : "local:" + this.path.toAbsolutePath();
        }
        return this.artUrl;
    }

    public String artistLine() {
        return String.join(", ", this.artists);
    }

    public static TrackInfo fromSpotify(JsonObject item) {
        if (item == null) {
            return null;
        }
        List<String> artists = new ArrayList<>();
        if (item.has("artists")) {
            for (JsonElement artist : item.getAsJsonArray("artists")) {
                artists.add(string(artist.getAsJsonObject(), "name"));
            }
        } else if (item.has("show") && item.get("show").isJsonObject()) {
            artists.add(string(item.getAsJsonObject("show"), "name"));
        }
        String album = "";
        String art = null;
        if (item.has("album") && item.get("album").isJsonObject()) {
            JsonObject albumObject = item.getAsJsonObject("album");
            album = string(albumObject, "name");
            art = bestImage(albumObject.getAsJsonArray("images"), 300);
        } else if (item.has("images")) {
            art = bestImage(item.getAsJsonArray("images"), 300);
        }
        long duration = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0L;
        return new TrackInfo(string(item, "id"), string(item, "uri"), string(item, "name"), artists, album, art, duration, null, null);
    }

    public static String bestImage(JsonArray images, int target) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (JsonElement element : images) {
            JsonObject image = element.getAsJsonObject();
            int width = image.has("width") && !image.get("width").isJsonNull() ? image.get("width").getAsInt() : target;
            int score = Math.abs(width - target) + (width < target ? 1000 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = string(image, "url");
            }
        }
        return best;
    }

    public static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
