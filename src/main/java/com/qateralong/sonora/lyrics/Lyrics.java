package com.qateralong.sonora.lyrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qateralong.sonora.Sonora;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Lyrics {
    public record Line(long timeMs, String text) {
    }

    public record Result(List<Line> lines, boolean synced, boolean instrumental) {
        public static final Result NONE = new Result(List.of(), false, false);

        public int indexAt(long position) {
            if (!this.synced) {
                return -1;
            }
            int index = -1;
            for (int i = 0; i < this.lines.size(); i++) {
                if (this.lines.get(i).timeMs() <= position) {
                    index = i;
                } else {
                    break;
                }
            }
            return index;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Map<String, CompletableFuture<Result>> CACHE = new ConcurrentHashMap<>();
    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]");

    private Lyrics() {
    }

    public static CompletableFuture<Result> get(String title, String artist, String album, long durationMs) {
        if (title == null || title.isBlank()) {
            return CompletableFuture.completedFuture(Result.NONE);
        }
        String key = title + "|" + artist;
        return CACHE.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> fetch(title, artist, album, durationMs)));
    }

    private static Result fetch(String title, String artist, String album, long durationMs) {
        try {
            String url = "https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                    + (album == null || album.isBlank() ? "" : "&album_name=" + enc(album))
                    + (durationMs > 0 ? "&duration=" + Math.round(durationMs / 1000.0) : "");
            JsonObject json = request(url);
            if (json == null) {
                json = searchBest(title, artist);
            }
            if (json == null) {
                return Result.NONE;
            }
            if (json.has("instrumental") && json.get("instrumental").getAsBoolean()) {
                return new Result(List.of(), false, true);
            }
            if (json.has("syncedLyrics") && !json.get("syncedLyrics").isJsonNull()) {
                return new Result(parse(json.get("syncedLyrics").getAsString()), true, false);
            }
            if (json.has("plainLyrics") && !json.get("plainLyrics").isJsonNull()) {
                List<Line> lines = new ArrayList<>();
                for (String line : json.get("plainLyrics").getAsString().split("\n")) {
                    lines.add(new Line(-1L, line.trim()));
                }
                return new Result(lines, false, false);
            }
        } catch (Exception e) {
            Sonora.LOGGER.debug("Lyrics lookup failed", e);
        }
        return Result.NONE;
    }

    private static JsonObject searchBest(String title, String artist) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(artist)))
                .header("User-Agent", "Sonora Minecraft mod").timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        var array = JsonParser.parseString(response.body()).getAsJsonArray();
        JsonObject best = null;
        for (var element : array) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("syncedLyrics") && !entry.get("syncedLyrics").isJsonNull()) {
                return entry;
            }
            if (best == null) {
                best = entry;
            }
        }
        return best;
    }

    private static JsonObject request(String url) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "Sonora Minecraft mod")
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    static List<Line> parse(String lrc) {
        List<Line> lines = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher matcher = TAG.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (matcher.find() && matcher.start() == end) {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                String fraction = matcher.group(3);
                long millis = fraction == null ? 0 : Long.parseLong((fraction + "00").substring(0, 3));
                times.add(minutes * 60_000L + seconds * 1000L + millis);
                end = matcher.end();
            }
            String text = raw.substring(end).trim();
            for (long time : times) {
                lines.add(new Line(time, text));
            }
        }
        lines.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
        return lines;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
