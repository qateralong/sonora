package com.qateralong.sonora.spotify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.SonoraConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;

public final class SpotifyAuth {
    public static final String KEYMASTER_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd";
    private static final String SCOPES = String.join(" ", "streaming", "user-read-playback-state", "user-modify-playback-state",
            "user-read-currently-playing", "playlist-read-private", "playlist-read-collaborative", "user-library-read",
            "user-library-modify", "user-read-private", "user-read-email", "user-read-recently-played", "user-top-read");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static final class Tokens {
        public String accessToken = "";
        public String refreshToken = "";
        public long expiresAt;
        public String displayName = "";
        public String product = "";
        public String clientId = "";
    }

    public enum State { LOGGED_OUT, WAITING_FOR_BROWSER, LOGGED_IN, ERROR }

    private static Tokens tokens;
    private static volatile State state = State.LOGGED_OUT;
    private static volatile String message = "";
    private static HttpServer server;
    private static volatile String pendingUrl = "";

    private SpotifyAuth() {
    }

    private static Path file() {
        return Sonora.dir().resolve("auth.json");
    }

    public static String clientId() {
        String configured = SonoraConfig.get().clientId;
        return configured == null || configured.isBlank() ? KEYMASTER_CLIENT_ID : configured.trim();
    }

    public static void load() {
        try {
            if (Files.exists(file())) {
                tokens = GSON.fromJson(Files.readString(file()), Tokens.class);
                if (tokens != null && !tokens.refreshToken.isEmpty()) {
                    state = State.LOGGED_IN;
                }
            }
        } catch (Exception e) {
            Sonora.LOGGER.error("Failed to read Spotify login", e);
        }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(tokens));
        } catch (Exception e) {
            Sonora.LOGGER.error("Failed to save Spotify login", e);
        }
    }

    public static State state() {
        return state;
    }

    public static String message() {
        return message;
    }

    public static String pendingUrl() {
        return pendingUrl;
    }

    public static boolean loggedIn() {
        return state == State.LOGGED_IN && tokens != null;
    }

    public static String displayName() {
        return tokens == null ? "" : tokens.displayName;
    }

    public static String product() {
        return tokens == null ? "" : tokens.product;
    }

    public static void logout() {
        tokens = null;
        state = State.LOGGED_OUT;
        try {
            Files.deleteIfExists(file());
        } catch (Exception ignored) {
        }
    }

    public static synchronized void startLogin() {
        stopServer();
        try {
            String verifier = randomString(64);
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String expectedState = randomString(16);
            int port = Math.max(0, SonoraConfig.get().oauthPort);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/login";
            String clientId = clientId();
            server.createContext("/login", exchange -> {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                boolean ok = expectedState.equals(query.get("state")) && query.containsKey("code");
                String page = ok ? Sonora.text("sonora.login.browser_ok") : Sonora.text("sonora.login.browser_fail") + " " + query.getOrDefault("error", "");
                byte[] body = ("<html><head><meta charset=\"utf-8\"><title>Sonora</title></head><body style=\"background:#121212;color:#fff;font-family:sans-serif;text-align:center;padding-top:15%\"><h1>"
                        + page + "</h1></body></html>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                if (ok) {
                    CompletableFuture.runAsync(() -> exchangeCode(query.get("code"), verifier, redirect, clientId));
                } else {
                    state = State.ERROR;
                    message = query.getOrDefault("error", "state mismatch");
                }
                CompletableFuture.runAsync(SpotifyAuth::stopServer);
            });
            server.start();
            String url = "https://accounts.spotify.com/authorize?response_type=code&client_id=" + enc(clientId)
                    + "&scope=" + enc(SCOPES) + "&redirect_uri=" + enc(redirect) + "&state=" + expectedState
                    + "&code_challenge_method=S256&code_challenge=" + challenge;
            pendingUrl = url;
            state = State.WAITING_FOR_BROWSER;
            message = "";
            Util.getPlatform().openUri(URI.create(url));
        } catch (Exception e) {
            state = State.ERROR;
            message = e.getMessage();
            Sonora.LOGGER.error("Failed to start Spotify login", e);
        }
    }

    private static synchronized void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public static void cancelLogin() {
        stopServer();
        if (state == State.WAITING_FOR_BROWSER) {
            state = tokens == null ? State.LOGGED_OUT : State.LOGGED_IN;
        }
    }

    private static void exchangeCode(String code, String verifier, String redirect, String clientId) {
        try {
            JsonObject json = tokenRequest(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", redirect,
                    "client_id", clientId, "code_verifier", verifier));
            Tokens fresh = new Tokens();
            fresh.clientId = clientId;
            apply(fresh, json);
            tokens = fresh;
            fetchProfile();
            state = State.LOGGED_IN;
            save();
            Sonora.onLogin();
        } catch (Exception e) {
            state = State.ERROR;
            message = e.getMessage();
            Sonora.LOGGER.error("Spotify login failed", e);
        }
    }

    private static void fetchProfile() {
        try {
            JsonObject me = SpotifyApi.get("/me");
            if (me != null) {
                tokens.displayName = me.has("display_name") && !me.get("display_name").isJsonNull() ? me.get("display_name").getAsString() : me.get("id").getAsString();
                tokens.product = me.has("product") ? me.get("product").getAsString() : "";
            }
        } catch (Exception e) {
            Sonora.LOGGER.warn("Could not read Spotify profile", e);
        }
    }

    private static void apply(Tokens target, JsonObject json) {
        target.accessToken = json.get("access_token").getAsString();
        if (json.has("refresh_token")) {
            target.refreshToken = json.get("refresh_token").getAsString();
        }
        target.expiresAt = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000L;
    }

    public static synchronized String accessToken() throws Exception {
        if (tokens == null || tokens.refreshToken.isEmpty()) {
            throw new IllegalStateException("not logged in");
        }
        if (System.currentTimeMillis() > tokens.expiresAt - 60_000L) {
            String clientId = tokens.clientId == null || tokens.clientId.isEmpty() ? clientId() : tokens.clientId;
            JsonObject json = tokenRequest(Map.of("grant_type", "refresh_token", "refresh_token", tokens.refreshToken, "client_id", clientId));
            apply(tokens, json);
            save();
        }
        return tokens.accessToken;
    }

    public static synchronized void invalidate() {
        if (tokens != null) {
            tokens.expiresAt = 0L;
        }
    }

    private static JsonObject tokenRequest(Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("token request failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null) {
            return map;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String randomString(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }
}
