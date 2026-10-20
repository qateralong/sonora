package com.qateralong.sonora.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qateralong.sonora.Sonora;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public final class Draw {
    public static final ResourceLocation ICONS = Sonora.id("textures/gui/icons.png");
    public static final int TEXT = 0xFFFFFF;
    public static final int SUBTLE = 0xB3B3B3;
    public static final int DIM = 0x7A7A7A;
    public static final int GREEN = 0x1ED760;
    private static boolean iconsFiltered;

    public enum Icon {
        PLAY, PAUSE, NEXT, PREV, SHUFFLE, REPEAT, REPEAT_ONE, HEART, HEART_FILLED, VOLUME, VOLUME_MUTE, SEARCH, QUEUE, EQ,
        LYRICS, NOTE, LIBRARY, FOLDER, SETTINGS, CLOSE, DISC, DEVICE, LOGO, PLUS
    }

    private Draw() {
    }

    public static void rounded(GuiGraphics g, int x, int y, int w, int h, int r, int argb) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            g.fill(x, y, x + w, y + h, argb);
            return;
        }
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, r * r - dy * dy)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, argb);
            g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, argb);
        }
        g.fill(x, y + r, x + w, y + h - r, argb);
    }

    public static void roundedGradient(GuiGraphics g, int x, int y, int w, int h, int r, int top, int bottom) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        for (int i = 0; i < h; i++) {
            int inset = 0;
            if (i < r || i >= h - r) {
                int row = i < r ? i : h - 1 - i;
                double dy = r - row - 0.5;
                inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, r * r - dy * dy)));
            }
            float t = h <= 1 ? 0.0F : (float) i / (h - 1);
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, lerpArgb(top, bottom, t));
        }
    }

    public static int lerpArgb(int a, int b, float t) {
        int aa = a >>> 24;
        int ba = b >>> 24;
        int alpha = (int) (aa + (ba - aa) * t);
        return (alpha << 24) | Colors.mix(a & 0xFFFFFF, b & 0xFFFFFF, t);
    }

    public static void icon(GuiGraphics g, Icon icon, int x, int y, int size, int argb) {
        if (!iconsFiltered) {
            iconsFiltered = true;
            Minecraft.getInstance().getTextureManager().getTexture(ICONS).setFilter(true, false);
        }
        int index = icon.ordinal();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(((argb >> 16) & 0xFF) / 255.0F, ((argb >> 8) & 0xFF) / 255.0F, (argb & 0xFF) / 255.0F, ((argb >>> 24) & 0xFF) / 255.0F);
        g.blit(ICONS, x, y, size, size, (index % 8) * 32, (index / 8) * 32, 32, 32, 256, 96);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    public static void art(GuiGraphics g, Artwork.Entry art, int x, int y, int size, int corner, int background) {
        if (art == null) {
            roundedGradient(g, x, y, size, size, corner, 0xFF3A3A44, 0xFF1C1C22);
            int iconSize = Math.max(8, size / 2);
            icon(g, Icon.NOTE, x + (size - iconSize) / 2, y + (size - iconSize) / 2, iconSize, 0x80FFFFFF);
            return;
        }
        RenderSystem.enableBlend();
        g.blit(art.texture(), x, y, size, size, 0.0F, 0.0F, 256, 256, 256, 256);
        RenderSystem.disableBlend();
        if (corner > 0 && (background >>> 24) != 0) {
            for (int i = 0; i < corner; i++) {
                double dy = corner - i - 0.5;
                int inset = (int) Math.round(corner - Math.sqrt(Math.max(0.0, corner * corner - dy * dy)));
                if (inset > 0) {
                    g.fill(x, y + i, x + inset, y + i + 1, background);
                    g.fill(x + size - inset, y + i, x + size, y + i + 1, background);
                    g.fill(x, y + size - i - 1, x + inset, y + size - i, background);
                    g.fill(x + size - inset, y + size - i - 1, x + size, y + size - i, background);
                }
            }
        }
    }

    private static final java.util.Map<String, Long> SLIDE_START = new java.util.LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
            return this.size() > 64;
        }
    };

    public static void slide(GuiGraphics g, Font font, String text, float x, float y, int width, float scale, int color, boolean shadow) {
        int textWidth = Math.round(font.width(text) * scale);
        if (textWidth <= width) {
            scaledText(g, font, text, x, y, scale, color, shadow);
            return;
        }
        long now = Util.getMillis();
        long start = SLIDE_START.computeIfAbsent(text, key -> now);
        int overflow = textWidth - width + 4;
        long travel = Math.max(1200L, overflow * 28L);
        long hold = 2200L;
        long cycle = hold * 2 + travel * 2;
        long t = (now - start) % cycle;
        float offset;
        if (t < hold) {
            offset = 0.0F;
        } else if (t < hold + travel) {
            offset = ease((t - hold) / (float) travel) * overflow;
        } else if (t < hold * 2 + travel) {
            offset = overflow;
        } else {
            offset = (1.0F - ease((t - hold * 2 - travel) / (float) travel)) * overflow;
        }
        scissor(g, Math.round(x), Math.round(y) - 2, Math.round(x) + width, Math.round(y + font.lineHeight * scale) + 2);
        scaledText(g, font, text, x - offset, y, scale, color, shadow);
        g.disableScissor();
    }

    public static float ease(float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        return t < 0.5F ? 4.0F * t * t * t : 1.0F - (float) Math.pow(-2.0F * t + 2.0F, 3.0) / 2.0F;
    }

    public static void scissor(GuiGraphics g, int x0, int y0, int x1, int y1) {
        org.joml.Vector3f a = g.pose().last().pose().transformPosition(x0, y0, 0.0F, new org.joml.Vector3f());
        org.joml.Vector3f b = g.pose().last().pose().transformPosition(x1, y1, 0.0F, new org.joml.Vector3f());
        g.enableScissor((int) Math.floor(Math.min(a.x, b.x)), (int) Math.floor(Math.min(a.y, b.y)), (int) Math.ceil(Math.max(a.x, b.x)), (int) Math.ceil(Math.max(a.y, b.y)));
    }

    public static String ellipsize(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        String dots = "…";
        int dotsWidth = font.width(dots);
        return font.plainSubstrByWidth(text, Math.max(0, width - dotsWidth)) + dots;
    }

    public static String time(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long minutes = seconds / 60L;
        if (minutes >= 60) {
            return String.format("%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60);
        }
        return String.format("%d:%02d", minutes, seconds % 60);
    }

    public static void scaledText(GuiGraphics g, Font font, String text, float x, float y, float scale, int color, boolean shadow) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(font, text, 0, 0, color, shadow);
        g.pose().popPose();
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
