package com.qateralong.sonora.player;

import com.qateralong.sonora.local.LocalLibrary;

public record BrowseItem(Kind kind, String id, String uri, String title, String subtitle, String artUrl, long durationMs,
                         TrackInfo track, LocalLibrary.LocalTrack local) {
    public enum Kind { TRACK, PLAYLIST, ALBUM, LIKED, LOCAL, HEADER }

    public static BrowseItem header(String title) {
        return new BrowseItem(Kind.HEADER, "", "", title, "", null, 0L, null, null);
    }

    public String artKey() {
        if (this.local != null) {
            return this.local.meta().artwork() == null ? null : "local:" + this.local.path().toAbsolutePath();
        }
        return this.artUrl;
    }
}
