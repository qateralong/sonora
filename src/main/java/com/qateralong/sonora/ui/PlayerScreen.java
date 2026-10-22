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
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

public class PlayerScreen extends SonoraScreen {
    public enum Tab {
        NOW(Draw.Icon.DISC), LYRICS(Draw.Icon.LYRICS), LIBRARY(Draw.Icon.LIBRARY), SEARCH(Draw.Icon.SEARCH), QUEUE(Draw.Icon.QUEUE),
        LOCAL(Draw.Icon.FOLDER), EQ(Draw.Icon.EQ), SETTINGS(Draw.Icon.SETTINGS);

        final Draw.Icon icon;

        Tab(Draw.Icon icon) {
            this.icon = icon;
        }
    }

    private static final Tab[][] GROUPS = {{Tab.NOW, Tab.LYRICS}, {Tab.LIBRARY, Tab.SEARCH, Tab.QUEUE, Tab.LOCAL}, {Tab.EQ, Tab.SETTINGS}};
    private static final String[] GROUP_KEYS = {"sonora.section.listen", "sonora.section.music", "sonora.section.sound"};

    private record Hit(int x, int y, int w, int h, Runnable left, Runnable right) {
    }

    private record ListState(String title, BrowseItem container, List<BrowseItem> items, double scroll) {
    }

    private static Tab lastTab = Tab.NOW;
    private final Screen parent;
    private Tab tab = lastTab;
    private Tab beforeLyrics = Tab.NOW;
    private final List<Hit> hits = new ArrayList<>();
    private final Deque<ListState> history = new ArrayDeque<>();
    private List<BrowseItem> items = List.of();
    private String listTitle = "";
    private BrowseItem container;
    private double scroll;
    private boolean loading;
    private String listError = "";
    private long errorSince;
    private EditBox searchBox;
    private String lastSearch = "";
    private int drag = -1;
    private float dragValue;
    private long toastUntil;
    private String toast = "";
    private float lyricScroll;
    private double plainLyricScroll;
    private float fullScroll;
    private double fullManual;
    private long manualUntil;
    private String lyricsKey = "";
    private double settingsScroll;
    private int settingsHeight;
    private float selectorY = -1;
    private long tabSince;
    private long lastFrame;
    private String tip;
    private int tipX;
    private int tipY;
    private int sideW;
    private int barH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int contentY;
    private int contentH;
    private static final int ROW = 28;
    private static final int DRAG_SEEK = 100;
    private static final int DRAG_VOLUME = 101;
    private static final int DRAG_PREAMP = 102;

    public PlayerScreen(Screen parent) {
        super(Component.literal("Sonora"));
        this.parent = parent;
    }

