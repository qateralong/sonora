package com.qateralong.sonora.ui;

import net.minecraft.client.gui.GuiGraphics;

public final class Visualizer {
    private Visualizer() {
    }

    public static void draw(GuiGraphics g, float[] bars, int x, int y, int w, int h, int color, String style, int alpha) {
        if (bars == null || bars.length == 0 || w <= 0 || h <= 0) {
            return;
        }
        int count = bars.length;
        float slot = (float) w / count;
        int barWidth = Math.max(1, Math.round(slot * 0.68F));
        int top = Colors.argb(alpha, Colors.mix(color, 0xFFFFFF, 0.35F));
        int bottom = Colors.argb(alpha, color);
        switch (style) {
            case "mirror" -> {
                int mid = y + h / 2;
                for (int i = 0; i < count; i++) {
                    int bx = x + Math.round(i * slot);
                    int half = Math.max(1, Math.round(bars[i] * h / 2.0F));
                    g.fillGradient(bx, mid - half, bx + barWidth, mid, top, bottom);
                    g.fillGradient(bx, mid, bx + barWidth, mid + half, bottom, Colors.argb(alpha / 3, color));
                }
            }
            case "wave" -> {
                int previous = -1;
                for (int i = 0; i < count; i++) {
                    int bx = x + Math.round(i * slot);
                    int value = y + h - Math.max(1, Math.round(bars[i] * h));
                    int nx = x + Math.round((i + 1) * slot);
                    if (previous >= 0) {
                        int lo = Math.min(previous, value);
                        int hi = Math.max(previous, value);
                        g.fill(bx, lo, bx + 2, hi + 2, top);
                    }
                    g.fill(bx, value, nx, value + 2, top);
                    g.fillGradient(bx, value + 2, nx, y + h, Colors.argb(alpha / 3, color), Colors.argb(0, color));
                    previous = value;
                }
            }
            case "dots" -> {
                int dot = Math.max(2, barWidth);
                for (int i = 0; i < count; i++) {
                    int bx = x + Math.round(i * slot);
                    int stacks = Math.round(bars[i] * h / (dot + 1));
                    for (int s = 0; s < stacks; s++) {
                        float t = (float) s / Math.max(1, h / (dot + 1));
                        int c = Colors.argb(alpha, Colors.mix(color, 0xFFFFFF, t * 0.6F));
                        int dy = y + h - (s + 1) * (dot + 1);
                        g.fill(bx, dy, bx + dot, dy + dot, c);
                    }
                }
            }
            default -> {
                for (int i = 0; i < count; i++) {
                    int bx = x + Math.round(i * slot);
                    int height = Math.max(1, Math.round(bars[i] * h));
                    g.fillGradient(bx, y + h - height, bx + barWidth, y + h, top, bottom);
                    if (barWidth >= 3 && height > 2) {
                        g.fill(bx, y + h - height - 1, bx + barWidth, y + h - height, Colors.argb(alpha, 0xFFFFFF));
                    }
                }
            }
        }
    }

    public static float[] resample(float[] bars, int count) {
        if (bars.length == count) {
            return bars;
        }
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            float pos = (float) i * bars.length / count;
            int index = Math.min(bars.length - 1, (int) pos);
            out[i] = bars[index];
        }
        return out;
    }
}
