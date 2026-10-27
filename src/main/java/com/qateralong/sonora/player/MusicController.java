package com.qateralong.sonora.player;

import com.qateralong.sonora.SonoraConfig;
import com.qateralong.sonora.audio.AudioEngine;
import com.qateralong.sonora.audio.AudioSource;
import com.qateralong.sonora.audio.FileSource;
import com.qateralong.sonora.audio.PcmStreamSource;
import com.qateralong.sonora.local.LocalLibrary;
import com.qateralong.sonora.spotify.Librespot;
import com.qateralong.sonora.spotify.SpotifyAuth;
import com.qateralong.sonora.spotify.SpotifyPlayer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class MusicController {
    public enum Mode { SPOTIFY, LOCAL }

    public record NowPlaying(TrackInfo track, boolean playing, long position, boolean shuffle, String repeat, Mode mode,
                             boolean liked, boolean inGame, String device, boolean loading) {
    }

    private final AudioEngine engine = new AudioEngine();
    private final SpotifyPlayer spotify = new SpotifyPlayer();
    private volatile Mode mode = Mode.SPOTIFY;
    private final List<LocalLibrary.LocalTrack> localQueue = new ArrayList<>();
    private final List<Integer> order = new ArrayList<>();
    private int orderIndex = -1;
    private boolean localShuffle;
    private String localRepeat = "off";
    private FileSource localSource;
    private TrackInfo localTrack;
    private final Random random = new Random();
    private volatile boolean loading;
    private volatile long loadingSince;
    private volatile String loadingFrom = "";
    private volatile long sleepAt;
    private volatile long sleepFadeStart;

    public void init() {
        SonoraConfig config = SonoraConfig.get();
        this.engine.setVolume(config.volume);
        this.engine.setBarCount(config.visualizerBars);
        this.applyEqualizer();
        this.engine.setDevice(config.outputDevice);
        this.mode = "local".equals(config.lastSource) ? Mode.LOCAL : Mode.SPOTIFY;
        Librespot.onLoaded(this::endLoading);
        this.spotify.start();
    }

    public AudioEngine engine() {
        return this.engine;
    }

    public SpotifyPlayer spotify() {
        return this.spotify;
    }

    public Mode mode() {
        return this.mode;
    }

    public void applyEqualizer() {
        SonoraConfig config = SonoraConfig.get();
        this.engine.equalizer().set(config.eqGains, config.eqPreamp, config.eqEnabled);
    }

    public void setVolume(float volume) {
        this.engine.setVolume(volume);
        SonoraConfig.get().volume = this.engine.volume();
    }

    public void onLibrespotStarted(PcmStreamSource source) {
        if (this.mode == Mode.SPOTIFY) {
            this.engine.play(source, null);
        }
    }

    private void switchMode(Mode next) {
        if (this.mode == next) {
            return;
        }
        this.mode = next;
        SonoraConfig.get().lastSource = next == Mode.LOCAL ? "local" : "spotify";
        SonoraConfig.save();
        if (next == Mode.LOCAL) {
            if (this.spotify.playing() && this.spotify.inGame()) {
                this.spotify.setPlaying(false);
            }
        } else {
            if (this.localSource != null) {
                this.engine.detach(this.localSource);
                this.localSource.close();
                this.localSource = null;
            }
            PcmStreamSource stream = Librespot.source();
            if (stream != null) {
                this.engine.play(stream, null);
            }
        }
    }

    public NowPlaying nowPlaying() {
        if (this.mode == Mode.LOCAL) {
            FileSource source = this.localSource;
            boolean playing = source != null && !this.engine.paused() && this.engine.source() == source;
            long position = source == null ? 0L : source.positionMillis();
            return new NowPlaying(this.localTrack, playing, position, this.localShuffle, this.localRepeat, Mode.LOCAL, false, true, "", false);
        }
        return new NowPlaying(this.spotify.track(), this.spotify.playing(), this.spotify.position(), this.spotify.shuffle(),
                this.spotify.repeat(), Mode.SPOTIFY, this.spotify.liked(), this.spotify.inGame(), this.spotify.deviceName(), this.loading);
    }

    public void togglePlay() {
        if (this.mode == Mode.LOCAL) {
            if (this.localSource == null) {
                if (!this.localQueue.isEmpty()) {
                    this.playLocalAt(Math.max(0, this.orderIndex));
                } else if (!LocalLibrary.tracks().isEmpty()) {
                    this.playLocal(LocalLibrary.tracks(), 0);
                }
                return;
            }
            this.engine.setPaused(!this.engine.paused());
            return;
        }
        if (!SpotifyAuth.loggedIn()) {
            return;
        }
        Librespot.ensureRunning();
        if (this.spotify.track() == null && !this.spotify.playing()) {
            this.spotify.transferHere(true);
            return;
        }
        this.engine.setPaused(false);
        this.spotify.setPlaying(!this.spotify.playing());
    }

    public boolean loading() {
        return this.loading;
    }

    private void beginLoading() {
        PcmStreamSource stream = Librespot.source();
        TrackInfo track = this.spotify.track();
        this.loadingFrom = track == null ? "" : track.id();
        this.loadingSince = System.currentTimeMillis();
        this.loading = true;
        if (stream != null) {
            stream.setDiscard(true);
            stream.flush();
        }
        this.engine.flushOutput();
    }

    private void endLoading() {
        if (!this.loading) {
            return;
        }
        PcmStreamSource stream = Librespot.source();
        if (stream != null) {
            stream.flush();
            stream.setDiscard(false);
        }
        this.engine.flushOutput();
        this.loading = false;
    }

    public void setSleepTimer(int minutes) {
        this.sleepAt = minutes <= 0 ? 0L : System.currentTimeMillis() + minutes * 60_000L;
        this.sleepFadeStart = 0L;
    }

    public long sleepRemainingMillis() {
        return this.sleepAt == 0L ? 0L : Math.max(0L, this.sleepAt - System.currentTimeMillis());
    }

    public void next() {
        if (this.mode == Mode.LOCAL) {
            this.advance(1, true);
        } else {
            this.beginLoading();
            this.spotify.next();
        }
    }

    public void previous() {
        if (this.mode == Mode.LOCAL) {
            if (this.localSource != null && this.localSource.positionMillis() > 3000L) {
                this.localSource.seek(0L);
            } else {
                this.advance(-1, true);
            }
        } else {
            this.beginLoading();
            this.spotify.previous();
        }
    }

    public void seek(long millis) {
        if (this.mode == Mode.LOCAL) {
            if (this.localSource != null) {
                this.localSource.seek(millis);
            }
        } else {
            PcmStreamSource stream = Librespot.source();
            if (stream != null) {
                stream.flush();
            }
            this.spotify.seek(millis);
        }
    }

    public void toggleShuffle() {
        if (this.mode == Mode.LOCAL) {
            this.localShuffle = !this.localShuffle;
            this.rebuildOrder(this.currentLocalIndex());
        } else {
            this.spotify.setShuffle(!this.spotify.shuffle());
        }
    }

    public void cycleRepeat() {
        if (this.mode == Mode.LOCAL) {
            this.localRepeat = switch (this.localRepeat) {
                case "off" -> "context";
                case "context" -> "track";
                default -> "off";
            };
        } else {
            String next = switch (this.spotify.repeat()) {
                case "off" -> "context";
                case "context" -> "track";
                default -> "off";
            };
            this.spotify.setRepeat(next);
        }
    }

    public void toggleLike() {
        if (this.mode == Mode.SPOTIFY) {
            this.spotify.setLiked(!this.spotify.liked());
        }
    }

    public void listenInGame() {
        if (!SpotifyAuth.loggedIn()) {
            return;
        }
        this.switchMode(Mode.SPOTIFY);
        Librespot.ensureRunning();
        this.spotify.transferHere(true);
    }

    public void playSpotify(BrowseItem item, List<BrowseItem> context, BrowseItem container) {
        if (!SpotifyAuth.loggedIn()) {
            return;
        }
        this.switchMode(Mode.SPOTIFY);
        Librespot.ensureRunning();
        this.engine.setPaused(false);
        PcmStreamSource stream = Librespot.source();
        if (stream != null) {
            stream.flush();
            this.engine.play(stream, null);
        }
        this.beginLoading();
        if (item.kind() == BrowseItem.Kind.PLAYLIST || item.kind() == BrowseItem.Kind.ALBUM) {
            this.spotify.playContext(item.uri(), null, 0);
            return;
        }
        if (container != null && (container.kind() == BrowseItem.Kind.PLAYLIST || container.kind() == BrowseItem.Kind.ALBUM)) {
            this.spotify.playContext(container.uri(), item.uri(), 0);
            return;
        }
        List<String> uris = new ArrayList<>();
        int index = 0;
        for (BrowseItem entry : context) {
            if (entry.kind() != BrowseItem.Kind.TRACK) {
                continue;
            }
            if (entry == item) {
                index = uris.size();
            }
            uris.add(entry.uri());
        }
        if (uris.isEmpty()) {
            uris.add(item.uri());
        }
        int start = Math.max(0, index - 200);
        this.spotify.playUris(uris.subList(start, Math.min(uris.size(), start + 500)), index - start);
    }

    public void queueSpotify(BrowseItem item) {
        if (item.kind() == BrowseItem.Kind.TRACK) {
            this.spotify.addToQueue(item.uri());
        }
    }

    public synchronized void playLocal(List<LocalLibrary.LocalTrack> tracks, int index) {
        this.switchMode(Mode.LOCAL);
        this.localQueue.clear();
        this.localQueue.addAll(tracks);
        this.rebuildOrder(index);
        this.playLocalAt(this.orderIndex);
    }

    public synchronized List<LocalLibrary.LocalTrack> localQueue() {
        List<LocalLibrary.LocalTrack> result = new ArrayList<>();
        for (int i = Math.max(0, this.orderIndex); i < this.order.size(); i++) {
            result.add(this.localQueue.get(this.order.get(i)));
        }
        return result;
    }

    private int currentLocalIndex() {
        return this.orderIndex >= 0 && this.orderIndex < this.order.size() ? this.order.get(this.orderIndex) : 0;
    }

    private void rebuildOrder(int current) {
        this.order.clear();
        for (int i = 0; i < this.localQueue.size(); i++) {
            this.order.add(i);
        }
        if (this.localShuffle && !this.order.isEmpty()) {
            this.order.remove(Integer.valueOf(current));
            Collections.shuffle(this.order, this.random);
            this.order.add(0, current);
            this.orderIndex = 0;
        } else {
            this.orderIndex = current;
        }
    }

    private synchronized void playLocalAt(int position) {
        if (position < 0 || position >= this.order.size()) {
            return;
        }
        this.orderIndex = position;
        LocalLibrary.LocalTrack track = this.localQueue.get(this.order.get(position));
        FileSource source = new FileSource(track.path(), 0L);
        this.localSource = source;
        this.localTrack = new TrackInfo("", "", track.meta().title(), track.meta().artist().isEmpty() ? List.of() : List.of(track.meta().artist()),
                track.meta().album(), null, track.meta().durationMillis(), track.path(), track.meta().artwork());
        this.engine.play(source, () -> this.onLocalEnd(source));
    }

    private synchronized void onLocalEnd(AudioSource finished) {
        if (finished != this.localSource) {
            return;
        }
        if ("track".equals(this.localRepeat)) {
            this.playLocalAt(this.orderIndex);
        } else {
            this.advance(1, false);
        }
    }

    private synchronized void advance(int step, boolean manual) {
        if (this.order.isEmpty()) {
            return;
        }
        int next = this.orderIndex + step;
        if (next >= this.order.size()) {
            if (!"off".equals(this.localRepeat) || manual) {
                if (this.localShuffle) {
                    Collections.shuffle(this.order, this.random);
                }
                next = 0;
            } else {
                this.localSource = null;
                return;
            }
        }
        if (next < 0) {
            next = this.order.size() - 1;
        }
        this.playLocalAt(next);
    }

    private void tickLoading() {
        if (!this.loading) {
            return;
        }
        TrackInfo track = this.spotify.track();
        long elapsed = System.currentTimeMillis() - this.loadingSince;
        boolean changed = track != null && !track.id().equals(this.loadingFrom);
        if (elapsed > 6000L || (changed && elapsed > 1500L) || !Librespot.running()) {
            this.endLoading();
        }
    }

    private void tickSleep() {
        if (this.sleepAt == 0L) {
            return;
        }
        long left = this.sleepAt - System.currentTimeMillis();
        if (left <= 0L) {
            this.sleepAt = 0L;
            this.engine.setFade(1.0F);
            if (this.mode == Mode.LOCAL) {
                this.engine.setPaused(true);
            } else if (this.spotify.playing()) {
                this.spotify.setPlaying(false);
            }
        } else {
            this.engine.setFade(left < 10_000L ? left / 10_000.0F : 1.0F);
        }
    }

    public void tick() {
        this.tickLoading();
        this.tickSleep();
        if (this.mode == Mode.SPOTIFY && SpotifyAuth.loggedIn()) {
            PcmStreamSource stream = Librespot.source();
            if (stream != null && this.engine.source() != stream) {
                this.engine.play(stream, null);
            }
        }
    }
}
