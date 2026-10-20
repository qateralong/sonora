package com.qateralong.sonora.spotify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import com.qateralong.sonora.Sonora;

public final class SpotifyApi {
    private static final String BASE = "https://api.spotify.com/v1";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static volatile long blockedUntil;

    private SpotifyApi() {
    }

    public static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static JsonObject get(String path) throws Exception {
        return request("GET", path, null);
    }

    public static JsonObject put(String path, JsonObject body) throws Exception {
        return request("PUT", path, body);
    }

    public static JsonObject post(String path, JsonObject body) throws Exception {
        return request("POST", path, body);
    }

    public static JsonObject delete(String path, JsonObject body) throws Exception {
        return request("DELETE", path, body);
    }

    public static void clearBlock() {
        blockedUntil = 0L;
    }

    public static long blockedSeconds() {
        return Math.max(0L, (blockedUntil - System.currentTimeMillis() + 999L) / 1000L);
    }

    public static HttpResponse<String> raw(String path) throws Exception {
        checkBlock();
        HttpRequest request = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : BASE + path))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + SpotifyAuth.webToken()).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            block(response);
        }
        return response;
    }

    private static void checkBlock() throws SpotifyException {
        long seconds = blockedSeconds();
        if (seconds > 0L) {
            throw new SpotifyException(429, Sonora.text("sonora.error.rate_limited", seconds));
        }
    }

    private static void block(HttpResponse<String> response) throws SpotifyException {
        long wait = Math.max(1L, response.headers().firstValueAsLong("Retry-After").orElse(5L));
        blockedUntil = System.currentTimeMillis() + wait * 1000L;
        throw new SpotifyException(429, Sonora.text("sonora.error.rate_limited", wait));
    }

    private static JsonObject request(String method, String path, JsonObject body) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            checkBlock();
            String token = SpotifyAuth.webToken();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : BASE + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token);
            String payload = body == null ? (method.equals("GET") ? null : "") : body.toString();
            if (payload == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(payload));
            }
            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 401 && attempt == 0) {
                SpotifyAuth.invalidateWeb();
                continue;
            }
            if (status == 429) {
                long wait = response.headers().firstValueAsLong("Retry-After").orElse(5L);
                if (wait <= 2L && attempt == 0) {
                    Thread.sleep(wait * 1000L);
                    continue;
                }
                block(response);
            }
            if (status == 204 || response.body() == null || response.body().isBlank()) {
                if (status >= 400) {
                    throw new SpotifyException(status, "HTTP " + status);
                }
                return null;
            }
            JsonElement json;
            try {
                json = JsonParser.parseString(response.body());
            } catch (Exception e) {
                if (status >= 400) {
                    throw new SpotifyException(status, "HTTP " + status);
                }
                return null;
            }
            if (status >= 400) {
                String message = "HTTP " + status;
                if (json.isJsonObject() && json.getAsJsonObject().has("error")) {
                    JsonElement error = json.getAsJsonObject().get("error");
                    message = error.isJsonObject() && error.getAsJsonObject().has("message") ? error.getAsJsonObject().get("message").getAsString() : error.toString();
                }
                if (status == 403 && message.toLowerCase(java.util.Locale.ROOT).contains("registered")) {
                    message = Sonora.text("sonora.error.not_registered");
                }
                throw new SpotifyException(status, message);
            }
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        }
        throw new SpotifyException(401, "unauthorized");
    }

    public static final class SpotifyException extends Exception {
        public final int status;

        public SpotifyException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