    @Override
    protected void layout() {
        this.sideW = Mth.clamp(this.width / 6, 124, 160);
        this.barH = 60;
        if (this.tab == Tab.SEARCH) {
            int x = this.sideW + 20;
            this.searchBox = new EditBox(this.font, x + 24, 25, Math.min(330, this.width - x - 60), 12, Component.translatable("sonora.search.hint"));
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
        if (next == this.tab) {
            return;
        }
        if (next == Tab.LYRICS) {
            this.beforeLyrics = this.tab;
            this.manualUntil = 0L;
        }
        this.tab = next;
        lastTab = next;
        this.tabSince = Util.getMillis();
        this.items = List.of();
        this.history.clear();
        this.container = null;
        this.scroll = 0;
        this.loading = false;
        this.rebuildWidgets();
    }

    private void toggleLyricsTab() {
        this.switchTab(this.tab == Tab.LYRICS ? (this.beforeLyrics == Tab.LYRICS ? Tab.NOW : this.beforeLyrics) : Tab.LYRICS);
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
            this.errorSince = Util.getMillis();
        }, this.minecraft);
    }

    private void renderErrorBanner(GuiGraphics g, int mx, int mw, int mouseX, int mouseY) {
        String text = Sonora.text("sonora.list.error", this.listError) + (SpotifyAuth.ownClient() ? "" : " " + Sonora.text("sonora.client.hint"));
        int bannerW = mw;
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), bannerW - 44);
        lines = lines.subList(0, Math.min(3, lines.size()));
        int bannerH = lines.size() * 10 + 12;
        float appear = Draw.ease(Math.min(1.0F, (Util.getMillis() - this.errorSince) / 250.0F));
        int y = this.listY - Math.round((1.0F - appear) * 6.0F);
        Draw.rounded(g, mx, y, bannerW, bannerH, 8, Colors.argb(Math.round(0x48 * appear), 0xFF8A3D));
        Draw.rounded(g, mx, y, 3, bannerH, 1, Colors.argb(Math.round(0xFF * appear), 0xFFA060));
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(this.font, lines.get(i), mx + 12, y + 7 + i * 10, Colors.argb(Math.max(8, Math.round(0xFF * appear)), 0xFFD8B8), false);
        }
        boolean hover = Draw.inside(mouseX, mouseY, mx + bannerW - 22, y + 2, 18, 18);
        Draw.icon(g, Draw.Icon.CLOSE, mx + bannerW - 18, y + 6, 10, hover ? 0xFFFFFFFF : 0xFFC0A090);
        this.hit(mx + bannerW - 22, y + 2, 18, 18, () -> this.listError = "");
        this.listY += bannerH + 8;
        this.listH -= bannerH + 8;
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
        this.tabSince = Util.getMillis();
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
        this.tabSince = Util.getMillis();
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

    private void tip(String key, int x, int y) {
        this.tip = Sonora.text(key);
        this.tipX = x;
        this.tipY = y;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int dark = MiniPlayer.dark();
        g.fillGradient(0, 0, this.width, this.height, Colors.argb(0xEC, Colors.mix(dark, 0x000000, 0.15F)), 0xF6060608);
    }

    @Override
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.hits.clear();
        this.tip = null;
        long now = Util.getMillis();
        float dt = this.lastFrame == 0L ? 0.016F : Math.min(0.1F, (now - this.lastFrame) / 1000.0F);
        this.lastFrame = now;
        MusicController controller = Sonora.controller();
        MusicController.NowPlaying playing = controller.nowPlaying();
        Artwork.Entry art = MiniPlayer.artwork(playing.track());
        MiniPlayer.updateAccent(art);
        super.draw(g, mouseX, mouseY, partialTick);
        this.renderSidebar(g, mouseX, mouseY, dt);
        int shift = Math.round((1.0F - Draw.ease(Math.min(1.0F, (now - this.tabSince) / 240.0F))) * 12.0F);
        int mx = this.sideW + 20 + shift;
        int my = 16;
        int mw = this.width - this.sideW - 40;
        int mh = this.height - this.barH - my - 12;
        switch (this.tab) {
            case NOW -> this.renderNow(g, playing, art, mx, my, mw, mh, mouseX, mouseY);
            case LYRICS -> this.renderLyricsTab(g, playing, art, mx, my, mw, mh, mouseX, mouseY, dt);
            case EQ -> this.renderEqualizer(g, mx, my, mw, mh, mouseX, mouseY);
            case SETTINGS -> this.renderSettings(g, mx, my, mw, mh, mouseX, mouseY);
            default -> this.renderList(g, mx, my, mw, mh, mouseX, mouseY);
        }
        this.renderBar(g, playing, art, mouseX, mouseY);
        if (now < this.toastUntil) {
            float fade = Math.min(1.0F, (this.toastUntil - now) / 300.0F);
            int w = this.font.width(this.toast) + 24;
            int y = this.height - this.barH - 32;
            Draw.rounded(g, this.width / 2 - w / 2, y, w, 20, 10, Colors.argb(Math.round(0xF0 * fade), 0xFFFFFF));
            if (fade > 0.05F) {
                g.drawCenteredString(this.font, this.toast, this.width / 2, y + 6, Colors.argb(Math.max(8, Math.round(0xFF * fade)), 0x000000));
            }
        }
        if (this.searchBox != null) {
            this.searchBox.render(g, mouseX, mouseY, partialTick);
        }
        if (this.tip != null && this.drag == -1) {
            Draw.tooltip(g, this.font, this.tip, this.tipX, this.tipY, this.width);
        }
    }

    private void renderSidebar(GuiGraphics g, int mouseX, int mouseY, float dt) {
        int bottomEdge = this.height - this.barH;
        g.fill(0, 0, this.sideW, bottomEdge, 0x66000000);
        g.fill(this.sideW - 1, 0, this.sideW, bottomEdge, 0x12FFFFFF);
        int accent = MiniPlayer.accent();
        Draw.icon(g, Draw.Icon.LOGO, 14, 14, 16, Colors.argb(0xFF, accent));
        Draw.scaledText(g, this.font, "Sonora", 36, 15, 1.35F, 0xFFFFFFFF, true);
        int rowH = 20;
        int tabs = Tab.values().length;
        boolean headers = 44 + tabs * (rowH + 2) + GROUPS.length * 16 + 54 <= bottomEdge;
        boolean account = 44 + tabs * (rowH + 2) + (headers ? GROUPS.length * 16 : 0) + 54 <= bottomEdge;
        int y = 42;
        int selectedY = 0;
        List<int[]> rows = new ArrayList<>();
        for (int group = 0; group < GROUPS.length; group++) {
            if (headers) {
                Draw.scaledText(g, this.font, Sonora.text(GROUP_KEYS[group]).toUpperCase(Locale.ROOT), 14, y + 5, 0.75F, 0xFF6E6E74, false);
                y += 16;
            } else if (group > 0) {
                y += 6;
            }
            for (Tab entry : GROUPS[group]) {
                rows.add(new int[]{entry.ordinal(), y});
                if (entry == this.tab) {
                    selectedY = y;
                }
                y += rowH + 2;
            }
        }
        if (this.selectorY < 0) {
            this.selectorY = selectedY;
        }
        this.selectorY += (selectedY - this.selectorY) * Math.min(1.0F, dt * 16.0F);
        int sy = Math.round(this.selectorY);
        Draw.rounded(g, 8, sy, this.sideW - 16, rowH, 6, 0x26FFFFFF);
        Draw.rounded(g, 8, sy + 4, 3, rowH - 8, 1, Colors.argb(0xFF, accent));
        for (int[] row : rows) {
            Tab entry = Tab.values()[row[0]];
            int ry = row[1];
            boolean selected = entry == this.tab;
            boolean hover = Draw.inside(mouseX, mouseY, 8, ry, this.sideW - 16, rowH);
            if (hover && !selected) {
                Draw.rounded(g, 8, ry, this.sideW - 16, rowH, 6, 0x12FFFFFF);
            }
            Draw.icon(g, entry.icon, 18, ry + 4, 12, selected ? Colors.argb(0xFF, accent) : hover ? 0xFFE0E0E0 : 0xFF9A9AA0);
            g.drawString(this.font, Sonora.text("sonora.tab." + entry.name().toLowerCase(Locale.ROOT)), 36, ry + 6, selected || hover ? 0xFFFFFFFF : 0xFFAAAAB0, false);
            this.hit(8, ry, this.sideW - 16, rowH, () -> this.switchTab(entry));
        }
        if (!account) {
            return;
        }
        int cardY = bottomEdge - 44;
        if (SpotifyAuth.loggedIn()) {
            boolean hover = Draw.inside(mouseX, mouseY, 8, cardY, this.sideW - 16, 34);
            Draw.rounded(g, 8, cardY, this.sideW - 16, 34, 8, hover ? 0x22FFFFFF : 0x14FFFFFF);
            Draw.rounded(g, 14, cardY + 7, 20, 20, 10, Colors.argb(0xFF, Colors.mix(accent, 0x000000, 0.35F)));
            Draw.icon(g, Draw.Icon.LOGO, 18, cardY + 11, 12, 0xFFFFFFFF);
            g.drawString(this.font, Draw.ellipsize(this.font, accountName(), this.sideW - 54), 40, cardY + 7, 0xFFFFFFFF, false);
            boolean ready = Librespot.running();
            String status = ready ? Sonora.text("sonora.status.ready") : Librespot.status();
            g.fill(40, cardY + 21, 44, cardY + 25, ready ? 0xFF1ED760 : 0xFFFFA060);
            Draw.scaledText(g, this.font, Draw.ellipsize(this.font, status, (int) ((this.sideW - 66) / 0.75F)), 48, cardY + 20, 0.75F, 0xFF9A9AA0, false);
            this.hit(8, cardY, this.sideW - 16, 34, () -> this.minecraft.setScreen(new LoginScreen(this)));
        } else {
            boolean hover = Draw.inside(mouseX, mouseY, 10, cardY + 8, this.sideW - 20, 22);
            Draw.rounded(g, 10, cardY + 8, this.sideW - 20, 22, 11, hover ? 0xFF1ED760 : 0xFF1DB954);
            g.drawCenteredString(this.font, Sonora.text("sonora.button.login"), this.sideW / 2, cardY + 15, 0xFF000000);
            this.hit(10, cardY + 8, this.sideW - 20, 22, () -> this.minecraft.setScreen(new LoginScreen(this)));
        }
    }

    private void emptyState(GuiGraphics g, Draw.Icon icon, String message, int cx, int y, int width) {
        Draw.rounded(g, cx - 20, y, 40, 40, 20, 0x18FFFFFF);
        Draw.icon(g, icon, cx - 10, y + 10, 20, 0xFF8A8A90);
        List<FormattedCharSequence> lines = this.font.split(Component.literal(message), Math.min(320, width - 20));
        for (int i = 0; i < lines.size(); i++) {
            g.drawCenteredString(this.font, lines.get(i), cx, y + 52 + i * 11, 0xFFB3B3B3);
        }
    }

    private void renderNow(GuiGraphics g, MusicController.NowPlaying now, Artwork.Entry art, int mx, int my, int mw, int mh, int mouseX, int mouseY) {
        TrackInfo track = now.track();
        int accent = MiniPlayer.accent();
        float[] bars = Visualizer.resample(Sonora.controller().engine().spectrum().bars(), Math.max(16, SonoraConfig.get().visualizerBars));
        int vizH = Math.min(64, mh / 5);
        Visualizer.draw(g, bars, mx, my + mh - vizH, mw, vizH, accent, SonoraConfig.get().visualizerStyle, 0x80);
        if (track == null) {
            String message = now.mode() == MusicController.Mode.SPOTIFY && !SpotifyAuth.loggedIn()
                    ? Sonora.text("sonora.now.login_hint") : Sonora.text("sonora.now.nothing");
            this.emptyState(g, Draw.Icon.NOTE, message, mx + mw / 2, my + mh / 4, mw);
            return;
        }
        int areaH = mh - vizH - 10;
        int artSize = Mth.clamp(Math.min(areaH - 60, (int) (mw * 0.36F)), 64, 230);
        int ax = mx + 6;
        int ay = my + 6;
        Draw.rounded(g, ax - 10, ay - 10, artSize + 20, artSize + 20, 18, Colors.argb(0x0E, accent));
        Draw.rounded(g, ax - 5, ay - 5, artSize + 10, artSize + 10, 13, Colors.argb(0x12, accent));
        Draw.rounded(g, ax + 2, ay + 4, artSize, artSize, 10, 0x60000000);
        Draw.art(g, art, ax, ay, artSize, 8, 0x00000000);
        int ty = ay + artSize + 12;
        Draw.slide(g, this.font, track.title(), ax, ty, artSize, 1.5F, 0xFFFFFFFF, true);
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
        int lx = ax + artSize + 28;
        int lw = mx + mw - lx;
        if (lw > 120) {
            this.renderLyricsPreview(g, track, now.position(), lx, ay - 2, lw, areaH - 4, accent, mouseX, mouseY);
        }
    }

    private void renderLyricsPreview(GuiGraphics g, TrackInfo track, long position, int x, int y, int w, int h, int accent, int mouseX, int mouseY) {
        Draw.icon(g, Draw.Icon.LYRICS, x, y + 2, 12, 0xB0FFFFFF);
        g.drawString(this.font, Sonora.text("sonora.lyrics.title"), x + 17, y + 4, 0xB0FFFFFF, false);
        String open = Sonora.text("sonora.lyrics.open");
        int ow = this.font.width(open) + 18;
        boolean hover = Draw.inside(mouseX, mouseY, x + w - ow, y, ow, 16);
        Draw.rounded(g, x + w - ow, y, ow, 16, 8, hover ? 0x40FFFFFF : 0x1EFFFFFF);
        g.drawString(this.font, open, x + w - ow + 8, y + 4, 0xFFFFFFFF, false);
        this.hit(x + w - ow, y, ow, 16, () -> this.switchTab(Tab.LYRICS));
        int top = y + 24;
        int height = h - 24;
        Draw.rounded(g, x, top, w, height, 10, Colors.argb(0x30, Colors.mix(accent, 0x000000, 0.4F)));
        int px = x + 12;
        int pw = w - 24;
        int inner = top + 8;
        int innerH = height - 16;
        if (!SonoraConfig.get().showLyrics) {
            this.lyricsMessage(g, Sonora.text("sonora.lyrics.disabled"), px, inner + 6, pw);
            return;
        }
        Lyrics.Result lyrics = NowLyrics.get(track);
        if (lyrics == null || lyrics.instrumental() || lyrics.lines().isEmpty()) {
            String key = lyrics == null ? NowLyrics.loading() ? "sonora.lyrics.loading" : "sonora.lyrics.none" : lyrics.instrumental() ? "sonora.lyrics.instrumental" : "sonora.lyrics.none";
            this.lyricsMessage(g, Sonora.text(key), px, inner + 6, pw);
            return;
        }
        int lineH = 14;
        Draw.scissor(g, x, inner, x + w, inner + innerH);
        if (lyrics.synced()) {
            int current = lyrics.indexAt(position + 300L);
            float glow = current < 0 ? 1.0F : Mth.clamp((position + 300L - lyrics.lines().get(current).timeMs()) / 420.0F, 0.0F, 1.0F);
            float eased = Draw.ease(glow);
            float target = Math.max(0, current) * lineH;
            this.lyricScroll = Mth.lerp(0.18F, this.lyricScroll, target);
            int center = inner + innerH / 3;
            for (int i = 0; i < lyrics.lines().size(); i++) {
                float ly = center + i * lineH - this.lyricScroll;
                if (ly < inner - lineH || ly > inner + innerH) {
                    continue;
                }
                String text = lyrics.lines().get(i).text();
                if (text.isEmpty()) {
                    text = "♪";
                }
                float edge = Math.min(ly - inner, inner + innerH - ly - 9);
                float fade = Mth.clamp((edge + 4.0F) / 16.0F, 0.0F, 1.0F);
                int color = lyricColor(i, current, glow, accent, false);
                if (i == current) {
                    g.fill(px - 7, (int) ly - 1, px - 5, (int) ly + 8, Colors.argb(Math.round(0xFF * glow * fade), accent));
                }
                int alpha = Math.round((color >>> 24) * fade);
                if (alpha < 8) {
                    continue;
                }
                float grow = i == current ? eased : i == current - 1 ? 1.0F - eased : 0.0F;
                float lineScale = 1.0F + 0.22F * grow;
                Draw.scaledText(g, this.font, Draw.ellipsize(this.font, text, (int) (pw / 1.22F)), px, ly - 1.0F * grow, lineScale, (alpha << 24) | (color & 0xFFFFFF), i == current && glow > 0.5F);
            }
        } else {
            int line = 0;
            for (Lyrics.Line entry : lyrics.lines()) {
                for (FormattedCharSequence wrapped : this.font.split(Component.literal(entry.text()), pw)) {
                    int ly = (int) (inner + line * 11 - this.plainLyricScroll);
                    if (ly > inner - 11 && ly < inner + innerH) {
                        g.drawString(this.font, wrapped, px, ly, 0xFFD0D0D0, false);
                    }
                    line++;
                }
            }
            this.plainLyricScroll = Mth.clamp(this.plainLyricScroll, 0, Math.max(0, line * 11 - innerH + 10));
        }
        g.disableScissor();
    }

    private void lyricsMessage(GuiGraphics g, String text, int x, int y, int w) {
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), w);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(this.font, lines.get(i), x, y + i * 11, 0xFF8A8A90, false);
        }
    }

    private void renderLyricsTab(GuiGraphics g, MusicController.NowPlaying now, Artwork.Entry art, int mx, int my, int mw, int mh, int mouseX, int mouseY, float dt) {
        int accent = MiniPlayer.accent();
        int panelTop = Colors.argb(0xE8, Colors.mix(accent, 0x000000, 0.38F));
        int panelBottom = Colors.argb(0xE8, Colors.mix(accent, 0x000000, 0.62F));
        Draw.roundedGradient(g, mx, my, mw, mh, 12, panelTop, panelBottom);
        TrackInfo track = now.track();
        if (track == null) {
            this.emptyState(g, Draw.Icon.LYRICS, Sonora.text("sonora.now.nothing"), mx + mw / 2, my + mh / 3, mw);
            return;
        }
        int x = mx + 22;
        int w = mw - 44;
        Draw.art(g, art, x, my + 14, 30, 5, 0x00000000);
        Draw.slide(g, this.font, track.title(), x + 40, my + 16, w - 40, 1.15F, 0xFFFFFFFF, true);
        g.drawString(this.font, Draw.ellipsize(this.font, track.artistLine(), w - 40), x + 40, my + 31, 0xC0FFFFFF, false);
        int top = my + 56;
        int bottom = my + mh - 20;
        g.fill(x, top - 8, x + w, top - 7, 0x18FFFFFF);
        Draw.scaledText(g, this.font, Sonora.text("sonora.lyrics.source"), x, bottom + 6, 0.75F, 0x80FFFFFF, false);
        if (!SonoraConfig.get().showLyrics) {
            this.lyricsMessage(g, Sonora.text("sonora.lyrics.disabled"), x, top + 10, w);
            String label = Sonora.text("sonora.lyrics.enable");
            int bw = this.font.width(label) + 20;
            boolean hover = Draw.inside(mouseX, mouseY, x, top + 34, bw, 20);
            Draw.rounded(g, x, top + 34, bw, 20, 10, hover ? 0xFFFFFFFF : 0xE0FFFFFF);
            g.drawString(this.font, label, x + 10, top + 40, 0xFF000000, false);
            this.hit(x, top + 34, bw, 20, () -> {
                SonoraConfig.get().showLyrics = true;
                SonoraConfig.save();
            });
            return;
        }
        Lyrics.Result lyrics = NowLyrics.get(track);
        if (lyrics == null || lyrics.instrumental() || lyrics.lines().isEmpty()) {
            String key = lyrics == null ? NowLyrics.loading() ? "sonora.lyrics.loading" : "sonora.lyrics.none" : lyrics.instrumental() ? "sonora.lyrics.instrumental" : "sonora.lyrics.none";
            Draw.scaledText(g, this.font, Sonora.text(key), x, top + 10, 1.5F, 0xB0FFFFFF, true);
            return;
        }
        String key = track.id();
        if (!key.equals(this.lyricsKey)) {
            this.lyricsKey = key;
            this.fullScroll = 0.0F;
            this.fullManual = 0.0;
            this.manualUntil = 0L;
        }
        float baseScale = 1.5F;
        float bigScale = 1.85F;
        int gap = 8;
        int wrapW = (int) (w / bigScale);
        int current = lyrics.synced() ? lyrics.indexAt(now.position() + 300L) : -1;
        float glow = current < 0 ? 1.0F : Mth.clamp((now.position() + 300L - lyrics.lines().get(current).timeMs()) / 280.0F, 0.0F, 1.0F);
        float grow = Draw.ease(current < 0 ? 1.0F : Mth.clamp((now.position() + 300L - lyrics.lines().get(current).timeMs()) / 420.0F, 0.0F, 1.0F));
        List<List<FormattedCharSequence>> wrapped = new ArrayList<>();
        float[] lineTop = new float[lyrics.lines().size()];
        float[] lineScale = new float[lyrics.lines().size()];
        float total = 0;
        for (int i = 0; i < lyrics.lines().size(); i++) {
            String text = lyrics.lines().get(i).text();
            List<FormattedCharSequence> parts = this.font.split(Component.literal(text.isEmpty() ? "♪" : text), wrapW);
            wrapped.add(parts);
            float emphasis = !lyrics.synced() ? 0.0F : i == current ? grow : i == current - 1 ? 1.0F - grow : 0.0F;
            lineScale[i] = lyrics.synced() ? baseScale + (bigScale - baseScale) * emphasis : baseScale;
            lineTop[i] = total;
            total += parts.size() * (this.font.lineHeight * lineScale[i] + 3.0F) + gap;
        }
        int viewH = bottom - top;
        long millis = Util.getMillis();
        float target;
        if (!lyrics.synced() || millis < this.manualUntil) {
            this.fullManual = Mth.clamp(this.fullManual, -viewH * 0.4, Math.max(0.0, total - viewH * 0.6));
            target = (float) this.fullManual;
        } else {
            int index = Math.max(0, current);
            target = lineTop[index] + wrapped.get(index).size() * (this.font.lineHeight * lineScale[index] + 3.0F) / 2.0F - viewH * 0.4F;
            this.fullManual = target;
        }
        this.fullScroll += (target - this.fullScroll) * Math.min(1.0F, dt * 8.0F);
        Draw.scissor(g, mx, top - 4, mx + mw, bottom + 4);
        for (int i = 0; i < wrapped.size(); i++) {
            float ly = top + lineTop[i] - this.fullScroll;
            float scale = lineScale[i];
            float rowH = this.font.lineHeight * scale + 3.0F;
            int height = Math.round(wrapped.get(i).size() * rowH);
            if (ly + height < top || ly > bottom) {
                continue;
            }
            boolean hover = lyrics.synced() && Draw.inside(mouseX, mouseY, x - 8, (int) ly - 3, w + 16, height + 4) && mouseY >= top && mouseY < bottom;
            int color = lyrics.synced() ? lyricColor(i, current, glow, accent, true) : 0xF0FFFFFF;
            if (hover && i != current) {
                Draw.rounded(g, x - 8, (int) ly - 3, w + 16, height + 4, 6, 0x14FFFFFF);
                color = Draw.lerpArgb(color, 0xFFFFFFFF, 0.5F);
            }
            for (int r = 0; r < wrapped.get(i).size(); r++) {
                float ry = ly + r * rowH;
                float edge = Math.min(ry - top, bottom - (ry + rowH - 3.0F));
                float fade = Mth.clamp((edge + 6.0F) / 22.0F, 0.0F, 1.0F);
                int alpha = Math.round((color >>> 24) * fade);
                if (alpha < 8) {
                    continue;
                }
                g.pose().pushPose();
                g.pose().translate(x, ry, 0.0F);
                g.pose().scale(scale, scale, 1.0F);
                g.drawString(this.font, wrapped.get(i).get(r), 0, 0, (alpha << 24) | (color & 0xFFFFFF), i == current && glow > 0.5F);
                g.pose().popPose();
            }
            if (hover) {
                long time = lyrics.lines().get(i).timeMs();
                this.hit(x - 8, Math.max(top, (int) ly - 3), w + 16, Math.min(height + 4, bottom - Math.max(top, (int) ly - 3)), () -> Sonora.controller().seek(time));
            }
        }
        g.disableScissor();
        if (lyrics.synced()) {
            String hint = Sonora.text(millis < this.manualUntil ? "sonora.lyrics.follow" : "sonora.lyrics.click_hint");
            float hw = this.font.width(hint) * 0.75F;
            Draw.scaledText(g, this.font, hint, x + w - hw, bottom + 6, 0.75F, 0x80FFFFFF, false);
        }
    }

    private static int lyricColor(int index, int current, float glow, int accent, boolean panel) {
        int future = panel ? Colors.argb(0xFF, Colors.mix(accent, 0x000000, 0.78F)) : 0x6CFFFFFF;
        int past = panel ? 0xC8FFFFFF : 0xA0FFFFFF;
        if (index == current) {
            return Draw.lerpArgb(future, 0xFFFFFFFF, glow);
        }
        if (index == current - 1) {
            return Draw.lerpArgb(0xFFFFFFFF, past, glow);
        }
        return index < current ? past : future;
    }

    private void renderList(GuiGraphics g, int mx, int my, int mw, int mh, int mouseX, int mouseY) {
        int accent = MiniPlayer.accent();
        int headerY = my + 4;
        int x = mx;
        if (this.tab == Tab.SEARCH) {
            int boxW = Math.min(370, mw);
            boolean focused = this.searchBox != null && this.searchBox.isFocused();
            Draw.rounded(g, mx, my + 1, boxW, 26, 13, focused ? 0xFF36363C : 0xFF2A2A2E);
            Draw.icon(g, Draw.Icon.SEARCH, mx + 8, my + 7, 13, focused ? 0xFFFFFFFF : 0xFFB3B3B3);
            if (this.searchBox != null && !this.searchBox.getValue().isEmpty()) {
                boolean hover = Draw.inside(mouseX, mouseY, mx + boxW - 24, my + 4, 20, 20);
                Draw.icon(g, Draw.Icon.CLOSE, mx + boxW - 19, my + 9, 10, hover ? 0xFFFFFFFF : 0xFF9A9AA0);
                this.hit(mx + boxW - 24, my + 4, 20, 20, () -> {
                    this.searchBox.setValue("");
                    this.setFocused(this.searchBox);
                    this.searchBox.setFocused(true);
                });
            }
            headerY = my + 40;
        }
        if (!this.history.isEmpty()) {
            boolean hover = Draw.inside(mouseX, mouseY, x, headerY - 2, 20, 20);
            Draw.rounded(g, x, headerY - 2, 20, 20, 10, hover ? 0x50FFFFFF : 0x25FFFFFF);
            Draw.icon(g, Draw.Icon.PREV, x + 4, headerY + 2, 12, 0xFFFFFFFF);
            this.hit(x, headerY - 2, 20, 20, this::back);
            if (hover) {
                this.tip("sonora.tip.back", x + 10, headerY - 2);
            }
            x += 28;
        }
        boolean showTitle = this.tab != Tab.SEARCH || !this.history.isEmpty() || !this.items.isEmpty();
        if (showTitle) {
            Draw.scaledText(g, this.font, Draw.ellipsize(this.font, this.listTitle, (int) ((mw - 170) / 1.5F)), x, headerY, 1.5F, 0xFFFFFFFF, true);
            int count = 0;
            for (BrowseItem item : this.items) {
                if (item.kind() != BrowseItem.Kind.HEADER) {
                    count++;
                }
            }
            if (count > 0 && !this.loading) {
                Draw.scaledText(g, this.font, Sonora.text("sonora.list.count", count), x, headerY + 15, 0.75F, 0xFF8A8A90, false);
            }
        }
        int actionX = mx + mw;
        if (this.container != null) {
            actionX -= 30;
            boolean hover = Draw.inside(mouseX, mouseY, actionX, headerY - 5, 26, 26);
            Draw.rounded(g, actionX, headerY - 5, 26, 26, 13, hover ? Colors.argb(0xFF, Colors.mix(accent, 0xFFFFFF, 0.2F)) : Colors.argb(0xFF, accent));
            Draw.icon(g, Draw.Icon.PLAY, actionX + 7, headerY + 2, 12, 0xFF000000);
            BrowseItem playlist = this.container;
            this.hit(actionX, headerY - 5, 26, 26, () -> Sonora.controller().playSpotify(playlist, List.of(), null));
            if (hover) {
                this.tip("sonora.tip.play_all", actionX + 13, headerY - 5);
            }
        }
        if (this.tab == Tab.LOCAL) {
            actionX = this.pill(g, actionX, headerY - 3, Draw.Icon.FOLDER, Sonora.text("sonora.local.open"), mouseX, mouseY, () -> {
                try {
                    java.nio.file.Files.createDirectories(LocalLibrary.folders().get(0));
                } catch (Exception ignored) {
                }
                Util.getPlatform().openFile(LocalLibrary.folders().get(0).toFile());
            });
            actionX = this.pill(g, actionX, headerY - 3, Draw.Icon.REPEAT, Sonora.text("sonora.local.rescan"), mouseX, mouseY, LocalLibrary::rescan);
        }
        if (this.tab == Tab.QUEUE || this.tab == Tab.LIBRARY && this.container == null) {
            this.pill(g, actionX, headerY - 3, Draw.Icon.REPEAT, Sonora.text("sonora.list.refresh"), mouseX, mouseY, () -> {
                this.items = List.of();
                this.loadTab();
            });
        }
        this.listX = mx;
        this.listY = headerY + (showTitle ? 30 : 4);
        this.listW = mw;
        this.listH = my + mh - this.listY;
        if (!this.listError.isEmpty() && !this.loading && (this.tab == Tab.LIBRARY || this.tab == Tab.SEARCH || this.tab == Tab.QUEUE)) {
            this.renderErrorBanner(g, mx, mw, mouseX, mouseY);
            if (this.items.isEmpty()) {
                return;
            }
        }
        if (this.loading) {
            float pulse = 0.5F + 0.5F * (float) Math.sin(Util.getMillis() / 220.0);
            for (int i = 0; i * ROW < this.listH - ROW && i < 9; i++) {
                int ry = this.listY + i * ROW;
                int alpha = Math.round((0x10 + 0x10 * pulse) * (1.0F - i / 10.0F));
                Draw.rounded(g, mx + 30, ry + 3, 22, 22, 4, Colors.argb(alpha, 0xFFFFFF));
                Draw.rounded(g, mx + 60, ry + 6, Math.min(220, mw / 3) - (i % 3) * 30, 7, 3, Colors.argb(alpha, 0xFFFFFF));
                Draw.rounded(g, mx + 60, ry + 17, Math.min(140, mw / 5) - (i % 2) * 24, 5, 2, Colors.argb(alpha * 2 / 3, 0xFFFFFF));
            }
            return;
        }
        if (this.items.isEmpty()) {
            String key = switch (this.tab) {
                case LIBRARY, QUEUE -> SpotifyAuth.loggedIn() || this.tab == Tab.QUEUE ? "sonora.list.empty" : "sonora.now.login_hint";
                case SEARCH -> this.lastSearch.isEmpty() ? "sonora.search.prompt" : "sonora.list.empty";
                case LOCAL -> LocalLibrary.scanning() ? "sonora.list.loading" : "sonora.local.empty";
                default -> "sonora.list.empty";
            };
            this.emptyState(g, this.tab.icon, Sonora.text(key, LocalLibrary.folders().get(0).toString()), mx + mw / 2, this.listY + 24, mw);
            return;
        }
        int total = this.items.size() * ROW;
        this.scroll = Mth.clamp(this.scroll, 0, Math.max(0, total - this.listH));
        Draw.scissor(g, mx, this.listY, mx + mw + 10, this.listY + this.listH);
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
                Draw.scaledText(g, this.font, item.title().toUpperCase(Locale.ROOT), mx + 4, ry + 15, 0.75F, Colors.argb(0xFF, Colors.mix(accent, 0xFFFFFF, 0.3F)), false);
                g.fill(mx + 8 + Math.round(this.font.width(item.title().toUpperCase(Locale.ROOT)) * 0.75F), ry + 18, mx + mw, ry + 19, 0x14FFFFFF);
                continue;
            }
            boolean hover = Draw.inside(mouseX, mouseY, mx, ry, mw, ROW) && mouseY >= this.listY && mouseY < this.listY + this.listH;
            String uri = item.local() != null ? item.id() : item.uri();
            boolean playing = !uri.isEmpty() && uri.equals(currentUri);
            if (hover || playing) {
                Draw.rounded(g, mx, ry + 1, mw, ROW - 2, 6, hover ? 0x22FFFFFF : 0x10FFFFFF);
            }
            int textX = mx + 34;
            if (item.kind() == BrowseItem.Kind.TRACK || item.kind() == BrowseItem.Kind.LOCAL) {
                if (hover) {
                    Draw.icon(g, Draw.Icon.PLAY, mx + 7, ry + 8, 12, 0xFFFFFFFF);
                } else if (playing) {
                    Draw.bars(g, mx + 9, ry + 9, 9, now.playing(), Colors.argb(0xFF, accent));
                } else {
                    g.drawCenteredString(this.font, String.valueOf(number), mx + 13, ry + 10, 0xFF8A8A8A);
                }
                textX = mx + 28;
            }
            Artwork.Entry thumb = item.local() != null ? Artwork.get(item.artKey(), () -> item.local().meta().artwork()) : Artwork.get(item.artKey(), null);
            if (item.kind() == BrowseItem.Kind.LIKED) {
                Draw.roundedGradient(g, textX, ry + 3, 22, 22, 4, 0xFF5038ED, 0xFF8DB4E8);
                Draw.icon(g, Draw.Icon.HEART_FILLED, textX + 5, ry + 8, 12, 0xFFFFFFFF);
            } else {
                Draw.art(g, thumb, textX, ry + 3, 22, 4, 0x00000000);
            }
            int infoX = textX + 30;
            int durationW = item.durationMs() > 0 ? 40 : 0;
            int infoW = mw - (infoX - mx) - durationW - 8;
            g.drawString(this.font, Draw.ellipsize(this.font, item.title(), infoW), infoX, ry + 5, playing ? Colors.argb(0xFF, accent) : 0xFFFFFFFF, false);
            g.drawString(this.font, Draw.ellipsize(this.font, item.subtitle(), infoW), infoX, ry + 16, 0xFF9A9A9A, false);
            if (item.durationMs() > 0) {
                String duration = Draw.time(item.durationMs());
                g.drawString(this.font, duration, mx + mw - 8 - this.font.width(duration), ry + 10, 0xFF8A8A8A, false);
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
            Draw.rounded(g, mx + mw + 5, barY, 3, barH, 1, 0x50FFFFFF);
        }
    }

    private int pill(GuiGraphics g, int right, int y, Draw.Icon icon, String text, int mouseX, int mouseY, Runnable action) {
        int w = this.font.width(text) + 26;
        int x = right - w - 6;
        boolean hover = Draw.inside(mouseX, mouseY, x, y, w, 20);
        Draw.rounded(g, x, y, w, 20, 10, hover ? 0x50FFFFFF : 0x22FFFFFF);
        Draw.icon(g, icon, x + 7, y + 4, 12, 0xFFFFFFFF);
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
        String state = Sonora.text(config.eqEnabled ? "sonora.eq.on" : "sonora.eq.off");
        int toggleW = this.font.width(state) + 40;
        int toggleX = mx + mw - toggleW;
        boolean hover = Draw.inside(mouseX, mouseY, toggleX, my, toggleW, 20);
        Draw.rounded(g, toggleX, my, toggleW, 20, 10, hover ? 0x30FFFFFF : 0x18FFFFFF);
        g.drawString(this.font, state, toggleX + 10, my + 6, 0xFFFFFFFF, false);
        Draw.toggle(g, toggleX + toggleW - 28, my + 4, config.eqEnabled, accent);
        this.hit(toggleX, my, toggleW, 20, () -> {
            config.eqEnabled = !config.eqEnabled;
            Sonora.controller().applyEqualizer();
            SonoraConfig.save();
        });
        int px = mx;
        int py = my + 30;
        for (String preset : Equalizer.PRESETS) {
            String label = Sonora.text("sonora.eq.preset." + preset);
            int w = this.font.width(label) + 16;
            if (px + w > mx + mw) {
                px = mx;
                py += 24;
            }
            boolean selected = preset.equals(config.eqPreset);
            boolean over = Draw.inside(mouseX, mouseY, px, py, w, 18);
            Draw.rounded(g, px, py, w, 18, 9, selected ? Colors.argb(0xFF, accent) : over ? 0x40FFFFFF : 0x1EFFFFFF);
            g.drawString(this.font, label, px + 8, py + 5, selected ? 0xFF000000 : 0xFFFFFFFF, false);
            this.hit(px, py, w, 18, () -> {
                config.eqPreset = preset;
                config.eqGains = Equalizer.preset(preset);
                Sonora.controller().applyEqualizer();
                SonoraConfig.save();
            });
            px += w + 6;
        }
        int top = py + 36;
        int height = Math.max(60, mh - (top - my) - 28);
        int bands = Equalizer.FREQUENCIES.length + 1;
        int slot = Math.min(52, mw / bands);
        int startX = mx + (mw - slot * bands) / 2;
        Draw.rounded(g, startX - 8, top - 18, slot * bands + 16, height + 42, 10, 0x14FFFFFF);
        float[] bars = Visualizer.resample(Sonora.controller().engine().spectrum().bars(), 60);
        Visualizer.draw(g, bars, startX, top, slot * bands, height, accent, "bars", 0x22);
        int zero = top + height / 2;
        g.fill(startX, zero, startX + slot * bands, zero + 1, 0x18FFFFFF);
        for (int band = -1; band < Equalizer.FREQUENCIES.length; band++) {
            int cx = startX + (band + 1) * slot + slot / 2;
            float value = band < 0 ? config.eqPreamp : config.eqGains[band];
            Draw.rounded(g, cx - 2, top, 4, height, 2, 0x30FFFFFF);
            int knobY = top + Math.round((12.0F - value) / 24.0F * height);
            int lo = Math.min(zero, knobY);
            int hi = Math.max(zero, knobY);
            g.fill(cx - 2, lo, cx + 2, hi, Colors.argb(config.eqEnabled ? 0xFF : 0x80, band < 0 ? 0xFFFFFF : accent));
            boolean over = Draw.inside(mouseX, mouseY, cx - slot / 2, top - 6, slot, height + 12);
            boolean active = over || this.drag == band || (band < 0 && this.drag == DRAG_PREAMP);
            Draw.rounded(g, cx - 6, knobY - 6, 12, 12, 6, active ? 0xFFFFFFFF : 0xFFD8D8D8);
            String label = band < 0 ? Sonora.text("sonora.eq.preamp") : Equalizer.LABELS[band];
            g.drawCenteredString(this.font, label, cx, top + height + 9, active ? 0xFFFFFFFF : 0xFFA0A0A6);
            String db = String.format("%+.0f", value);
            Draw.scaledText(g, this.font, db, cx - this.font.width(db) * 0.375F, top - 13, 0.75F, active ? 0xFFFFFFFF : 0xFF8A8A90, false);
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
        int rowW = Math.min(mw, 460);
        this.contentY = my + 30;
        this.contentH = mh - 30;
        this.settingsScroll = Mth.clamp(this.settingsScroll, 0, Math.max(0, this.settingsHeight - this.contentH));
        int y = this.contentY - (int) this.settingsScroll;
        int start = y;
        Draw.scissor(g, mx - 4, this.contentY, mx + rowW + 12, this.contentY + this.contentH);
        y = this.section(g, mx, y, "sonora.section.interface");
        int scale = SonoraScreen.scale();
        String scaleText = config.uiScale <= 0 ? Sonora.text("sonora.scale.auto", SonoraScreen.autoScale()) : config.uiScale + "×";
        y = this.choice(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.ui_scale", scaleText, direction -> {
            int max = SonoraScreen.maxScale();
            int next = Math.floorMod(config.uiScale + direction, max + 1);
            config.uiScale = next;
            if (SonoraScreen.scale() != scale) {
                this.minecraft.execute(this::rebuildWidgets);
            }
        });
        y = this.toggle(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.accent", config.accentFromArtwork, () -> config.accentFromArtwork = !config.accentFromArtwork);
        y = this.section(g, mx, y, "sonora.section.lyrics");
        y = this.toggle(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.show_lyrics", config.showLyrics, () -> config.showLyrics = !config.showLyrics);
        y = this.section(g, mx, y, "sonora.section.mini");
        y = this.toggle(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.mini", config.miniPlayer, () -> config.miniPlayer = !config.miniPlayer);
        y = this.choice(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.corner", Sonora.text("sonora.corner." + config.miniCorner),
                direction -> config.miniCorner = cycle(new String[]{"top_right", "top_left", "bottom_right", "bottom_left"}, config.miniCorner, direction));
        y = this.choice(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.scale", Math.round(config.miniScale * 100) + "%",
                direction -> config.miniScale = cycleFloat(new float[]{0.5F, 0.6F, 0.75F, 0.9F, 1.0F, 1.25F}, config.miniScale, direction));
        y = this.toggle(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.mini_visualizer", config.miniVisualizer, () -> config.miniVisualizer = !config.miniVisualizer);
        y = this.section(g, mx, y, "sonora.section.visualizer");
        y = this.choice(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.style", Sonora.text("sonora.style." + config.visualizerStyle),
                direction -> config.visualizerStyle = cycle(new String[]{"bars", "mirror", "wave", "dots"}, config.visualizerStyle, direction));
        y = this.choice(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.bars", String.valueOf(config.visualizerBars), direction -> {
            int[] values = {24, 32, 48, 64, 96};
            int index = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == config.visualizerBars) {
                    index = i;
                }
            }
            config.visualizerBars = values[Math.floorMod(index + direction, values.length)];
            Sonora.controller().engine().setBarCount(config.visualizerBars);
        });
        y = this.section(g, mx, y, "sonora.section.spotify");
        y = this.choice(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.bitrate", config.bitrate + " kbps", direction -> {
            int[] values = {96, 160, 320};
            int index = config.bitrate == 96 ? 0 : config.bitrate == 160 ? 1 : 2;
            config.bitrate = values[Math.floorMod(index + direction, values.length)];
            Librespot.restart();
        });
        y = this.toggle(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.normalize", config.normalize, () -> {
            config.normalize = !config.normalize;
            Librespot.restart();
        });
        y = this.link(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.account", SpotifyAuth.loggedIn() ? accountName() : Sonora.text("sonora.button.login"),
                () -> this.minecraft.setScreen(new LoginScreen(this)));
        y = this.link(g, mx, y, rowW, mouseX, mouseY, "sonora.settings.client", Sonora.text(SpotifyAuth.ownClient() ? "sonora.settings.client_on" : "sonora.settings.client_off"),
                () -> this.minecraft.setScreen(new ClientIdScreen(this)));
        g.disableScissor();
        this.settingsHeight = y - start + 6;
        if (this.settingsHeight > this.contentH) {
            int barH = Math.max(20, this.contentH * this.contentH / this.settingsHeight);
            int barY = this.contentY + (int) ((this.contentH - barH) * this.settingsScroll / Math.max(1, this.settingsHeight - this.contentH));
            Draw.rounded(g, mx + rowW + 6, barY, 3, barH, 1, 0x50FFFFFF);
        }
    }

    private boolean visible(int y, int h) {
        return y + h > this.contentY && y < this.contentY + this.contentH;
    }

    private int section(GuiGraphics g, int x, int y, String key) {
        y += 6;
        Draw.scaledText(g, this.font, Sonora.text(key).toUpperCase(Locale.ROOT), x + 2, y + 4, 0.75F, Colors.argb(0xFF, Colors.mix(MiniPlayer.accent(), 0xFFFFFF, 0.35F)), false);
        return y + 14;
    }

    private int row(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY, String key) {
        boolean hover = Draw.inside(mouseX, mouseY, x, y, w, 22) && this.visible(y, 22) && mouseY >= this.contentY && mouseY < this.contentY + this.contentH;
        Draw.rounded(g, x, y, w, 22, 6, hover ? 0x26FFFFFF : 0x12FFFFFF);
        g.drawString(this.font, Sonora.text(key), x + 10, y + 7, 0xFFFFFFFF, false);
        return hover ? 1 : 0;
    }

    private int toggle(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY, String key, boolean value, Runnable action) {
        this.row(g, x, y, w, mouseX, mouseY, key);
        Draw.toggle(g, x + w - 32, y + 5, value, MiniPlayer.accent());
        if (this.visible(y, 22)) {
            this.hit(x, Math.max(y, this.contentY), w, Math.min(y + 22, this.contentY + this.contentH) - Math.max(y, this.contentY), () -> {
                action.run();
                SonoraConfig.save();
            });
        }
        return y + 25;
    }

    private interface Step {
        void apply(int direction);
    }

    private int choice(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY, String key, String value, Step step) {
        boolean hover = this.row(g, x, y, w, mouseX, mouseY, key) == 1;
        int valueW = this.font.width(value);
        int right = x + w - 10;
        int color = Colors.argb(0xFF, Colors.mix(MiniPlayer.accent(), 0xFFFFFF, 0.25F));
        g.drawString(this.font, "›", right - 4, y + 7, hover ? 0xFFFFFFFF : 0xFF8A8A90, false);
        g.drawString(this.font, value, right - 12 - valueW, y + 7, color, false);
        g.drawString(this.font, "‹", right - 20 - valueW, y + 7, hover ? 0xFFFFFFFF : 0xFF8A8A90, false);
        if (this.visible(y, 22)) {
            int hy = Math.max(y, this.contentY);
            int hh = Math.min(y + 22, this.contentY + this.contentH) - hy;
            int arrowX = right - 26 - valueW;
            this.hit(x, hy, w, hh, () -> {
                step.apply(1);
                SonoraConfig.save();
            }, () -> {
                step.apply(-1);
                SonoraConfig.save();
            });
            this.hit(arrowX, hy, 14, hh, () -> {
                step.apply(-1);
                SonoraConfig.save();
            });
        }
        return y + 25;
    }

    private int link(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY, String key, String value, Runnable action) {
        boolean hover = this.row(g, x, y, w, mouseX, mouseY, key) == 1;
        String text = Draw.ellipsize(this.font, value, w / 2);
        g.drawString(this.font, text, x + w - 20 - this.font.width(text), y + 7, 0xFFB3B3B3, false);
        g.drawString(this.font, "›", x + w - 14, y + 7, hover ? 0xFFFFFFFF : 0xFF8A8A90, false);
        if (this.visible(y, 22)) {
            int hy = Math.max(y, this.contentY);
            this.hit(x, hy, w, Math.min(y + 22, this.contentY + this.contentH) - hy, action);
        }
        return y + 25;
    }

    private static String cycle(String[] values, String current, int direction) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                return values[Math.floorMod(i + direction, values.length)];
            }
        }
        return values[0];
    }

    private static float cycleFloat(float[] values, float current, int direction) {
        int best = 0;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - current) < Math.abs(values[best] - current)) {
                best = i;
            }
        }
        return values[Math.floorMod(best + direction, values.length)];
    }

    private void renderBar(GuiGraphics g, MusicController.NowPlaying now, Artwork.Entry art, int mouseX, int mouseY) {
        int top = this.height - this.barH;
        int accent = MiniPlayer.accent();
        g.fill(0, top, this.width, this.height, 0xF40A0A0C);
        g.fill(0, top, this.width, top + 1, 0x1AFFFFFF);
        MusicController controller = Sonora.controller();
        TrackInfo track = now.track();
        int leftW = Mth.clamp(this.width / 2 - 170, 120, 300);
        Draw.art(g, art, 12, top + 10, 40, 5, 0xF40A0A0C);
        if (track != null) {
            boolean spotify = now.mode() == MusicController.Mode.SPOTIFY;
            int textW = leftW - (spotify ? 84 : 66);
            Draw.slide(g, this.font, track.title(), 60, top + 16, textW, 1.0F, 0xFFFFFFFF, false);
            g.drawString(this.font, Draw.ellipsize(this.font, track.artistLine(), textW), 60, top + 29, 0xFFA0A0A0, false);
            if (spotify) {
                int hx = 60 + textW + 8;
                this.control(g, hx, top + 21, 13, now.liked() ? Draw.Icon.HEART_FILLED : Draw.Icon.HEART,
                        now.liked() ? Colors.argb(0xFF, Draw.GREEN) : 0xFFA0A0A0, mouseX, mouseY, now.liked() ? "sonora.tip.unlike" : "sonora.tip.like", controller::toggleLike);
            }
        }
        int cx = this.width / 2;
        int cy = top + 21;
        boolean shuffleOn = now.shuffle();
        this.control(g, cx - 70, cy - 7, 14, Draw.Icon.SHUFFLE, shuffleOn ? Colors.argb(0xFF, accent) : 0xFFB3B3B3, mouseX, mouseY, "sonora.tip.shuffle", controller::toggleShuffle);
        this.control(g, cx - 40, cy - 7, 14, Draw.Icon.PREV, 0xFFDDDDDD, mouseX, mouseY, "sonora.tip.previous", controller::previous);
        boolean playHover = Draw.inside(mouseX, mouseY, cx - 13, cy - 13, 26, 26);
        Draw.rounded(g, cx - 13, cy - 13, 26, 26, 13, playHover ? 0xFFFFFFFF : 0xFFEDEDED);
        Draw.icon(g, now.playing() ? Draw.Icon.PAUSE : Draw.Icon.PLAY, cx - 7, cy - 7, 14, 0xFF000000);
        this.hit(cx - 13, cy - 13, 26, 26, controller::togglePlay);
        if (playHover) {
            this.tip(now.playing() ? "sonora.tip.pause" : "sonora.tip.play", cx, cy - 13);
        }
        this.control(g, cx + 26, cy - 7, 14, Draw.Icon.NEXT, 0xFFDDDDDD, mouseX, mouseY, "sonora.tip.next", controller::next);
        boolean repeatOn = !"off".equals(now.repeat());
        this.control(g, cx + 56, cy - 7, 14, "track".equals(now.repeat()) ? Draw.Icon.REPEAT_ONE : Draw.Icon.REPEAT,
                repeatOn ? Colors.argb(0xFF, accent) : 0xFFB3B3B3, mouseX, mouseY, "sonora.tip.repeat." + now.repeat(), controller::cycleRepeat);
        if (shuffleOn) {
            g.fill(cx - 64, cy + 9, cx - 62, cy + 11, Colors.argb(0xFF, accent));
        }
        if (repeatOn) {
            g.fill(cx + 62, cy + 9, cx + 64, cy + 11, Colors.argb(0xFF, accent));
        }
        int rightW = 196;
        int barW = Mth.clamp(this.width - 2 * Math.max(leftW, rightW) - 90, 120, 460);
        int bx = cx - barW / 2;
        int by = top + 44;
        long duration = track == null ? 0 : track.durationMs();
        long position = this.drag == DRAG_SEEK ? (long) (this.dragValue * duration) : now.position();
        String left = Draw.time(position);
        String right = Draw.time(duration);
        g.drawString(this.font, left, bx - 8 - this.font.width(left), by - 3, 0xFFA0A0A0, false);
        g.drawString(this.font, right, bx + barW + 8, by - 3, 0xFFA0A0A0, false);
        boolean seekHover = Draw.inside(mouseX, mouseY, bx, by - 5, barW, 12) || this.drag == DRAG_SEEK;
        Draw.rounded(g, bx, by - 1, barW, 4, 2, 0x40FFFFFF);
        float progress = duration > 0 ? Mth.clamp((float) position / duration, 0.0F, 1.0F) : 0.0F;
        int filled = Math.round(barW * progress);
        Draw.rounded(g, bx, by - 1, Math.max(4, filled), 4, 2, seekHover ? Colors.argb(0xFF, accent) : 0xFFFFFFFF);
        if (seekHover) {
            Draw.rounded(g, bx + filled - 5, by - 4, 10, 10, 5, 0xFFFFFFFF);
            if (duration > 0 && this.drag != DRAG_SEEK) {
                float at = (float) Mth.clamp((mouseX - bx) / (double) barW, 0.0, 1.0);
                this.tip = Draw.time((long) (at * duration));
                this.tipX = mouseX;
                this.tipY = by - 6;
            }
        }
        this.seekInfo = new int[]{bx, barW};
        this.hit(bx, by - 5, barW, 12, () -> {
            if (duration > 0) {
                this.drag = DRAG_SEEK;
                this.dragValue = (float) Mth.clamp((this.lastMouseX - this.seekInfo[0]) / this.seekInfo[1], 0.0, 1.0);
            }
        });
        int volumeW = 80;
        int vx = this.width - volumeW - 16;
        int vy = top + 30;
        float volume = this.drag == DRAG_VOLUME ? this.dragValue : controller.engine().volume();
        this.control(g, vx - 22, vy - 7, 14, volume <= 0.001F ? Draw.Icon.VOLUME_MUTE : Draw.Icon.VOLUME, 0xFFB3B3B3, mouseX, mouseY,
                volume <= 0.001F ? "sonora.tip.unmute" : "sonora.tip.mute", () -> {
                    controller.setVolume(controller.engine().volume() > 0.0F ? 0.0F : 0.6F);
                    SonoraConfig.save();
                });
        boolean volumeHover = Draw.inside(mouseX, mouseY, vx, vy - 5, volumeW, 12) || this.drag == DRAG_VOLUME;
        Draw.rounded(g, vx, vy - 1, volumeW, 4, 2, 0x40FFFFFF);
        Draw.rounded(g, vx, vy - 1, Math.max(4, Math.round(volumeW * volume)), 4, 2, volumeHover ? Colors.argb(0xFF, accent) : 0xFFFFFFFF);
        if (volumeHover) {
            Draw.rounded(g, vx + Math.round(volumeW * volume) - 5, vy - 4, 10, 10, 5, 0xFFFFFFFF);
            this.tip = Math.round(volume * 100) + "%";
            this.tipX = vx + Math.round(volumeW * volume);
            this.tipY = vy - 6;
        }
        this.volumeInfo = new int[]{vx, volumeW};
        this.hit(vx, vy - 5, volumeW, 12, () -> {
            this.drag = DRAG_VOLUME;
            this.dragValue = (float) Mth.clamp((this.lastMouseX - this.volumeInfo[0]) / this.volumeInfo[1], 0.0, 1.0);
            controller.setVolume(this.dragValue);
        });
        int ix = vx - 48;
        if (now.mode() == MusicController.Mode.SPOTIFY && SpotifyAuth.loggedIn()) {
            this.control(g, ix, vy - 7, 14, Draw.Icon.DEVICE, now.inGame() ? Colors.argb(0xFF, Draw.GREEN) : 0xFFB3B3B3, mouseX, mouseY,
                    now.inGame() ? "sonora.tip.device_game" : "sonora.tip.device", controller::listenInGame);
            ix -= 26;
        }
        boolean queueOn = this.tab == Tab.QUEUE;
        this.control(g, ix, vy - 7, 14, Draw.Icon.QUEUE, queueOn ? Colors.argb(0xFF, accent) : 0xFFB3B3B3, mouseX, mouseY, "sonora.tip.queue",
                () -> this.switchTab(queueOn ? Tab.NOW : Tab.QUEUE));
        ix -= 26;
        boolean lyricsOn = this.tab == Tab.LYRICS;
        this.control(g, ix, vy - 7, 14, Draw.Icon.LYRICS, lyricsOn ? Colors.argb(0xFF, accent) : 0xFFB3B3B3, mouseX, mouseY, "sonora.tip.lyrics", this::toggleLyricsTab);
    }

    private void control(GuiGraphics g, int x, int y, int size, Draw.Icon icon, int color, int mouseX, int mouseY, String tip, Runnable action) {
        boolean hover = Draw.inside(mouseX, mouseY, x - 4, y - 4, size + 8, size + 8);
        if (hover) {
            Draw.rounded(g, x - 4, y - 4, size + 8, size + 8, (size + 8) / 2, 0x18FFFFFF);
            this.tip(tip, x + size / 2, y - 4);
        }
        Draw.icon(g, icon, x, y, size, hover && (color == 0xFFB3B3B3 || color == 0xFFDDDDDD || color == 0xFFA0A0A0) ? 0xFFFFFFFF : color);
        this.hit(x - 4, y - 4, size + 8, size + 8, action);
    }

    @Override
    protected boolean clicked(double mouseX, double mouseY, int button) {
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
        return super.clicked(mouseX, mouseY, button);
    }

    @Override
    protected boolean dragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
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
        return super.dragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    protected boolean released(double mouseX, double mouseY, int button) {
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
        return super.released(mouseX, mouseY, button);
    }

    @Override
    protected boolean scrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        switch (this.tab) {
            case NOW -> {
                this.plainLyricScroll -= scrollY * 22;
                return true;
            }
            case LYRICS -> {
                this.fullManual -= scrollY * 36;
                this.manualUntil = Util.getMillis() + 4000L;
                return true;
            }
            case SETTINGS -> {
                this.settingsScroll -= scrollY * 30;
                return true;
            }
            default -> {
            }
        }
        if (Draw.inside(mouseX, mouseY, this.listX, this.listY, this.listW, this.listH)) {
            this.scroll -= scrollY * ROW * 1.5;
            return true;
        }
        return super.scrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void moved(double mouseX, double mouseY) {
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
                case GLFW.GLFW_KEY_L -> {
                    this.toggleLyricsTab();
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
