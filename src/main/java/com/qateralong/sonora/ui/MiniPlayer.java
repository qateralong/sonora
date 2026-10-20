package com.qateralong.sonora.ui;

import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.SonoraConfig;
import com.qateralong.sonora.lyrics.Lyrics;
import com.qateralong.sonora.player.MusicController;
import com.qateralong.sonora.player.TrackInfo;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectInstance;

public final class MiniPlayer {
    private static int accent = Draw.GREEN;
    private static int dark = 0x0E1A12;

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

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        SonoraConfig config = SonoraConfig.get();
        if (!config.miniPlayer || minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen() || minecraft.screen instanceof PlayerScreen) {
            return;
        }
        MusicController.NowPlaying now = Sonora.controller().nowPlaying();
        TrackInfo track = now.track();
        if (track == null) {
            return;
        }
        Artwork.Entry art = artwork(track);
        updateAccent(art);
        Font font = minecraft.font;
        Lyrics.Result lyrics = config.miniLyrics ? NowLyrics.get(track) : null;
        String lyricLine = null;
        if (lyrics != null && lyrics.synced()) {
            int index = lyrics.indexAt(now.position() + 250L);
            if (index >= 0 && !lyrics.lines().get(index).text().isEmpty()) {
                lyricLine = lyrics.lines().get(index).text();
            }
        }
        float scale = Math.max(0.5F, Math.min(2.0F, config.miniScale));
        int width = 200;
        int height = lyricLine != null ? 70 : 58;
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        int scaledW = Math.round(width * scale);
        int scaledH = Math.round(height * scale);
        int margin = 4;
        int x;
        int y;
        boolean right = config.miniCorner.endsWith("right");
        boolean bottom = config.miniCorner.startsWith("bottom");
        x = right ? screenW - scaledW - margin : margin;
        y = bottom ? screenH - scaledH - margin - 22 : margin;
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
        Draw.roundedGradient(g, 0, 0, width, height, 7, Colors.argb(0xE6, Colors.mix(dark, 0x101012, 0.4F)), 0xE60A0A0C);
        if (config.miniVisualizer) {
            float[] bars = Visualizer.resample(Sonora.controller().engine().spectrum().bars(), 36);
            Visualizer.draw(g, bars, 56, 22, width - 62, height - 34, accent, SonoraConfig.get().visualizerStyle, 0x55);
        }
        int artBg = Colors.argb(0xE6, Colors.mix(dark, 0x101012, 0.4F));
        Draw.art(g, art, 6, 6, 44, 5, artBg);
        Draw.marquee(g, font, track.title(), 56, 8, width - 62, Draw.TEXT, true);
        String artist = track.artistLine();
        String timeText = Draw.time(now.position()) + " / " + Draw.time(track.durationMs());
        int timeWidth = font.width(timeText);
        Draw.scaledText(g, font, timeText, width - 6 - timeWidth * 0.75F, 20.5F, 0.75F, Draw.DIM, false);
        g.drawString(font, Draw.ellipsize(font, artist, width - 66 - (int) (timeWidth * 0.75F)), 56, 19, Draw.SUBTLE, false);
        int barY = 46;
        int barX = 56;
        int barW = width - 62;
        g.fill(barX, barY, barX + barW, barY + 2, 0x40FFFFFF);
        float progress = track.durationMs() > 0 ? Math.min(1.0F, (float) now.position() / track.durationMs()) : 0.0F;
        g.fill(barX, barY, barX + Math.round(barW * progress), barY + 2, Colors.argb(0xFF, accent));
        Draw.icon(g, now.playing() ? Draw.Icon.PLAY : Draw.Icon.PAUSE, 56, 51 - 1, 7, Colors.argb(0xC0, accent));
        String status = now.mode() == MusicController.Mode.LOCAL ? Sonora.text("sonora.mini.local")
                : now.inGame() ? "Spotify" : Sonora.text("sonora.mini.on_device", now.device());
        Draw.scaledText(g, font, Draw.ellipsize(font, status, (int) ((width - 72) / 0.75F)), 66, 51, 0.75F, Draw.DIM, false);
        if (lyricLine != null) {
            g.fill(6, 58, width - 6, 59, 0x20FFFFFF);
            Draw.marquee(g, font, lyricLine, 6, 61, width - 12, Colors.mix(accent, 0xFFFFFF, 0.55F), false);
        }
        g.pose().popPose();
    }
}
