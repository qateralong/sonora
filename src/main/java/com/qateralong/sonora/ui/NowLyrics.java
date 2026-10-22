package com.qateralong.sonora.ui;

import com.qateralong.sonora.SonoraConfig;
import com.qateralong.sonora.lyrics.Lyrics;
import com.qateralong.sonora.player.TrackInfo;
import java.util.concurrent.CompletableFuture;

public final class NowLyrics {
    private static String key;
    private static CompletableFuture<Lyrics.Result> future;

    private NowLyrics() {
    }

    public static Lyrics.Result get(TrackInfo track) {
        if (track == null || !SonoraConfig.get().showLyrics) {
            return null;
        }
        String current = track.title() + "|" + track.artistLine();
        if (!current.equals(key)) {
            key = current;
            future = Lyrics.get(track.title(), track.artists().isEmpty() ? "" : track.artists().get(0), track.album(), track.durationMs());
        }
        return future != null && future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
    }

    public static boolean loading() {
        return future != null && !future.isDone();
    }
}
