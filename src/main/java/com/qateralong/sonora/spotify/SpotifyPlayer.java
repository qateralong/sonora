package com.qateralong.sonora.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.SonoraConfig;
import com.qateralong.sonora.player.BrowseItem;
import com.qateralong.sonora.player.TrackInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SpotifyPlayer {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Sonora Spotify");
        thread.setDaemon(true);
        return thread;
    });
    private volatile TrackInfo track;
    private volatile boolean playing;
    private volatile long progress;
    private volatile long stamp;
    private volatile boolean shuffle;
    private volatile String repeat = "off";
    private volatile String deviceId = "";
    private volatile String deviceName = "";
    private volatile String ourDeviceId = "";
    private volatile boolean liked;
    private volatile String error = "";
    private volatile long nextPoll;
    private volatile boolean fastPolling;
    private volatile long holdUntil;

    public void start() {
        Thread poller = new Thread(this::pollLoop, "Sonora Spotify poller");
        poller.setDaemon(true);
        poller.start();
    }

    public void setFastPolling(boolean fast) {
        this.fastPolling = fast;
    }

    private void pollLoop() {
        while (true) {
            try {
                Thread.sleep(200);
                if (!SpotifyAuth.loggedIn() || System.currentTimeMillis() < this.nextPoll) {
                    continue;
                }
                this.poll();
                long delay = this.fastPolling || this.playing ? 1000L : 5000L;
                this.nextPoll = System.currentTimeMillis() + delay;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                this.error = e.getMessage() == null ? e.toString() : e.getMessage();
                this.nextPoll = System.currentTimeMillis() + 5000L;
            }
        }
    }

    public void pollSoon() {
        this.nextPoll = System.currentTimeMillis() + 250L;
    }

    private void poll() throws Exception {
        if (this.ourDeviceId.isEmpty()) {
            this.findOurDevice();
        }
        JsonObject state = SpotifyApi.get("/me/player?additional_types=track,episode");
        this.error = "";
        if (System.currentTimeMillis() < this.holdUntil) {
            return;
        }
        if (state == null) {
            this.playing = false;
            this.deviceId = "";
            this.deviceName = "";
            return;
        }
        TrackInfo next = state.has("item") && state.get("item").isJsonObject() ? TrackInfo.fromSpotify(state.getAsJsonObject("item")) : null;
        boolean changed = next != null && (this.track == null || !next.id().equals(this.track.id()));
        this.track = next;
        this.playing = state.has("is_playing") && state.get("is_playing").getAsBoolean();
        this.progress = state.has("progress_ms") && !state.get("progress_ms").isJsonNull() ? state.get("progress_ms").getAsLong() : 0L;
        this.stamp = System.currentTimeMillis();
        this.shuffle = state.has("shuffle_state") && state.get("shuffle_state").getAsBoolean();
        this.repeat = TrackInfo.string(state, "repeat_state");
        if (state.has("device") && state.get("device").isJsonObject()) {
            JsonObject device = state.getAsJsonObject("device");
            this.deviceId = TrackInfo.string(device, "id");
            this.deviceName = TrackInfo.string(device, "name");
        }
        if (changed && !next.id().isEmpty()) {
            this.refreshLiked(next.id());
        }
    }

    private void refreshLiked(String id) {
        try {
            JsonArray array = getArray("/me/tracks/contains?ids=" + id);
            this.liked = array != null && !array.isEmpty() && array.get(0).getAsBoolean();
        } catch (Exception ignored) {
            this.liked = false;
        }
    }

    private static JsonArray getArray(String path) throws Exception {
        String token = SpotifyAuth.accessToken();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.spotify.com/v1" + path))
                .header("Authorization", "Bearer " + token).GET().build();
        var response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return com.google.gson.JsonParser.parseString(response.body()).getAsJsonArray();
    }

    private void findOurDevice() throws Exception {
        JsonObject devices = SpotifyApi.get("/me/player/devices");
        if (devices == null || !devices.has("devices")) {
            return;
        }
        for (JsonElement element : devices.getAsJsonArray("devices")) {
            JsonObject device = element.getAsJsonObject();
            if (SonoraConfig.get().deviceName.equals(TrackInfo.string(device, "name"))) {
                this.ourDeviceId = TrackInfo.string(device, "id");
            }
        }
    }

    public void forgetDevice() {
        this.ourDeviceId = "";
    }

    public TrackInfo track() {
        return this.track;
    }

    public boolean playing() {
        return this.playing;
    }

    public long position() {
        long value = this.playing ? this.progress + (System.currentTimeMillis() - this.stamp) : this.progress;
        TrackInfo current = this.track;
        return current == null ? value : Math.min(value, current.durationMs());
    }

    public boolean shuffle() {
        return this.shuffle;
    }

    public String repeat() {
        return this.repeat;
    }

    public boolean liked() {
        return this.liked;
    }

    public String deviceName() {
        return this.deviceName;
    }

    public boolean inGame() {
        return !this.ourDeviceId.isEmpty() && this.ourDeviceId.equals(this.deviceId);
    }

    public boolean hasDevice() {
        return !this.ourDeviceId.isEmpty();
    }

    public String error() {
        return this.error;
    }

    private void hold() {
        this.holdUntil = System.currentTimeMillis() + 1200L;
        this.nextPoll = this.holdUntil + 50L;
    }

    private CompletableFuture<Void> run(ThrowingRunnable action) {
        return CompletableFuture.runAsync(() -> {
            try {
                action.run();
                this.error = "";
            } catch (Exception e) {
                this.error = e.getMessage();
                Sonora.LOGGER.warn("Spotify command failed: {}", e.getMessage());
            }
            this.pollSoon();
        }, this.executor);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private String deviceQuery() {
        return this.deviceId.isEmpty() ? (this.ourDeviceId.isEmpty() ? "" : "?device_id=" + this.ourDeviceId) : "";
    }

    public CompletableFuture<Void> setPlaying(boolean play) {
        this.playing = play;
        this.progress = this.position();
        this.stamp = System.currentTimeMillis();
        this.hold();
        return this.run(() -> SpotifyApi.put(play ? "/me/player/play" + this.deviceQuery() : "/me/player/pause", null));
    }

    public CompletableFuture<Void> next() {
        this.hold();
        return this.run(() -> SpotifyApi.post("/me/player/next", null));
    }

    public CompletableFuture<Void> previous() {
        this.hold();
        return this.run(() -> SpotifyApi.post("/me/player/previous", null));
    }

    public CompletableFuture<Void> seek(long millis) {
        this.progress = millis;
        this.stamp = System.currentTimeMillis();
        this.hold();
        return this.run(() -> SpotifyApi.put("/me/player/seek?position_ms=" + millis, null));
    }

    public CompletableFuture<Void> setShuffle(boolean value) {
        this.shuffle = value;
        this.hold();
        return this.run(() -> SpotifyApi.put("/me/player/shuffle?state=" + value, null));
    }

    public CompletableFuture<Void> setRepeat(String mode) {
        this.repeat = mode;
        this.hold();
        return this.run(() -> SpotifyApi.put("/me/player/repeat?state=" + mode, null));
    }

    public CompletableFuture<Void> setLiked(boolean value) {
        TrackInfo current = this.track;
        if (current == null || current.id().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        this.liked = value;
        return this.run(() -> {
            JsonObject body = new JsonObject();
            JsonArray ids = new JsonArray();
            ids.add(current.id());
            body.add("ids", ids);
            if (value) {
                SpotifyApi.put("/me/tracks", body);
            } else {
                SpotifyApi.delete("/me/tracks", body);
            }
        });
    }

    public CompletableFuture<Void> transferHere(boolean play) {
        return this.run(() -> {
            if (this.ourDeviceId.isEmpty()) {
                for (int i = 0; i < 10 && this.ourDeviceId.isEmpty(); i++) {
                    this.findOurDevice();
                    if (this.ourDeviceId.isEmpty()) {
                        Thread.sleep(1000);
                    }
                }
            }
            if (this.ourDeviceId.isEmpty()) {
                throw new IllegalStateException(Sonora.text("sonora.error.no_device"));
            }
            JsonObject body = new JsonObject();
            JsonArray ids = new JsonArray();
            ids.add(this.ourDeviceId);
            body.add("device_ids", ids);
            body.addProperty("play", play);
            SpotifyApi.put("/me/player", body);
        });
    }

    public CompletableFuture<Void> playContext(String contextUri, String trackUri, int index) {
        this.hold();
        return this.run(() -> {
            this.ensureDeviceKnown();
            JsonObject body = new JsonObject();
            body.addProperty("context_uri", contextUri);
            JsonObject offset = new JsonObject();
            if (trackUri != null && !trackUri.isEmpty()) {
                offset.addProperty("uri", trackUri);
            } else {
                offset.addProperty("position", Math.max(0, index));
            }
            body.add("offset", offset);
            SpotifyApi.put("/me/player/play?device_id=" + this.ourDeviceId, body);
        });
    }

    public CompletableFuture<Void> playUris(List<String> uris, int index) {
        this.hold();
        return this.run(() -> {
            this.ensureDeviceKnown();
            JsonObject body = new JsonObject();
            JsonArray array = new JsonArray();
            uris.stream().limit(500).forEach(array::add);
            body.add("uris", array);
            JsonObject offset = new JsonObject();
            offset.addProperty("position", Math.max(0, Math.min(index, array.size() - 1)));
            body.add("offset", offset);
            SpotifyApi.put("/me/player/play?device_id=" + this.ourDeviceId, body);
        });
    }

    public CompletableFuture<Void> addToQueue(String uri) {
        return this.run(() -> SpotifyApi.post("/me/player/queue?uri=" + SpotifyApi.enc(uri) + (this.ourDeviceId.isEmpty() ? "" : "&device_id=" + this.ourDeviceId), null));
    }

    private void ensureDeviceKnown() throws Exception {
        for (int i = 0; i < 10 && this.ourDeviceId.isEmpty(); i++) {
            this.findOurDevice();
            if (this.ourDeviceId.isEmpty()) {
                Thread.sleep(1000);
            }
        }
        if (this.ourDeviceId.isEmpty()) {
            throw new IllegalStateException(Sonora.text("sonora.error.no_device"));
        }
    }

    public CompletableFuture<List<BrowseItem>> playlists() {
        return CompletableFuture.supplyAsync(() -> {
            List<BrowseItem> items = new ArrayList<>();
            items.add(new BrowseItem(BrowseItem.Kind.LIKED, "liked", "", Sonora.text("sonora.browse.liked"), "", null, 0L, null, null));
            try {
                String next = "/me/playlists?limit=50";
                int pages = 0;
                while (next != null && pages++ < 6) {
                    JsonObject page = SpotifyApi.get(next);
                    if (page == null) {
                        break;
                    }
                    for (JsonElement element : page.getAsJsonArray("items")) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        JsonObject playlist = element.getAsJsonObject();
                        String owner = playlist.has("owner") ? TrackInfo.string(playlist.getAsJsonObject("owner"), "display_name") : "";
                        int total = 0;
                        for (String key : new String[]{"tracks", "items"}) {
                            if (playlist.has(key) && playlist.get(key).isJsonObject() && playlist.getAsJsonObject(key).has("total")) {
                                total = playlist.getAsJsonObject(key).get("total").getAsInt();
                            }
                        }
                        items.add(new BrowseItem(BrowseItem.Kind.PLAYLIST, TrackInfo.string(playlist, "id"), TrackInfo.string(playlist, "uri"),
                                TrackInfo.string(playlist, "name"), owner + " · " + total, TrackInfo.bestImage(playlist.getAsJsonArray("images"), 64), 0L, null, null));
                    }
                    next = page.has("next") && !page.get("next").isJsonNull() ? page.get("next").getAsString() : null;
                }
            } catch (Exception e) {
                this.error = e.getMessage();
            }
            return items;
        }, this.executor);
    }

    public CompletableFuture<List<BrowseItem>> tracks(BrowseItem container) {
        return CompletableFuture.supplyAsync(() -> {
            List<BrowseItem> items = new ArrayList<>();
            try {
                String next = switch (container.kind()) {
                    case LIKED -> "/me/tracks?limit=50";
                    case ALBUM -> "/albums/" + container.id() + "/tracks?limit=50";
                    default -> "/playlists/" + container.id() + "/tracks?limit=100";
                };
                int pages = 0;
                while (next != null && pages++ < 20) {
                    JsonObject page;
                    try {
                        page = SpotifyApi.get(next);
                    } catch (SpotifyApi.SpotifyException e) {
                        if (pages == 1 && container.kind() == BrowseItem.Kind.PLAYLIST && (e.status == 403 || e.status == 404)) {
                            next = "/playlists/" + container.id() + "/items?limit=100";
                            page = SpotifyApi.get(next);
                        } else {
                            throw e;
                        }
                    }
                    if (page == null) {
                        break;
                    }
                    for (JsonElement element : page.getAsJsonArray("items")) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        JsonObject entry = element.getAsJsonObject();
                        JsonObject trackJson = entry.has("track") && entry.get("track").isJsonObject() ? entry.getAsJsonObject("track")
                                : entry.has("item") && entry.get("item").isJsonObject() ? entry.getAsJsonObject("item") : entry;
                        TrackInfo track = TrackInfo.fromSpotify(trackJson);
                        if (track == null || track.uri().isEmpty() || track.uri().startsWith("spotify:local")) {
                            continue;
                        }
                        String art = track.artUrl() != null ? track.artUrl() : container.artUrl();
                        items.add(new BrowseItem(BrowseItem.Kind.TRACK, track.id(), track.uri(), track.title(), track.artistLine(), art, track.durationMs(), track, null));
                    }
                    next = page.has("next") && !page.get("next").isJsonNull() ? page.get("next").getAsString() : null;
                }
            } catch (Exception e) {
                this.error = e.getMessage();
            }
            return items;
        }, this.executor);
    }

    public CompletableFuture<List<BrowseItem>> search(String query) {
        return CompletableFuture.supplyAsync(() -> {
            List<BrowseItem> items = new ArrayList<>();
            try {
                JsonObject result = SpotifyApi.get("/search?type=track,playlist,album&limit=20&q=" + SpotifyApi.enc(query));
                if (result == null) {
                    return items;
                }
                if (result.has("tracks")) {
                    items.add(BrowseItem.header(Sonora.text("sonora.browse.tracks")));
                    for (JsonElement element : result.getAsJsonObject("tracks").getAsJsonArray("items")) {
                        if (element.isJsonObject()) {
                            TrackInfo track = TrackInfo.fromSpotify(element.getAsJsonObject());
                            items.add(new BrowseItem(BrowseItem.Kind.TRACK, track.id(), track.uri(), track.title(), track.artistLine(), track.artUrl(), track.durationMs(), track, null));
                        }
                    }
                }
                if (result.has("albums")) {
                    items.add(BrowseItem.header(Sonora.text("sonora.browse.albums")));
                    for (JsonElement element : result.getAsJsonObject("albums").getAsJsonArray("items")) {
                        if (element.isJsonObject()) {
                            JsonObject album = element.getAsJsonObject();
                            String artists = album.has("artists") ? album.getAsJsonArray("artists").asList().stream()
                                    .map(a -> TrackInfo.string(a.getAsJsonObject(), "name")).reduce((a, b) -> a + ", " + b).orElse("") : "";
                            items.add(new BrowseItem(BrowseItem.Kind.ALBUM, TrackInfo.string(album, "id"), TrackInfo.string(album, "uri"),
                                    TrackInfo.string(album, "name"), artists, TrackInfo.bestImage(album.getAsJsonArray("images"), 64), 0L, null, null));
                        }
                    }
                }
                if (result.has("playlists")) {
                    items.add(BrowseItem.header(Sonora.text("sonora.browse.playlists")));
                    for (JsonElement element : result.getAsJsonObject("playlists").getAsJsonArray("items")) {
                        if (element.isJsonObject()) {
                            JsonObject playlist = element.getAsJsonObject();
                            String owner = playlist.has("owner") ? TrackInfo.string(playlist.getAsJsonObject("owner"), "display_name") : "";
                            items.add(new BrowseItem(BrowseItem.Kind.PLAYLIST, TrackInfo.string(playlist, "id"), TrackInfo.string(playlist, "uri"),
                                    TrackInfo.string(playlist, "name"), owner, TrackInfo.bestImage(playlist.getAsJsonArray("images"), 64), 0L, null, null));
                        }
                    }
                }
            } catch (Exception e) {
                this.error = e.getMessage();
            }
            return items;
        }, this.executor);
    }

    public CompletableFuture<List<BrowseItem>> queue() {
        return CompletableFuture.supplyAsync(() -> {
            List<BrowseItem> items = new ArrayList<>();
            try {
                JsonObject result = SpotifyApi.get("/me/player/queue");
                if (result != null && result.has("queue")) {
                    for (JsonElement element : result.getAsJsonArray("queue")) {
                        if (element.isJsonObject()) {
                            TrackInfo track = TrackInfo.fromSpotify(element.getAsJsonObject());
                            items.add(new BrowseItem(BrowseItem.Kind.TRACK, track.id(), track.uri(), track.title(), track.artistLine(), track.artUrl(), track.durationMs(), track, null));
                        }
                    }
                }
            } catch (Exception e) {
                this.error = e.getMessage();
            }
            return items;
        }, this.executor);
    }
}
