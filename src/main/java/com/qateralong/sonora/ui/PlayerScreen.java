package com.qateralong.sonora.ui;

import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.SonoraConfig;
import com.qateralong.sonora.audio.Equalizer;
import com.qateralong.sonora.local.LocalLibrary;
import com.qateralong.sonora.lyrics.Lyrics;
import com.qateralong.sonora.player.BrowseItem;
import com.qateralong.sonora.player.MusicController;
import com.qateralong.sonora.player.TrackInfo;
import com.qateralong.sonora.spotify.Librespot;
import com.qateralong.sonora.spotify.SpotifyAuth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

public class PlayerScreen extends Screen {
    public enum Tab {
        NOW(Draw.Icon.DISC), LIBRARY(Draw.Icon.LIBRARY), SEARCH(Draw.Icon.SEARCH), QUEUE(Draw.Icon.QUEUE),
        LOCAL(Draw.Icon.FOLDER), EQ(Draw.Icon.EQ), SETTINGS(Draw.Icon.SETTINGS);

        final Draw.Icon icon;

        Tab(Draw.Icon icon) {
            this.icon = icon;
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable left, Runnable right) {
    }

    private record ListState(String title, BrowseItem container, List<BrowseItem> items, double scroll) {
    }

    private static Tab lastTab = Tab.NOW;
    private final Screen parent;
    private Tab tab = lastTab;
    private final List<Hit> hits = new ArrayList<>();
    private final Deque<ListState> history = new ArrayDeque<>();
    private List<BrowseItem> items = List.of();
    private String listTitle = "";
    private BrowseItem container;
    private double scroll;
    private boolean loading;
    private String listError = "";
    private EditBox searchBox;
    private String lastSearch = "";
    private int drag = -1;
    private float dragValue;
    private long toastUntil;
    private String toast = "";
    private float lyricScroll;
    private double plainLyricScroll;
    private int sideW;
    private int barH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private static final int ROW = 30;
    private static final int DRAG_SEEK = 100;
    private static final int DRAG_VOLUME = 101;
    private static final int DRAG_PREAMP = 102;

