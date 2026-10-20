package com.qateralong.sonora.spotify;

import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.SonoraConfig;

public final class SpotifyAuth {
    public static final String KEYMASTER_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd";
    private static final String SCOPES = String.join(" ", "streaming", "user-read-playback-state", "user-modify-playback-state",
            "user-read-currently-playing", "playlist-read-private", "playlist-read-collaborative", "user-library-read",
            "user-library-modify", "user-read-private", "user-read-email", "user-read-recently-played", "user-top-read");
    private static final String WEB_SCOPES = String.join(" ", "user-read-playback-state", "user-modify-playback-state",
            "user-read-currently-playing", "playlist-read-private", "playlist-read-collaborative", "user-library-read",
            "user-library-modify", "user-read-private");

    public static final OAuthSession PLAYBACK = new OAuthSession("auth.json", () -> KEYMASTER_CLIENT_ID, SCOPES, () -> SonoraConfig.get().oauthPort, Sonora::onLogin);
    public static final OAuthSession WEB = new OAuthSession("web_auth.json", SpotifyAuth::webClientId, WEB_SCOPES, SpotifyAuth::webPort, Sonora::onWebLogin);

    public static int webPort() {
        int port = SonoraConfig.get().webOauthPort;
        return port > 0 && port < 65536 ? port : 43827;
    }

    public static String webRedirect() {
        return "http://127.0.0.1:" + webPort() + "/login";
    }

    private SpotifyAuth() {
    }

    public static String webClientId() {
        String configured = SonoraConfig.get().clientId;
        return configured == null ? "" : configured.trim();
    }

    public static void load() {
        PLAYBACK.load();
        WEB.load();
        if (WEB.loggedIn() && !WEB.tokenClientId().equals(webClientId())) {
            WEB.logout();
        }
    }

    public static OAuthSession.State state() {
        return PLAYBACK.state();
    }

    public static String message() {
        return PLAYBACK.message();
    }

    public static String pendingUrl() {
        return PLAYBACK.pendingUrl();
    }

    public static boolean loggedIn() {
        return PLAYBACK.loggedIn();
    }

    public static boolean ownClient() {
        return WEB.loggedIn() && !webClientId().isEmpty();
    }

    public static String displayName() {
        String name = WEB.displayName();
        return name.isBlank() ? PLAYBACK.displayName() : name;
    }

    public static String product() {
        String product = WEB.product();
        return product.isBlank() ? PLAYBACK.product() : product;
    }

    public static void startLogin() {
        PLAYBACK.startLogin();
    }

    public static void cancelLogin() {
        PLAYBACK.cancelLogin();
    }

    public static void logout() {
        PLAYBACK.logout();
        WEB.logout();
    }

    public static String accessToken() throws Exception {
        return PLAYBACK.accessToken();
    }

    public static String webToken() throws Exception {
        return ownClient() ? WEB.accessToken() : PLAYBACK.accessToken();
    }

    public static void invalidateWeb() {
        (ownClient() ? WEB : PLAYBACK).invalidate();
    }

    public static void refreshProfile() {
        if (displayName().isBlank()) {
            (ownClient() ? WEB : PLAYBACK).refreshProfile();
        }
    }
}
