package com.qateralong.sonora.spotify;

import com.qateralong.sonora.Sonora;
import java.util.regex.Pattern;

public final class Connectivity {
    private static final Pattern BLOCKED = Pattern.compile(
            "(?i)country|region|not available|unavailable in|timed out|timeout|unknownhost|unable to connect|connection (refused|reset)|network is unreachable|handshake|ssl|connect(ion)? failed|no route to host|access point");

    private Connectivity() {
    }

    public static boolean blocked(String message) {
        return message != null && !message.isBlank() && BLOCKED.matcher(message).find();
    }

    public static boolean suspected() {
        return blocked(Sonora.controller().spotify().error()) || blocked(Librespot.problem()) || blocked(SpotifyAuth.message());
    }

    public static void retry() {
        Sonora.controller().spotify().clearError();
        Sonora.controller().spotify().pollSoon();
        Librespot.restart();
    }
}