    public PlayerScreen(Screen parent) {
        super(Component.literal("Sonora"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.sideW = Math.max(110, Math.min(150, this.width / 6));
        this.barH = 62;
        if (this.tab == Tab.SEARCH) {
            int x = this.sideW + 16;
            this.searchBox = new EditBox(this.font, x + 22, 18, Math.min(360, this.width - x - 40), 16, Component.translatable("sonora.search.hint"));
            this.searchBox.setHint(Component.translatable("sonora.search.hint"));
            this.searchBox.setValue(this.lastSearch);
            this.searchBox.setBordered(false);
            this.addRenderableWidget(this.searchBox);
            this.setInitialFocus(this.searchBox);
        } else {
            this.searchBox = null;
        }
        if (this.items.isEmpty() && !this.loading) {
            this.loadTab();
        }
    }

    public void switchTab(Tab next) {
        this.tab = next;
        lastTab = next;
        this.items = List.of();
        this.history.clear();
        this.container = null;
        this.scroll = 0;
        this.loading = false;
        this.rebuildWidgets();
    }

    private void loadTab() {
        MusicController controller = Sonora.controller();
        switch (this.tab) {
            case LIBRARY -> {
                this.listTitle = Sonora.text("sonora.tab.library");
                if (SpotifyAuth.loggedIn()) {
                    this.load(controller.spotify().playlists());
                }
            }
            case QUEUE -> {
                this.listTitle = Sonora.text("sonora.tab.queue");
                if (controller.mode() == MusicController.Mode.LOCAL) {
                    List<BrowseItem> list = new ArrayList<>();
                    for (LocalLibrary.LocalTrack track : controller.localQueue()) {
                        list.add(localItem(track));
                    }
                    this.items = list;
                } else if (SpotifyAuth.loggedIn()) {
                    this.load(controller.spotify().queue());
                }
            }
            case LOCAL -> {
                this.listTitle = Sonora.text("sonora.tab.local");
                List<BrowseItem> list = new ArrayList<>();
                for (LocalLibrary.LocalTrack track : LocalLibrary.tracks()) {
                    list.add(localItem(track));
                }
                this.items = list;
            }
            default -> {
            }
        }
    }

    private static BrowseItem localItem(LocalLibrary.LocalTrack track) {
        return new BrowseItem(BrowseItem.Kind.LOCAL, track.key(), "", track.meta().title(),
                track.meta().artist().isEmpty() ? track.meta().album() : track.meta().artist(), null, track.meta().durationMillis(), null, track);
    }

    private void load(CompletableFuture<List<BrowseItem>> future) {
        this.loading = true;
        this.listError = "";
        Sonora.controller().spotify().clearError();
        future.thenAcceptAsync(result -> {
            this.items = result;
            this.loading = false;
            this.listError = Sonora.controller().spotify().error();
        }, this.minecraft);
    }

    private static String accountName() {
        return SpotifyAuth.displayName().isBlank() ? "Spotify" : SpotifyAuth.displayName();
    }

    private void openContainer(BrowseItem item) {
        this.history.push(new ListState(this.listTitle, this.container, this.items, this.scroll));
        this.container = item;
        this.listTitle = item.title();
        this.items = List.of();
        this.scroll = 0;
        this.load(Sonora.controller().spotify().tracks(item));
    }

    private void back() {
        if (this.history.isEmpty()) {
            return;
        }
        ListState state = this.history.pop();
        this.listTitle = state.title();
        this.container = state.container();
        this.items = state.items();
        this.scroll = state.scroll();
        this.loading = false;
    }

    private void showToast(String text) {
        this.toast = text;
        this.toastUntil = Util.getMillis() + 2500L;
    }

    @Override
    public void tick() {
        if (this.tab == Tab.LOCAL && this.items.size() != LocalLibrary.tracks().size() && !LocalLibrary.scanning()) {
            this.loadTab();
        }
    }

    private void hit(int x, int y, int w, int h, Runnable left) {
        this.hits.add(new Hit(x, y, w, h, left, null));
    }

    private void hit(int x, int y, int w, int h, Runnable left, Runnable right) {
        this.hits.add(new Hit(x, y, w, h, left, right));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int dark = MiniPlayer.dark();
        g.fillGradient(0, 0, this.width, this.height, Colors.argb(0xE8, Colors.mix(dark, 0x000000, 0.1F)), 0xF4060608);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.hits.clear();
        MusicController controller = Sonora.controller();
        MusicController.NowPlaying now = controller.nowPlaying();
        Artwork.Entry art = MiniPlayer.artwork(now.track());
        MiniPlayer.updateAccent(art);
        super.render(g, mouseX, mouseY, partialTick);
        this.renderSidebar(g, mouseX, mouseY);
        int mx = this.sideW + 16;
        int my = 12;
        int mw = this.width - mx - 16;
        int mh = this.height - this.barH - my - 8;
        switch (this.tab) {
            case NOW -> this.renderNow(g, now, art, mx, my, mw, mh, mouseX, mouseY);
            case EQ -> this.renderEqualizer(g, mx, my, mw, mh, mouseX, mouseY);
            case SETTINGS -> this.renderSettings(g, mx, my, mw, mh, mouseX, mouseY);
            default -> this.renderList(g, mx, my, mw, mh, mouseX, mouseY);
        }
        this.renderBar(g, now, art, mouseX, mouseY);
        if (Util.getMillis() < this.toastUntil) {
            int w = this.font.width(this.toast) + 20;
            Draw.rounded(g, this.width / 2 - w / 2, this.height - this.barH - 30, w, 20, 10, 0xF0FFFFFF);
            g.drawCenteredString(this.font, this.toast, this.width / 2, this.height - this.barH - 24, 0xFF000000);
        }
        if (this.searchBox != null) {
            this.searchBox.render(g, mouseX, mouseY, partialTick);
        }
    }

    private void renderSidebar(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(0, 0, this.sideW, this.height - this.barH, 0x90000000);
        int accent = MiniPlayer.accent();
        Draw.icon(g, Draw.Icon.LOGO, 12, 12, 18, Colors.argb(0xFF, accent));
        Draw.scaledText(g, this.font, "Sonora", 36, 13, 1.5F, 0xFFFFFFFF, true);
        int y = 44;
        int count = Tab.values().length;
        int available = this.height - this.barH;
        boolean account = 44 + count * 20 <= available - 52;
        int step = Mth.clamp((available - 48 - (account ? 52 : 0)) / count, 16, 26);
        int rowH = Math.min(22, step - 2);
        for (Tab entry : Tab.values()) {
            boolean selected = entry == this.tab;
            boolean hover = Draw.inside(mouseX, mouseY, 6, y, this.sideW - 12, rowH);
            if (selected || hover) {
                Draw.rounded(g, 6, y, this.sideW - 12, rowH, 5, selected ? 0x30FFFFFF : 0x18FFFFFF);
            }
            Draw.icon(g, entry.icon, 14, y + (rowH - 14) / 2, 14, selected ? Colors.argb(0xFF, accent) : 0xFFB3B3B3);
            g.drawString(this.font, Sonora.text("sonora.tab." + entry.name().toLowerCase()), 34, y + (rowH - 8) / 2, selected ? 0xFFFFFFFF : 0xFFB3B3B3, false);
            Tab target = entry;
            this.hit(6, y, this.sideW - 12, rowH, () -> this.switchTab(target));
            y += step;
        }
        if (!account) {
            return;
        }
        int bottom = this.height - this.barH - 46;
        g.fill(10, bottom - 6, this.sideW - 10, bottom - 5, 0x20FFFFFF);
        if (SpotifyAuth.loggedIn()) {
            g.drawString(this.font, Draw.ellipsize(this.font, accountName(), this.sideW - 20), 12, bottom, 0xFFFFFFFF, false);
            String status = Librespot.running() ? Sonora.text("sonora.status.ready") : Librespot.status();
            Draw.scaledText(g, this.font, Draw.ellipsize(this.font, status, (int) ((this.sideW - 20) / 0.75F)), 12, bottom + 12, 0.75F, Librespot.running() ? 0xFF1ED760 : 0xFFFFA060, false);
        } else {
            boolean hover = Draw.inside(mouseX, mouseY, 10, bottom - 2, this.sideW - 20, 22);
            Draw.rounded(g, 10, bottom - 2, this.sideW - 20, 22, 11, hover ? 0xFF1ED760 : 0xFF1DB954);
            g.drawCenteredString(this.font, Sonora.text("sonora.button.login"), this.sideW / 2, bottom + 5, 0xFF000000);
            this.hit(10, bottom - 2, this.sideW - 20, 22, () -> this.minecraft.setScreen(new LoginScreen(this)));
        }
    }

    private void renderNow(GuiGraphics g, MusicController.NowPlaying now, Artwork.Entry art, int mx, int my, int mw, int mh, int mouseX, int mouseY) {
        TrackInfo track = now.track();
        int accent = MiniPlayer.accent();
        float[] bars = Visualizer.resample(Sonora.controller().engine().spectrum().bars(), Math.max(16, SonoraConfig.get().visualizerBars));
        int vizH = Math.min(90, mh / 4);
        Visualizer.draw(g, bars, mx, my + mh - vizH, mw, vizH, accent, SonoraConfig.get().visualizerStyle, 0x90);
        if (track == null) {
            String message = now.mode() == MusicController.Mode.SPOTIFY && !SpotifyAuth.loggedIn()
                    ? Sonora.text("sonora.now.login_hint") : Sonora.text("sonora.now.nothing");
            List<FormattedCharSequence> lines = this.font.split(Component.literal(message), Math.min(320, mw - 20));
            for (int i = 0; i < lines.size(); i++) {
                g.drawCenteredString(this.font, lines.get(i), mx + mw / 2, my + mh / 3 + i * 11, 0xFFB3B3B3);
            }
            return;
        }
        int artSize = Math.max(64, Math.min(Math.min(mh - vizH - 70, (int) (mw * 0.42F)), 240));
        int ax = mx + 8;
        int ay = my + 8;
        Draw.rounded(g, ax + 3, ay + 4, artSize, artSize, 10, 0x60000000);
        Draw.art(g, art, ax, ay, artSize, 8, 0x00000000);
        int ty = ay + artSize + 10;
        g.pose().pushPose();
        g.pose().translate(ax, ty, 0.0F);
        g.pose().scale(1.6F, 1.6F, 1.0F);
        Draw.marquee(g, this.font, track.title(), 0, 0, (int) (artSize / 1.6F), 0xFFFFFFFF, true);
        g.pose().popPose();
        g.drawString(this.font, Draw.ellipsize(this.font, track.artistLine(), artSize), ax, ty + 17, Colors.argb(0xFF, Colors.mix(accent, 0xFFFFFF, 0.5F)), false);
        if (!track.album().isEmpty()) {
            g.drawString(this.font, Draw.ellipsize(this.font, track.album(), artSize), ax, ty + 29, 0xFF8A8A8A, false);
        }
        if (now.mode() == MusicController.Mode.SPOTIFY && !now.inGame() && !now.device().isEmpty()) {
            String text = Sonora.text("sonora.now.other_device", now.device());
            int bw = Math.min(artSize, this.font.width(text) + 16);
            int by = ty + 42;
            boolean hover = Draw.inside(mouseX, mouseY, ax, by, bw, 18);
            Draw.rounded(g, ax, by, bw, 18, 9, hover ? Colors.argb(0xFF, accent) : 0x40FFFFFF);
            g.drawString(this.font, Draw.ellipsize(this.font, text, bw - 12), ax + 8, by + 5, hover ? 0xFF000000 : 0xFFFFFFFF, false);
            this.hit(ax, by, bw, 18, () -> Sonora.controller().listenInGame());
        }
        int lx = ax + artSize + 24;
        int lw = mx + mw - lx - 8;
        int lh = mh - vizH - 16;
        if (lw > 80) {
            this.renderLyrics(g, track, now.position(), lx, my + 8, lw, lh, accent);
        }
    }

    private void renderLyrics(GuiGraphics g, TrackInfo track, long position, int x, int y, int w, int h, int accent) {
        if (!SonoraConfig.get().lyrics) {
            return;
        }
        Lyrics.Result lyrics = NowLyrics.get(track);
        Draw.icon(g, Draw.Icon.LYRICS, x, y, 12, 0x80FFFFFF);
        g.drawString(this.font, Sonora.text("sonora.lyrics.title"), x + 16, y + 2, 0x80FFFFFF, false);
        int top = y + 18;
        int height = h - 18;
        if (lyrics == null) {
            g.drawString(this.font, Sonora.text(NowLyrics.loading() ? "sonora.lyrics.loading" : "sonora.lyrics.none"), x, top + 8, 0xFF7A7A7A, false);
            return;
        }
        if (lyrics.instrumental()) {
            g.drawString(this.font, Sonora.text("sonora.lyrics.instrumental"), x, top + 8, 0xFF7A7A7A, false);
            return;
        }
        if (lyrics.lines().isEmpty()) {
            g.drawString(this.font, Sonora.text("sonora.lyrics.none"), x, top + 8, 0xFF7A7A7A, false);
            return;
        }
        int lineH = 14;
        Draw.scissor(g, x, top, x + w, top + height);
        if (lyrics.synced()) {
            int current = lyrics.indexAt(position + 300L);
            float target = Math.max(0, current) * lineH;
            this.lyricScroll = Mth.lerp(0.18F, this.lyricScroll, target);
            int center = top + height / 3;
            for (int i = 0; i < lyrics.lines().size(); i++) {
                float ly = center + i * lineH - this.lyricScroll;
                if (ly < top - lineH || ly > top + height) {
                    continue;
                }
                String text = lyrics.lines().get(i).text();
                if (text.isEmpty()) {
                    text = "♪";
                }
                int distance = Math.abs(i - current);
                int color = i == current ? 0xFFFFFFFF : i < current ? 0xFF6E6E6E : Colors.argb(Math.max(0x50, 0xC0 - distance * 0x18), 0xD0D0D0);
                if (i == current) {
                    Draw.scaledText(g, this.font, Draw.ellipsize(this.font, text, (int) (w / 1.15F)), x, ly - 1, 1.15F, color, true);
                    g.fill(x - 6, (int) ly, x - 4, (int) ly + 10, Colors.argb(0xFF, accent));
                } else {
                    g.drawString(this.font, Draw.ellipsize(this.font, text, w), x, (int) ly, color, false);
                }
            }
        } else {
            int line = 0;
            for (Lyrics.Line entry : lyrics.lines()) {
                for (FormattedCharSequence wrapped : this.font.split(Component.literal(entry.text()), w)) {
                    int ly = (int) (top + line * 11 - this.plainLyricScroll);
                    if (ly > top - 11 && ly < top + height) {
                        g.drawString(this.font, wrapped, x, ly, 0xFFD0D0D0, false);
                    }
                    line++;
                }
            }
            this.plainLyricScroll = Mth.clamp(this.plainLyricScroll, 0, Math.max(0, line * 11 - height + 10));
        }
        g.disableScissor();
    }

    private void renderList(GuiGraphics g, int mx, int my, int mw, int mh, int mouseX, int mouseY) {
        int accent = MiniPlayer.accent();
        int headerY = my + 4;
        int x = mx;
        if (this.tab == Tab.SEARCH) {
            Draw.rounded(g, mx, my + 1, Math.min(380, mw), 24, 12, 0xFF2A2A2E);
            Draw.icon(g, Draw.Icon.SEARCH, mx + 6, my + 6, 14, 0xFFB3B3B3);
            headerY = my + 34;
        }
        if (!this.history.isEmpty()) {
            boolean hover = Draw.inside(mouseX, mouseY, x, headerY - 2, 20, 20);
            Draw.rounded(g, x, headerY - 2, 20, 20, 10, hover ? 0x50FFFFFF : 0x25FFFFFF);
            Draw.icon(g, Draw.Icon.PREV, x + 4, headerY + 2, 12, 0xFFFFFFFF);
            this.hit(x, headerY - 2, 20, 20, this::back);
            x += 28;
        }
        if (this.tab != Tab.SEARCH || !this.history.isEmpty()) {
            Draw.scaledText(g, this.font, Draw.ellipsize(this.font, this.listTitle, (int) ((mw - 140) / 1.5F)), x, headerY, 1.5F, 0xFFFFFFFF, true);
        }
        int actionX = mx + mw;
        if (this.container != null) {
            actionX -= 34;
            boolean hover = Draw.inside(mouseX, mouseY, actionX, headerY - 6, 28, 28);
            Draw.rounded(g, actionX, headerY - 6, 28, 28, 14, hover ? Colors.argb(0xFF, Colors.mix(accent, 0xFFFFFF, 0.2F)) : Colors.argb(0xFF, accent));
            Draw.icon(g, Draw.Icon.PLAY, actionX + 7, headerY + 1, 14, 0xFF000000);
            BrowseItem playlist = this.container;
            this.hit(actionX, headerY - 6, 28, 28, () -> Sonora.controller().playSpotify(playlist, List.of(), null));
        }
        if (this.tab == Tab.LOCAL) {
            actionX = this.pill(g, actionX, headerY - 4, Draw.Icon.FOLDER, Sonora.text("sonora.local.open"), mouseX, mouseY, () -> {
                try {
                    java.nio.file.Files.createDirectories(LocalLibrary.folders().get(0));
                } catch (Exception ignored) {
                }
                Util.getPlatform().openFile(LocalLibrary.folders().get(0).toFile());
            });
            actionX = this.pill(g, actionX, headerY - 4, Draw.Icon.REPEAT, Sonora.text("sonora.local.rescan"), mouseX, mouseY, LocalLibrary::rescan);
        }
        if (this.tab == Tab.QUEUE || this.tab == Tab.LIBRARY && this.container == null) {
            this.pill(g, actionX, headerY - 4, Draw.Icon.REPEAT, Sonora.text("sonora.list.refresh"), mouseX, mouseY, () -> {
                this.items = List.of();
                this.loadTab();
            });
        }
        this.listX = mx;
        this.listY = headerY + 24;
        this.listW = mw;
        this.listH = my + mh - this.listY;
        if (this.loading) {
            g.drawCenteredString(this.font, Sonora.text("sonora.list.loading"), mx + mw / 2, this.listY + 20, 0xFFB3B3B3);
            return;
        }
        if (!this.listError.isEmpty() && (this.tab == Tab.LIBRARY || this.tab == Tab.SEARCH || this.tab == Tab.QUEUE)) {
            String text = Sonora.text("sonora.list.error", this.listError) + (SpotifyAuth.ownClient() ? "" : " " + Sonora.text("sonora.client.hint"));
            List<FormattedCharSequence> lines = this.font.split(Component.literal(text), Math.min(420, mw - 20));
            int lineY = this.items.isEmpty() ? this.listY + 20 : my + mh - 6 - lines.size() * 11;
            for (int i = 0; i < lines.size(); i++) {
                g.drawCenteredString(this.font, lines.get(i), mx + mw / 2, lineY + i * 11, 0xFFFFA060);
            }
            if (this.items.isEmpty()) {
                return;
            }
        }
        if (this.items.isEmpty()) {
            String key = switch (this.tab) {
                case LIBRARY, QUEUE -> SpotifyAuth.loggedIn() || this.tab == Tab.QUEUE ? "sonora.list.empty" : "sonora.now.login_hint";
                case SEARCH -> this.lastSearch.isEmpty() ? "sonora.search.prompt" : "sonora.list.empty";
                case LOCAL -> LocalLibrary.scanning() ? "sonora.list.loading" : "sonora.local.empty";
                default -> "sonora.list.empty";
            };
            List<FormattedCharSequence> lines = this.font.split(Component.literal(Sonora.text(key, LocalLibrary.folders().get(0).toString())), Math.min(360, mw - 20));
            for (int i = 0; i < lines.size(); i++) {
                g.drawCenteredString(this.font, lines.get(i), mx + mw / 2, this.listY + 20 + i * 11, 0xFFB3B3B3);
            }
            return;
        }
        int total = this.items.size() * ROW;
        this.scroll = Mth.clamp(this.scroll, 0, Math.max(0, total - this.listH));
        Draw.scissor(g, mx, this.listY, mx + mw, this.listY + this.listH);
        MusicController.NowPlaying now = Sonora.controller().nowPlaying();
        String currentUri = now.track() == null ? "" : now.track().local() ? "local:" + now.track().path().toAbsolutePath() : now.track().uri();
        int number = 0;
        for (int i = 0; i < this.items.size(); i++) {
            BrowseItem item = this.items.get(i);
            int ry = this.listY + i * ROW - (int) this.scroll;
            if (item.kind() != BrowseItem.Kind.HEADER) {
                number++;
            }
            if (ry + ROW < this.listY || ry > this.listY + this.listH) {
                continue;
            }
            if (item.kind() == BrowseItem.Kind.HEADER) {
                g.drawString(this.font, item.title(), mx + 4, ry + 14, Colors.argb(0xFF, accent), false);
                continue;
            }
            boolean hover = Draw.inside(mouseX, mouseY, mx, ry, mw, ROW) && mouseY >= this.listY && mouseY < this.listY + this.listH;
            String uri = item.local() != null ? item.id() : item.uri();
            boolean playing = !uri.isEmpty() && uri.equals(currentUri);
            if (hover) {
                Draw.rounded(g, mx, ry + 1, mw, ROW - 2, 5, 0x22FFFFFF);
            }
            int textX = mx + 34;
            if (item.kind() == BrowseItem.Kind.TRACK || item.kind() == BrowseItem.Kind.LOCAL) {
                String index = String.valueOf(number);
                if (hover) {
                    Draw.icon(g, Draw.Icon.PLAY, mx + 6, ry + 9, 12, 0xFFFFFFFF);
                } else {
                    g.drawCenteredString(this.font, index, mx + 12, ry + 11, playing ? Colors.argb(0xFF, accent) : 0xFF8A8A8A);
                }
                textX = mx + 26;
            }
            Artwork.Entry thumb = item.local() != null ? Artwork.get(item.artKey(), () -> item.local().meta().artwork()) : Artwork.get(item.artKey(), null);
            if (item.kind() == BrowseItem.Kind.LIKED) {
                Draw.roundedGradient(g, textX, ry + 3, 24, 24, 4, 0xFF5038ED, 0xFF8DB4E8);
                Draw.icon(g, Draw.Icon.HEART_FILLED, textX + 5, ry + 8, 14, 0xFFFFFFFF);
            } else {
                Draw.art(g, thumb, textX, ry + 3, 24, 4, hover ? 0x00000000 : 0x00000000);
            }
            int infoX = textX + 32;
            int durationW = item.durationMs() > 0 ? 40 : 0;
            int infoW = mw - (infoX - mx) - durationW - 8;
            g.drawString(this.font, Draw.ellipsize(this.font, item.title(), infoW), infoX, ry + 5, playing ? Colors.argb(0xFF, accent) : 0xFFFFFFFF, false);
            g.drawString(this.font, Draw.ellipsize(this.font, item.subtitle(), infoW), infoX, ry + 17, 0xFF9A9A9A, false);
            if (item.durationMs() > 0) {
                String duration = Draw.time(item.durationMs());
                g.drawString(this.font, duration, mx + mw - 8 - this.font.width(duration), ry + 11, 0xFF8A8A8A, false);
            }
            if (ry >= this.listY - ROW / 2 && ry < this.listY + this.listH) {
                BrowseItem clicked = item;
                List<BrowseItem> context = this.items;
                this.hit(mx, Math.max(ry, this.listY), mw, Math.min(ROW, this.listY + this.listH - Math.max(ry, this.listY)),
                        () -> this.activate(clicked, context), () -> this.secondary(clicked));
            }
        }
        g.disableScissor();
        if (total > this.listH) {
            int barH = Math.max(20, this.listH * this.listH / total);
            int barY = this.listY + (int) ((this.listH - barH) * this.scroll / Math.max(1, total - this.listH));
            Draw.rounded(g, mx + mw + 4, barY, 4, barH, 2, 0x50FFFFFF);
        }
    }

    private int pill(GuiGraphics g, int right, int y, Draw.Icon icon, String text, int mouseX, int mouseY, Runnable action) {
        int w = this.font.width(text) + 26;
        int x = right - w - 6;
        boolean hover = Draw.inside(mouseX, mouseY, x, y, w, 20);
        Draw.rounded(g, x, y, w, 20, 10, hover ? 0x50FFFFFF : 0x25FFFFFF);
        Draw.icon(g, icon, x + 6, y + 4, 12, 0xFFFFFFFF);
        g.drawString(this.font, text, x + 21, y + 6, 0xFFFFFFFF, false);
        this.hit(x, y, w, 20, action);
        return x;
    }

    private void activate(BrowseItem item, List<BrowseItem> context) {
        MusicController controller = Sonora.controller();
        switch (item.kind()) {
            case PLAYLIST, ALBUM, LIKED -> this.openContainer(item);
            case TRACK -> controller.playSpotify(item, context, this.container);
            case LOCAL -> {
                List<LocalLibrary.LocalTrack> tracks = new ArrayList<>();
                int index = 0;
                for (BrowseItem entry : context) {
                    if (entry.local() != null) {
                        if (entry == item) {
                            index = tracks.size();
                        }
                        tracks.add(entry.local());
                    }
                }
                controller.playLocal(tracks, index);
            }
            default -> {
            }
        }
    }

    private void secondary(BrowseItem item) {
        if (item.kind() == BrowseItem.Kind.TRACK) {
            Sonora.controller().queueSpotify(item);
            this.showToast(Sonora.text("sonora.toast.queued", item.title()));
        } else if (item.kind() == BrowseItem.Kind.PLAYLIST || item.kind() == BrowseItem.Kind.ALBUM) {
            Sonora.controller().playSpotify(item, List.of(), null);
        }
    }

    private void renderEqualizer(GuiGraphics g, int mx, int my, int mw, int mh, int mouseX, int mouseY) {
        SonoraConfig config = SonoraConfig.get();
        int accent = MiniPlayer.accent();
        Draw.scaledText(g, this.font, Sonora.text("sonora.tab.eq"), mx, my + 4, 1.5F, 0xFFFFFFFF, true);
        int toggleX = mx + mw - 90;
        boolean hover = Draw.inside(mouseX, mouseY, toggleX, my, 90, 20);
        Draw.rounded(g, toggleX, my, 90, 20, 10, config.eqEnabled ? Colors.argb(0xFF, accent) : 0x40FFFFFF);
        g.drawCenteredString(this.font, Sonora.text(config.eqEnabled ? "sonora.eq.on" : "sonora.eq.off"), toggleX + 45, my + 6, config.eqEnabled ? 0xFF000000 : 0xFFFFFFFF);
        this.hit(toggleX, my, 90, 20, () -> {
            config.eqEnabled = !config.eqEnabled;
            Sonora.controller().applyEqualizer();
            SonoraConfig.save();
        });
        if (hover) {
            Draw.rounded(g, toggleX, my, 90, 20, 10, 0x20FFFFFF);
        }
        int px = mx;
        int py = my + 30;
        for (String preset : Equalizer.PRESETS) {
            String label = Sonora.text("sonora.eq.preset." + preset);
            int w = this.font.width(label) + 14;
            if (px + w > mx + mw) {
                px = mx;
                py += 24;
            }
            boolean selected = preset.equals(config.eqPreset);
            boolean over = Draw.inside(mouseX, mouseY, px, py, w, 18);
            Draw.rounded(g, px, py, w, 18, 9, selected ? Colors.argb(0xFF, accent) : over ? 0x50FFFFFF : 0x25FFFFFF);
            g.drawString(this.font, label, px + 7, py + 5, selected ? 0xFF000000 : 0xFFFFFFFF, false);
            this.hit(px, py, w, 18, () -> {
                config.eqPreset = preset;
                config.eqGains = Equalizer.preset(preset);
                Sonora.controller().applyEqualizer();
                SonoraConfig.save();
            });
            px += w + 6;
        }
        int top = py + 34;
        int height = Math.max(60, mh - (top - my) - 30);
        int bands = Equalizer.FREQUENCIES.length + 1;
        int slot = Math.min(56, mw / bands);
        int startX = mx + (mw - slot * bands) / 2;
        float[] bars = Visualizer.resample(Sonora.controller().engine().spectrum().bars(), 60);
        Visualizer.draw(g, bars, startX, top, slot * bands, height, accent, "bars", 0x28);
        for (int band = -1; band < Equalizer.FREQUENCIES.length; band++) {
            int cx = startX + (band + 1) * slot + slot / 2;
            float value = band < 0 ? config.eqPreamp : config.eqGains[band];
            int zero = top + height / 2;
            g.fill(cx - 1, top, cx + 1, top + height, 0x40FFFFFF);
            int knobY = top + Math.round((12.0F - value) / 24.0F * height);
            int lo = Math.min(zero, knobY);
            int hi = Math.max(zero, knobY);
            g.fill(cx - 2, lo, cx + 2, hi, Colors.argb(0xFF, band < 0 ? 0xFFFFFF : accent));
            boolean over = Draw.inside(mouseX, mouseY, cx - slot / 2, top - 6, slot, height + 12);
            Draw.rounded(g, cx - 7, knobY - 4, 14, 8, 4, over || this.drag == band || (band < 0 && this.drag == DRAG_PREAMP) ? 0xFFFFFFFF : 0xFFDDDDDD);
            String label = band < 0 ? Sonora.text("sonora.eq.preamp") : Equalizer.LABELS[band];
            g.drawCenteredString(this.font, label, cx, top + height + 8, 0xFFB3B3B3);
            String db = String.format("%+.0f", value);
            Draw.scaledText(g, this.font, db, cx - this.font.width(db) * 0.375F, top - 12, 0.75F, 0xFF9A9A9A, false);
        }
        this.hit(startX, top - 6, slot * bands, height + 12, () -> {
            int band = (int) ((this.lastMouseX - startX) / slot) - 1;
            this.drag = band < 0 ? DRAG_PREAMP : band;
            this.dragInfo = new int[]{top, height};
            this.updateEqDrag(this.lastMouseY);
        });
    }

    private double lastMouseX;
    private double lastMouseY;
    private int[] dragInfo = new int[2];
    private int[] seekInfo = new int[2];
    private int[] volumeInfo = new int[2];

    private void updateEqDrag(double mouseY) {
        float value = 12.0F - (float) ((mouseY - this.dragInfo[0]) / this.dragInfo[1] * 24.0);
        value = Math.round(Mth.clamp(value, -12.0F, 12.0F) * 2.0F) / 2.0F;
        SonoraConfig config = SonoraConfig.get();
        if (this.drag == DRAG_PREAMP) {
            config.eqPreamp = value;
        } else if (this.drag >= 0 && this.drag < 10) {
            config.eqGains[this.drag] = value;
            config.eqPreset = "custom";
        }
        Sonora.controller().applyEqualizer();
    }

    private void renderSettings(GuiGraphics g, int mx, int my, int mw, int mh, int mouseX, int mouseY) {
        SonoraConfig config = SonoraConfig.get();
        Draw.scaledText(g, this.font, Sonora.text("sonora.tab.settings"), mx, my + 4, 1.5F, 0xFFFFFFFF, true);
        int y = my + 30;
        y = this.option(g, mx, y, mw, mouseX, mouseY, "sonora.settings.mini", on(config.miniPlayer), () -> config.miniPlayer = !config.miniPlayer);
        y = this.option(g, mx, y, mw, mouseX, mouseY, "sonora.settings.corner", Sonora.text("sonora.corner." + config.miniCorner),
                () -> config.miniCorner = cycle(new String[]{"top_right", "top_left", "bottom_right", "bottom_left"}, config.miniCorner));
        y = this.option(g, mx, y, mw, mouseX, mouseY, "sonora.settings.scale", Math.round(config.miniScale * 100) + "%",
                () -> config.miniScale = cycleFloat(new float[]{0.75F, 1.0F, 1.25F, 1.5F}, config.miniScale));
        y = this.option(g, mx, y, mw, mouseX, mouseY, "sonora.settings.mini_visualizer", on(config.miniVisualizer), () -> config.miniVisualizer = !config.miniVisualizer);
        y = this.option(g, mx, y, mw, mouseX, mouseY, "sonora.settings.mini_lyrics", on(config.miniLyrics), () -> config.miniLyrics = !config.miniLyrics);
        y = this.option(g, mx, y, mw, mouseX, mouseY, "sonora.settings.style", Sonora.text("sonora.style." + config.visualizerStyle),
                () -> config.visualizerStyle = cycle(new String[]{"bars", "mirror", "wave", "dots"}, config.visualizerStyle));
        y = this.option(g, mx, y, mw, mouseX, mouseY, "sonora.settings.bars", String.valueOf(config.visualizerBars), () -> {
            int[] values = {24, 32, 48, 64, 96};
            int next = values[0];
            for (int i = 0; i < values.length; i++) {
                if (values[i] == config.visualizerBars) {
                    next = values[(i + 1) % values.length];
                }
            }
            config.visualizerBars = next;
            Sonora.controller().engine().setBarCount(next);
        });
        y = this.option(g, mx, y, mw, mouseX, mouseY, "sonora.settings.accent", on(config.accentFromArtwork), () -> config.accentFromArtwork = !config.accentFromArtwork);
        y = this.option(g, mx, y, mw, mouseX, mouseY, "sonora.settings.lyrics", on(config.lyrics), () -> config.lyrics = !config.lyrics);
        y = this.option(g, mx, y, mw, mouseX, mouseY, "sonora.settings.bitrate", config.bitrate + " kbps", () -> {
            config.bitrate = config.bitrate == 320 ? 96 : config.bitrate == 96 ? 160 : 320;
            Librespot.restart();
        });
        y = this.option(g, mx, y, mw, mouseX, mouseY, "sonora.settings.normalize", on(config.normalize), () -> {
            config.normalize = !config.normalize;
            Librespot.restart();
        });
        this.option(g, mx, y, mw, mouseX, mouseY, "sonora.settings.account", SpotifyAuth.loggedIn() ? accountName() : Sonora.text("sonora.button.login"),
                () -> this.minecraft.setScreen(new LoginScreen(this)));
    }

    private static String on(boolean value) {
        return Sonora.text(value ? "options.on" : "options.off");
    }

    private static String cycle(String[] values, String current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    private static float cycleFloat(float[] values, float current) {
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - current) < 0.01F) {
                return values[(i + 1) % values.length];
            }
        }
        return values[1];
    }

