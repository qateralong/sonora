package com.qateralong.sonora.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

public class PillButton extends AbstractButton {
    private final Draw.Icon icon;
    private final Runnable action;
    private final int color;

    public PillButton(int x, int y, int height, Draw.Icon icon, Component label, int color, Runnable action) {
        super(x, y, measure(label, height, icon), height, label);
        this.icon = icon;
        this.action = action;
        this.color = color;
    }

    private static int measure(Component label, int height, Draw.Icon icon) {
        Font font = Minecraft.getInstance().font;
        int text = label.getString().isEmpty() ? 0 : font.width(label) + 8;
        return (icon == null ? 8 : height) + text;
    }

    @Override
    public void onPress() {
        this.action.run();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hover = this.isHoveredOrFocused();
        int background = hover ? Colors.argb(0xF0, Colors.mix(this.color, 0x000000, 0.15F)) : Colors.argb(0xD0, 0x121214);
        Draw.rounded(g, this.getX(), this.getY(), this.width, this.height, this.height / 2, background);
        int textColor = hover ? 0xFF000000 : 0xFFFFFFFF;
        int x = this.getX() + 4;
        if (this.icon != null) {
            int size = this.height - 8;
            Draw.icon(g, this.icon, this.getX() + 5, this.getY() + 4, size, hover ? 0xFF000000 : Colors.argb(0xFF, this.color));
            x = this.getX() + this.height;
        }
        Font font = Minecraft.getInstance().font;
        if (!this.getMessage().getString().isEmpty()) {
            g.drawString(font, this.getMessage(), x, this.getY() + (this.height - 8) / 2, textColor, false);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
