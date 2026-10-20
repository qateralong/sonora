package com.qateralong.sonora.ui;

import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.SonoraConfig;
import com.qateralong.sonora.lyrics.Lyrics;
import com.qateralong.sonora.player.MusicController;
import com.qateralong.sonora.player.TrackInfo;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectInstance;

public final class MiniPlayer {
    private static final int WIDTH = 188;
    private static final int BASE_HEIGHT = 54;
    private static final int LYRIC_HEIGHT = 15;
    private static final long CHANGE_MS = 380L;

    private static int accent = Draw.GREEN;
    private static int dark = 0x0E1A12;
    private static float shown;
    private static float lyricShown;
    private static long lastFrame;
    private static MusicController.NowPlaying lastNow;
    private static String currentId = "";
    private static String prevTitle = "";
    private static String prevArtist = "";
    private static long trackChanged;
    private static String currentLyric = "";
    private static String prevLyric = "";
    private static long lyricChanged;
    private static float[] pulse = new float[3];

    private MiniPlayer() {
    }

    public static int accent() {
        return accent;
    }

    public static int dark() {
        return dark;
    }

    public static void updateAccent(Artwork.Entry art) {
        int targetAccent = art != null && SonoraConfig.get().accentFromArtwork ? art.accent() : Draw.GREEN;
        int targetDark = art != null && SonoraConfig.get().accentFromArtwork ? art.dark() : 0x0E1A12;
        accent = Colors.mix(accent, targetAccent, 0.08F);
        dark = Colors.mix(dark, targetDark, 0.08F);
    }

    public static Artwork.Entry artwork(TrackInfo track) {
        if (track == null) {
            return null;
        }
        return Artwork.get(track.artKey(), track::artwork);
    }