    private int option(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY, String key, String value, Runnable action) {
        int rowW = Math.min(w, 420);
        boolean hover = Draw.inside(mouseX, mouseY, x, y, rowW, 22);
        Draw.rounded(g, x, y, rowW, 22, 6, hover ? 0x30FFFFFF : 0x18FFFFFF);
        g.drawString(this.font, Sonora.text(key), x + 10, y + 7, 0xFFFFFFFF, false);
        g.drawString(this.font, value, x + rowW - 10 - this.font.width(value), y + 7, Colors.argb(0xFF, MiniPlayer.accent()), false);
        this.hit(x, y, rowW, 22, () -> {
            action.run();
            SonoraConfig.save();
        });
        return y + 26;
    }

    private void renderBar(GuiGraphics g, MusicController.NowPlaying now, Artwork.Entry art, int mouseX, int mouseY) {
        int top = this.height - this.barH;
        int accent = MiniPlayer.accent();
        g.fill(0, top, this.width, this.height, 0xF00A0A0C);
        g.fill(0, top, this.width, top + 1, 0x20FFFFFF);
        MusicController controller = Sonora.controller();
        TrackInfo track = now.track();
        int leftW = Math.max(120, Math.min(320, this.width / 2 - 100));
        Draw.art(g, art, 10, top + 9, 44, 5, 0xF00A0A0C);
        if (track != null) {
            Draw.marquee(g, this.font, track.title(), 62, top + 16, leftW - 80, 0xFFFFFFFF, false);
            g.drawString(this.font, Draw.ellipsize(this.font, track.artistLine(), leftW - 80), 62, top + 30, 0xFFA0A0A0, false);
            if (now.mode() == MusicController.Mode.SPOTIFY) {
                int hx = 62 + leftW - 76;
                boolean hover = Draw.inside(mouseX, mouseY, hx, top + 20, 14, 14);
                Draw.icon(g, now.liked() ? Draw.Icon.HEART_FILLED : Draw.Icon.HEART, hx, top + 20, 14,
                        now.liked() ? Colors.argb(0xFF, Draw.GREEN) : hover ? 0xFFFFFFFF : 0xFFA0A0A0);
                this.hit(hx, top + 20, 14, 14, controller::toggleLike);
            }
        }
        int cx = this.width / 2;
        int cy = top + 20;
        boolean shuffleOn = now.shuffle();
        this.control(g, cx - 72, cy - 7, 14, Draw.Icon.SHUFFLE, shuffleOn ? Colors.argb(0xFF, accent) : 0xFFB3B3B3, mouseX, mouseY, controller::toggleShuffle);
        this.control(g, cx - 40, cy - 7, 14, Draw.Icon.PREV, 0xFFDDDDDD, mouseX, mouseY, controller::previous);
        boolean playHover = Draw.inside(mouseX, mouseY, cx - 14, cy - 14, 28, 28);
        Draw.rounded(g, cx - 14, cy - 14, 28, 28, 14, playHover ? 0xFFFFFFFF : 0xFFEDEDED);
        Draw.icon(g, now.playing() ? Draw.Icon.PAUSE : Draw.Icon.PLAY, cx - 8, cy - 8, 16, 0xFF000000);
        this.hit(cx - 14, cy - 14, 28, 28, controller::togglePlay);
        this.control(g, cx + 26, cy - 7, 14, Draw.Icon.NEXT, 0xFFDDDDDD, mouseX, mouseY, controller::next);
        boolean repeatOn = !"off".equals(now.repeat());
        this.control(g, cx + 58, cy - 7, 14, "track".equals(now.repeat()) ? Draw.Icon.REPEAT_ONE : Draw.Icon.REPEAT,
                repeatOn ? Colors.argb(0xFF, accent) : 0xFFB3B3B3, mouseX, mouseY, controller::cycleRepeat);
        int barW = Math.min(480, this.width - 2 * leftW - 40);
        int bx = cx - barW / 2;
        int by = top + 44;
        long duration = track == null ? 0 : track.durationMs();
        long position = this.drag == DRAG_SEEK ? (long) (this.dragValue * duration) : now.position();
        String left = Draw.time(position);
        String right = Draw.time(duration);
        g.drawString(this.font, left, bx - 6 - this.font.width(left), by - 3, 0xFFA0A0A0, false);
        g.drawString(this.font, right, bx + barW + 6, by - 3, 0xFFA0A0A0, false);
        boolean seekHover = Draw.inside(mouseX, mouseY, bx, by - 5, barW, 12) || this.drag == DRAG_SEEK;
        Draw.rounded(g, bx, by - 1, barW, 4, 2, 0x40FFFFFF);
        float progress = duration > 0 ? Mth.clamp((float) position / duration, 0.0F, 1.0F) : 0.0F;
        int filled = Math.round(barW * progress);
        Draw.rounded(g, bx, by - 1, Math.max(4, filled), 4, 2, seekHover ? Colors.argb(0xFF, accent) : 0xFFFFFFFF);
        if (seekHover) {
            Draw.rounded(g, bx + filled - 5, by - 4, 10, 10, 5, 0xFFFFFFFF);
        }
        this.seekInfo = new int[]{bx, barW};
        this.hit(bx, by - 5, barW, 12, () -> {
            if (duration > 0) {
                this.drag = DRAG_SEEK;
                this.dragValue = (float) Mth.clamp((this.lastMouseX - this.seekInfo[0]) / this.seekInfo[1], 0.0, 1.0);
            }
        });
        int vx = this.width - 120;
        int vy = top + 26;
        float volume = this.drag == DRAG_VOLUME ? this.dragValue : controller.engine().volume();
        this.control(g, vx - 22, vy - 7, 14, volume <= 0.001F ? Draw.Icon.VOLUME_MUTE : Draw.Icon.VOLUME, 0xFFB3B3B3, mouseX, mouseY, () -> {
            controller.setVolume(controller.engine().volume() > 0.0F ? 0.0F : 0.6F);
            SonoraConfig.save();
        });
        boolean volumeHover = Draw.inside(mouseX, mouseY, vx, vy - 5, 100, 12) || this.drag == DRAG_VOLUME;
        Draw.rounded(g, vx, vy - 1, 100, 4, 2, 0x40FFFFFF);
        Draw.rounded(g, vx, vy - 1, Math.max(4, Math.round(100 * volume)), 4, 2, volumeHover ? Colors.argb(0xFF, accent) : 0xFFFFFFFF);
        if (volumeHover) {
            Draw.rounded(g, vx + Math.round(100 * volume) - 5, vy - 4, 10, 10, 5, 0xFFFFFFFF);
        }
        this.volumeInfo = new int[]{vx, 100};
        this.hit(vx, vy - 5, 100, 12, () -> {
            this.drag = DRAG_VOLUME;
            this.dragValue = (float) Mth.clamp((this.lastMouseX - this.volumeInfo[0]) / this.volumeInfo[1], 0.0, 1.0);
            controller.setVolume(this.dragValue);
        });
        if (now.mode() == MusicController.Mode.SPOTIFY && SpotifyAuth.loggedIn()) {
            int dx = vx - 44;
            int color = now.inGame() ? Colors.argb(0xFF, Draw.GREEN) : 0xFFB3B3B3;
            this.control(g, dx, vy - 7, 14, Draw.Icon.DEVICE, color, mouseX, mouseY, controller::listenInGame);
        }
    }

