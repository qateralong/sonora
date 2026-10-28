package com.qateralong.sonora.ui;

import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.SonoraConfig;
import com.qateralong.sonora.player.TrackInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;

public final class TrackToast implements Toast {
    private static final long SHOW_MS = 4000L;
    private final TrackInfo track;

    private TrackToast(TrackInfo track) {
        this.track = track;
    }

    public static void show(TrackInfo track) {
        Minecraft.getInstance().getToasts().addToast(new TrackToast(track));
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent component, long time) {
        Font font = Minecraft.getInstance().font;
        boolean incognito = SonoraConfig.get().incognito;
        int accent = MiniPlayer.accent();
        Draw.roundedGradient(g, 0, 0, this.width(), this.height(), 6, Colors.argb(0xF0, Colors.mix(MiniPlayer.dark(), 0x141416, 0.35F)), 0xF00B0B0D);
        Draw.art(g, incognito ? null : MiniPlayer.artwork(this.track), 4, 4, 24, 4, 0xFF1A1A1E);
        int textX = 34;
        int textW = this.width() - textX - 6;
        Draw.scaledText(g, font, Sonora.text("sonora.toast.now_playing"), textX, 5, 0.75F, Colors.argb(0xFF, accent), false);
        String title = incognito ? Sonora.text("sonora.incognito.title") : this.track.title();
        g.drawString(font, Draw.ellipsize(font, title, textW), textX, 13, Draw.TEXT, false);
        if (!incognito) {
            Draw.scaledText(g, font, Draw.ellipsize(font, this.track.artistLine(), (int) (textW / 0.75F)), textX, 23, 0.75F, Draw.SUBTLE, false);
        }
        float progress = Math.min(1.0F, time / (float) SHOW_MS);
        g.fill(4, this.height() - 1, 4 + Math.round((this.width() - 8) * (1.0F - progress)), this.height(), Colors.argb(0x90, accent));
        return time >= SHOW_MS ? Visibility.HIDE : Visibility.SHOW;
    }
}
