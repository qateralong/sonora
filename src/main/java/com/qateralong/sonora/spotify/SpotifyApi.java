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

public final class SpotifyApi {
    private static final String BASE = "https://api.spotify.com/v1";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

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

    private static JsonObject request(String method, String path, JsonObject body) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            String token = SpotifyAuth.accessToken();
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
                SpotifyAuth.invalidate();
                continue;
            }
            if (status == 429) {
                long wait = response.headers().firstValueAsLong("Retry-After").orElse(1L);
                Thread.sleep(Math.min(5000L, wait * 1000L));
                continue;
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
                throw new SpotifyException(status, message);
            }
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        }
        throw new SpotifyException(429, "rate limited");
    }

    public static final class SpotifyException extends Exception {
        public final int status;

        public SpotifyException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