    private void control(GuiGraphics g, int x, int y, int size, Draw.Icon icon, int color, int mouseX, int mouseY, Runnable action) {
        boolean hover = Draw.inside(mouseX, mouseY, x - 3, y - 3, size + 6, size + 6);
        Draw.icon(g, icon, x, y, size, hover ? 0xFFFFFFFF : color);
        this.hit(x - 3, y - 3, size + 6, size + 6, action);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        if (this.searchBox != null && this.searchBox.mouseClicked(mouseX, mouseY, button)) {
            this.setFocused(this.searchBox);
            return true;
        }
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit hit = this.hits.get(i);
            if (Draw.inside(mouseX, mouseY, hit.x(), hit.y(), hit.w(), hit.h())) {
                Runnable action = button == 1 ? hit.right() : button == 0 ? hit.left() : null;
                if (action != null) {
                    action.run();
                    return true;
                }
            }
        }
        if (this.searchBox != null) {
            this.searchBox.setFocused(false);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        if (this.drag == DRAG_SEEK) {
            this.dragValue = (float) Mth.clamp((mouseX - this.seekInfo[0]) / this.seekInfo[1], 0.0, 1.0);
            return true;
        }
        if (this.drag == DRAG_VOLUME) {
            this.dragValue = (float) Mth.clamp((mouseX - this.volumeInfo[0]) / this.volumeInfo[1], 0.0, 1.0);
            Sonora.controller().setVolume(this.dragValue);
            return true;
        }
        if (this.drag == DRAG_PREAMP || (this.drag >= 0 && this.drag < 10)) {
            this.updateEqDrag(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.drag == DRAG_SEEK) {
            TrackInfo track = Sonora.controller().nowPlaying().track();
            if (track != null) {
                Sonora.controller().seek((long) (this.dragValue * track.durationMs()));
            }
        }
        if (this.drag != -1) {
            SonoraConfig.save();
        }
        this.drag = -1;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.tab == Tab.NOW) {
            this.plainLyricScroll -= scrollY * 22;
            return true;
        }
        if (Draw.inside(mouseX, mouseY, this.listX, this.listY, this.listW, this.listH)) {
            this.scroll -= scrollY * ROW * 1.5;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean typing = this.searchBox != null && this.searchBox.isFocused();
        if (typing && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            String query = this.searchBox.getValue().trim();
            this.lastSearch = query;
            if (!query.isEmpty() && SpotifyAuth.loggedIn()) {
                this.history.clear();
                this.container = null;
                this.listTitle = query;
                this.load(Sonora.controller().spotify().search(query));
            }
            return true;
        }
        if (!typing) {
            MusicController controller = Sonora.controller();
            switch (keyCode) {
                case GLFW.GLFW_KEY_SPACE -> {
                    controller.togglePlay();
                    return true;
                }
                case GLFW.GLFW_KEY_RIGHT -> {
                    controller.seek(controller.nowPlaying().position() + 5000L);
                    return true;
                }
                case GLFW.GLFW_KEY_LEFT -> {
                    controller.seek(Math.max(0L, controller.nowPlaying().position() - 5000L));
                    return true;
                }
                case GLFW.GLFW_KEY_UP -> {
                    controller.setVolume(controller.engine().volume() + 0.05F);
                    return true;
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    controller.setVolume(controller.engine().volume() - 0.05F);
                    return true;
                }
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    this.back();
                    return true;
                }
                default -> {
                }
            }
            if (Sonora.OPEN.matches(keyCode, scanCode)) {
                this.onClose();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        SonoraConfig.save();
        this.minecraft.setScreen(this.parent);
    }
}
