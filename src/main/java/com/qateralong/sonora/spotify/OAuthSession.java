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
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.Util;

public final class OAuthSession {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public static final class Tokens {
        public String accessToken = "";
        public String refreshToken = "";
        public long expiresAt;
        public String displayName = "";
        public String product = "";
        public String clientId = "";
    }

    public enum State { LOGGED_OUT, WAITING_FOR_BROWSER, LOGGED_IN, ERROR }

    private final String fileName;
    private final Supplier<String> clientId;
    private final String scopes;
    private final java.util.function.IntSupplier port;
    private final Runnable onLogin;
    private Tokens tokens;
    private volatile State state = State.LOGGED_OUT;
    private volatile String message = "";
    private volatile String pendingUrl = "";
    private volatile long nextProfileAttempt;
    private HttpServer server;

    public OAuthSession(String fileName, Supplier<String> clientId, String scopes, java.util.function.IntSupplier port, Runnable onLogin) {
        this.fileName = fileName;
        this.port = port;
        this.clientId = clientId;
        this.scopes = scopes;
        this.onLogin = onLogin;
    }

    private Path file() {
        return Sonora.dir().resolve(this.fileName);
    }

    public String clientId() {
        return this.clientId.get();
    }

    public void load() {
        try {
            if (Files.exists(this.file())) {
                this.tokens = GSON.fromJson(Files.readString(this.file()), Tokens.class);
                if (this.tokens != null && !this.tokens.refreshToken.isEmpty()) {
                    this.state = State.LOGGED_IN;
                }
            }
        } catch (Exception e) {
            Sonora.LOGGER.error("Failed to read Spotify login {}", this.fileName, e);
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(this.file().getParent());
            Files.writeString(this.file(), GSON.toJson(this.tokens));
        } catch (Exception e) {
            Sonora.LOGGER.error("Failed to save Spotify login", e);
        }
    }

    public State state() {
        return this.state;
    }

    public String message() {
        return this.message;
    }

    public String pendingUrl() {
        return this.pendingUrl;
    }

    public boolean loggedIn() {
        return this.state == State.LOGGED_IN && this.tokens != null;
    }

    public String displayName() {
        return this.tokens == null ? "" : this.tokens.displayName;
    }

    public String product() {
        return this.tokens == null ? "" : this.tokens.product;
    }

    public String tokenClientId() {
        return this.tokens == null ? "" : this.tokens.clientId;
    }

    public void logout() {
        this.stopServer();
        this.tokens = null;
        this.state = State.LOGGED_OUT;
        this.message = "";
        try {
            Files.deleteIfExists(this.file());
        } catch (Exception ignored) {
        }
    }

    public synchronized void startLogin() {
        this.stopServer();
        String clientId = this.clientId();
        if (clientId.isBlank()) {
            this.state = State.ERROR;
            this.message = "no client id";
            return;
        }
        try {
            String verifier = randomString(64);
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String expectedState = randomString(16);
            int port = Math.max(0, this.port.getAsInt());
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            String redirect = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/login";
            this.server.createContext("/login", exchange -> {
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
                    CompletableFuture.runAsync(() -> this.exchangeCode(query.get("code"), verifier, redirect, clientId));
                } else {
                    this.state = State.ERROR;
                    this.message = query.getOrDefault("error", "state mismatch");
                }
                CompletableFuture.runAsync(this::stopServer);
            });
            this.server.start();
            String url = "https://accounts.spotify.com/authorize?response_type=code&client_id=" + enc(clientId)
                    + "&scope=" + enc(this.scopes) + "&redirect_uri=" + enc(redirect) + "&state=" + expectedState
                    + "&code_challenge_method=S256&code_challenge=" + challenge;
            this.pendingUrl = url;
            this.state = State.WAITING_FOR_BROWSER;
            this.message = "";
            Util.getPlatform().openUri(URI.create(url));
        } catch (Exception e) {
            this.state = State.ERROR;
            this.message = e.getMessage();
            Sonora.LOGGER.error("Failed to start Spotify login", e);
        }
    }

    private synchronized void stopServer() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }
    }

    public void cancelLogin() {
        this.stopServer();
        if (this.state == State.WAITING_FOR_BROWSER) {
            this.state = this.tokens == null ? State.LOGGED_OUT : State.LOGGED_IN;
        }
    }

    private void exchangeCode(String code, String verifier, String redirect, String clientId) {
        try {
            JsonObject json = tokenRequest(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", redirect,
                    "client_id", clientId, "code_verifier", verifier));
            Tokens fresh = new Tokens();
            fresh.clientId = clientId;
            apply(fresh, json);
            this.tokens = fresh;
            this.state = State.LOGGED_IN;
            this.save();
            this.onLogin.run();
            this.refreshProfile();
        } catch (Exception e) {
            this.state = State.ERROR;
            this.message = e.getMessage();
            Sonora.LOGGER.error("Spotify login failed", e);
        }
    }

    public void refreshProfile() {
        if (!this.loggedIn() || System.currentTimeMillis() < this.nextProfileAttempt) {
            return;
        }
        this.nextProfileAttempt = System.currentTimeMillis() + 60_000L;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + this.accessToken()).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                Sonora.LOGGER.warn("Could not read Spotify profile: HTTP {} {}", response.statusCode(), response.body());
                if (response.statusCode() == 403 && response.body() != null && response.body().toLowerCase(java.util.Locale.ROOT).contains("registered")) {
                    this.state = State.ERROR;
                    this.message = Sonora.text("sonora.error.not_registered");
                }
                return;
            }
            JsonObject me = JsonParser.parseString(response.body()).getAsJsonObject();
            String name = me.has("display_name") && !me.get("display_name").isJsonNull() ? me.get("display_name").getAsString() : "";
            this.tokens.displayName = name.isBlank() && me.has("id") ? me.get("id").getAsString() : name;
            this.tokens.product = me.has("product") && !me.get("product").isJsonNull() ? me.get("product").getAsString() : "";
            this.save();
        } catch (Exception e) {
            Sonora.LOGGER.warn("Could not read Spotify profile: {}", e.getMessage());
        }
    }

    private static void apply(Tokens target, JsonObject json) {
        target.accessToken = json.get("access_token").getAsString();
        if (json.has("refresh_token")) {
            target.refreshToken = json.get("refresh_token").getAsString();
        }
        target.expiresAt = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000L;
    }

    public synchronized String accessToken() throws Exception {
        if (this.tokens == null || this.tokens.refreshToken.isEmpty()) {
            throw new IllegalStateException("not logged in");
        }
        if (System.currentTimeMillis() > this.tokens.expiresAt - 60_000L) {
            String clientId = this.tokens.clientId == null || this.tokens.clientId.isEmpty() ? this.clientId() : this.tokens.clientId;
            JsonObject json = tokenRequest(Map.of("grant_type", "refresh_token", "refresh_token", this.tokens.refreshToken, "client_id", clientId));
            apply(this.tokens, json);
            this.save();
        }
        return this.tokens.accessToken;
    }

    public synchronized void invalidate() {
        if (this.tokens != null) {
            this.tokens.expiresAt = 0L;
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
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            Sonora.LOGGER.warn("Spotify token request failed: HTTP {} {}", response.statusCode(), response.body());
            if (response.body() != null && response.body().contains("invalid_grant")) {
                throw new IllegalStateException(Sonora.text("sonora.error.relogin"));
            }
            throw new IllegalStateException(Sonora.text("sonora.error.token", response.statusCode()));
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