    private static float approach(float value, float target, float speed, float dt) {
        float step = speed * dt;
        return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        SonoraConfig config = SonoraConfig.get();
        long now = Util.getMillis();
        float dt = lastFrame == 0L ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        boolean hidden = !config.miniPlayer || minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen() || minecraft.screen instanceof PlayerScreen;
        MusicController.NowPlaying state = Sonora.controller().nowPlaying();
        if (state.track() != null) {
            lastNow = state;
        }
        shown = approach(shown, !hidden && state.track() != null ? 1.0F : 0.0F, 3.5F, dt);
        if (shown <= 0.001F || lastNow == null || hidden) {
            if (hidden) {
                shown = 0.0F;
            }
            return;
        }
        MusicController.NowPlaying current = state.track() != null ? state : lastNow;
        TrackInfo track = current.track();
        if (!track.id().equals(currentId)) {
            if (!currentId.isEmpty() && lastNow != null) {
                trackChanged = now;
            }
            currentId = track.id();
        }
        Artwork.Entry art = artwork(track);
        updateAccent(art);
        Font font = minecraft.font;
        String lyricLine = lyricLine(config, current);
        if (!lyricLine.equals(currentLyric)) {
            prevLyric = currentLyric;
            currentLyric = lyricLine;
            lyricChanged = now;
        }
        lyricShown = approach(lyricShown, lyricLine.isEmpty() ? 0.0F : 1.0F, 5.0F, dt);
        float scale = Math.max(0.5F, Math.min(2.0F, config.miniScale));
        int height = BASE_HEIGHT + Math.round(LYRIC_HEIGHT * Draw.ease(lyricShown));
        int scaledW = Math.round(WIDTH * scale);
        int scaledH = Math.round(height * scale);
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        int margin = 4;
        boolean right = config.miniCorner.endsWith("right");
        boolean bottom = config.miniCorner.startsWith("bottom");
        float slide = (1.0F - Draw.ease(shown)) * (scaledW + margin + 4);
        float x = right ? screenW - scaledW - margin + slide : margin - slide;
        float y = bottom ? screenH - scaledH - margin - 22 : margin;
        if (!bottom && right && minecraft.player != null) {
            boolean beneficial = false;
            boolean harmful = false;
            for (MobEffectInstance effect : minecraft.player.getActiveEffects()) {
                if (effect.showIcon()) {
                    if (effect.getEffect().value().isBeneficial()) {
                        beneficial = true;
                    } else {
                        harmful = true;
                    }
                }
            }
            y += harmful ? 52 : beneficial ? 26 : 0;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        int top = Colors.argb(0xEC, Colors.mix(dark, 0x141416, 0.35F));
        int bottomColor = 0xEC0B0B0D;
        Draw.roundedGradient(g, 0, 0, WIDTH, height, 8, top, bottomColor);
        g.fill(8, 0, WIDTH - 8, 1, 0x18FFFFFF);
        int textX = 54;
        int textW = WIDTH - textX - 8;
        if (config.miniVisualizer) {
            float[] bars = Visualizer.resample(Sonora.controller().engine().spectrum().bars(), 28);
            Visualizer.draw(g, bars, textX, 30, textW, 11, accent, config.visualizerStyle, 0x70);
        }
        Draw.art(g, art, 6, 6, 40, 6, Colors.argb(0xEC, Colors.mix(dark, 0x141416, 0.35F)));
        Draw.rounded(g, 8, 34, 14, 10, 3, 0xC0000000);
        drawIndicator(g, 10, 35, current.playing());
        float change = trackChanged == 0L ? 1.0F : Math.min(1.0F, (now - trackChanged) / (float) CHANGE_MS);
        float eased = Draw.ease(change);
        Draw.scissor(g, textX, 5, textX + textW, 31);
        if (change < 1.0F) {
            float out = -eased * 14.0F;
            Draw.slide(g, font, prevTitle, textX, 8 + out, textW, 1.0F, Draw.TEXT, true);
            Draw.slide(g, font, prevArtist, textX, 20 + out, textW, 0.9F, Draw.SUBTLE, false);
        }
        float in = (1.0F - eased) * 14.0F;
        Draw.slide(g, font, track.title(), textX, 8 + in, textW, 1.0F, Draw.TEXT, true);
        Draw.slide(g, font, track.artistLine(), textX, 20 + in, textW, 0.9F, Draw.SUBTLE, false);
        g.disableScissor();
        if (change >= 1.0F) {
            prevTitle = track.title();
            prevArtist = track.artistLine();
        }
        if (current.mode() == MusicController.Mode.SPOTIFY && !current.inGame() && !current.device().isEmpty()) {
            String status = Sonora.text("sonora.mini.on_device", current.device());
            Draw.rounded(g, textX - 1, 31, Math.min(textW, Math.round(font.width(status) * 0.75F) + 6), 10, 3, 0xC0000000);
            Draw.scaledText(g, font, Draw.ellipsize(font, status, (int) ((textW - 6) / 0.75F)), textX + 2, 33, 0.75F, 0xFFFFC060, false);
        }
        String elapsed = Draw.time(current.position());
        String total = Draw.time(track.durationMs());
        float elapsedW = font.width(elapsed) * 0.75F;
        float totalW = font.width(total) * 0.75F;
        int timeY = BASE_HEIGHT - 9;
        Draw.scaledText(g, font, elapsed, textX, timeY, 0.75F, Draw.DIM, false);
        Draw.scaledText(g, font, total, WIDTH - 8 - totalW, timeY, 0.75F, Draw.DIM, false);
        int barX = Math.round(textX + elapsedW + 5);
        int barW = Math.round(WIDTH - 8 - totalW - 5) - barX;
        int barY = timeY + 2;
        float progress = track.durationMs() > 0 ? Math.min(1.0F, (float) current.position() / track.durationMs()) : 0.0F;
        Draw.rounded(g, barX, barY, barW, 3, 1, 0x30FFFFFF);
        int filled = Math.max(3, Math.round(barW * progress));
        Draw.rounded(g, barX, barY, filled, 3, 1, Colors.argb(0xFF, accent));
        Draw.rounded(g, barX + filled - 3, barY - 1, 5, 5, 2, Colors.argb(current.playing() ? 0xFF : 0xA0, Colors.mix(accent, 0xFFFFFF, 0.4F)));
        if (lyricShown > 0.01F) {
            int lyricTop = BASE_HEIGHT;
            Draw.scissor(g, 6, lyricTop, WIDTH - 6, height - 2);
            g.fill(8, lyricTop - 1, WIDTH - 8, lyricTop, 0x14FFFFFF);
            float lyricChange = Math.min(1.0F, (now - lyricChanged) / 320.0F);
            float le = Draw.ease(lyricChange);
            int lyricColor = Colors.mix(accent, 0xFFFFFF, 0.6F);
            if (lyricChange < 1.0F && !prevLyric.isEmpty() && Math.round(0xFF * (1.0F - le)) >= 8) {
                Draw.slide(g, font, prevLyric, 8, lyricTop + 3 - le * 12.0F, WIDTH - 16, 0.85F, Colors.argb(Math.round(0xFF * (1.0F - le)), lyricColor), false);
            }
            if (!currentLyric.isEmpty()) {
                Draw.slide(g, font, currentLyric, 8, lyricTop + 3 + (1.0F - le) * 12.0F, WIDTH - 16, 0.85F, Colors.argb(Math.max(8, Math.round(0xFF * le)), lyricColor), false);
            }
            g.disableScissor();
        }
        g.pose().popPose();
    }

    private static String lyricLine(SonoraConfig config, MusicController.NowPlaying current) {
        if (!config.miniLyrics) {
            return "";
        }
        Lyrics.Result lyrics = NowLyrics.get(current.track());
        if (lyrics == null || !lyrics.synced()) {
            return "";
        }
        int index = lyrics.indexAt(current.position() + 250L);
        return index >= 0 ? lyrics.lines().get(index).text() : "";
    }

    private static void drawIndicator(GuiGraphics g, int x, int y, boolean playing) {
        float[] bars = Sonora.controller().engine().spectrum().bars();
        for (int i = 0; i < 3; i++) {
            float target = 0.2F;
            if (playing && bars.length > 0) {
                int from = i * bars.length / 3;
                int to = Math.max(from + 1, (i + 1) * bars.length / 3);
                float sum = 0.0F;
                for (int b = from; b < to; b++) {
                    sum += bars[b];
                }
                target = 0.25F + Math.min(0.75F, sum / (to - from) * 1.6F);
            }
            pulse[i] += (target - pulse[i]) * 0.35F;
            int h = Math.max(1, Math.round(7 * pulse[i]));
            int color = Colors.argb(playing ? 0xFF : 0x90, playing ? accent : 0xA0A0A0);
            g.fill(x + i * 3, y + 7 - h, x + i * 3 + 2, y + 7, color);
        }
    }
}
