package com.qateralong.sonora.ui;

import com.mojang.blaze3d.platform.Window;
import com.qateralong.sonora.SonoraConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public abstract class SonoraScreen extends Screen {
    private double factor = 1.0;

    protected SonoraScreen(Component title) {
        super(title);
    }

    public static int autoScale() {
        Window window = Minecraft.getInstance().getWindow();
        return Mth.clamp(Math.round(window.getHeight() / 500.0F), 1, Math.max(1, (int) window.getGuiScale()));
    }

    public static int maxScale() {
        Minecraft minecraft = Minecraft.getInstance();
        return Math.max(1, minecraft.getWindow().calculateScale(0, minecraft.options.forceUnicodeFont().get()));
    }

    public static int scale() {
        int chosen = SonoraConfig.get().uiScale;
        return Mth.clamp(chosen <= 0 ? autoScale() : chosen, 1, maxScale());
    }

    @Override
    protected final void init() {
        Window window = this.minecraft.getWindow();
        int scale = scale();
        this.factor = window.getGuiScale() / scale;
        this.width = Mth.ceil(window.getWidth() / (double) scale);
        this.height = Mth.ceil(window.getHeight() / (double) scale);
        this.layout();
    }

    protected abstract void layout();

    @Override
    public final void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.pose().pushPose();
        float inverse = (float) (1.0 / this.factor);
        g.pose().scale(inverse, inverse, 1.0F);
        this.draw(g, (int) (mouseX * this.factor), (int) (mouseY * this.factor), partialTick);
        g.pose().popPose();
    }

    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public final boolean mouseClicked(double mouseX, double mouseY, int button) {
        return this.clicked(mouseX * this.factor, mouseY * this.factor, button);
    }

    protected boolean clicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public final boolean mouseReleased(double mouseX, double mouseY, int button) {
        return this.released(mouseX * this.factor, mouseY * this.factor, button);
    }

    protected boolean released(double mouseX, double mouseY, int button) {
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public final boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return this.dragged(mouseX * this.factor, mouseY * this.factor, button, dragX * this.factor, dragY * this.factor);
    }

    protected boolean dragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public final boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return this.scrolled(mouseX * this.factor, mouseY * this.factor, scrollX, scrollY);
    }

    protected boolean scrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public final void mouseMoved(double mouseX, double mouseY) {
        this.moved(mouseX * this.factor, mouseY * this.factor);
    }

    protected void moved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public final boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(mouseX * this.factor, mouseY * this.factor);
    }
}
