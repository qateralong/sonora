package com.qateralong.sonora.ui;

import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.SonoraConfig;
import com.qateralong.sonora.spotify.OAuthSession;
import com.qateralong.sonora.spotify.SpotifyAuth;
import java.net.URI;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public class ClientIdScreen extends Screen {
    private static final String DASHBOARD = "https://developer.spotify.com/dashboard";
    private final Screen parent;
    private EditBox field;
    private OAuthSession.State lastState;
    private int cardY;
    private int cardH;

    public ClientIdScreen(Screen parent) {
        super(Component.translatable("sonora.client.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.lastState = SpotifyAuth.WEB.state();
        int cardW = Math.min(380, this.width - 30);
        this.cardH = Math.min(this.height - 50, 210);
        this.cardY = Math.max(8, (this.height - 30 - this.cardH) / 2);
        int left = this.width / 2 - cardW / 2;
        int rowY = this.cardY + this.cardH - 60;
        String current = this.field == null ? SpotifyAuth.webClientId() : this.field.getValue();
        this.field = new EditBox(this.font, left + 15, rowY, cardW - 30, 16, Component.translatable("sonora.client.field"));
        this.field.setMaxLength(64);
        this.field.setHint(Component.translatable("sonora.client.field"));
        this.field.setValue(current);
        this.addRenderableWidget(this.field);
        int y = rowY + 22;
        PillButton dashboard = new PillButton(left + 15, y, 20, Draw.Icon.LOGO, Component.translatable("sonora.client.dashboard"), Draw.GREEN,
                () -> Util.getPlatform().openUri(URI.create(DASHBOARD)));
        this.addRenderableWidget(dashboard);
        PillButton copy = new PillButton(dashboard.getX() + dashboard.getWidth() + 6, y, 20, Draw.Icon.PLUS,
                Component.translatable("sonora.client.copy_redirect"), 0x9AA4FF, () -> this.minecraft.keyboardHandler.setClipboard(SpotifyAuth.webRedirect()));
        this.addRenderableWidget(copy);
        int bottom = this.height - 28;
        PillButton login = new PillButton(0, bottom, 20, Draw.Icon.PLAY, Component.translatable(
                SpotifyAuth.WEB.state() == OAuthSession.State.WAITING_FOR_BROWSER ? "sonora.client.waiting" : "sonora.client.login"), Draw.GREEN, this::login);
        PillButton off = new PillButton(0, bottom, 20, Draw.Icon.CLOSE, Component.translatable("sonora.client.disable"), 0xFF6B6B, () -> {
            SpotifyAuth.WEB.logout();
            SonoraConfig.get().clientId = "";
            SonoraConfig.save();
            this.field.setValue("");
            this.rebuildWidgets();
        });
        PillButton back = new PillButton(0, bottom, 20, null, Component.translatable("gui.back"), 0xFFFFFF, this::onClose);
        int total = login.getWidth() + off.getWidth() + back.getWidth() + 12;
        login.setX(this.width / 2 - total / 2);
        off.setX(login.getX() + login.getWidth() + 6);
        back.setX(off.getX() + off.getWidth() + 6);
        this.addRenderableWidget(login);
        this.addRenderableWidget(off);
        this.addRenderableWidget(back);
        this.setInitialFocus(this.field);
    }

    private void login() {
        String id = this.field.getValue().trim();
        if (!id.matches("[0-9a-fA-F]{32}")) {
            SpotifyAuth.WEB.logout();
            this.lastState = null;
            return;
        }
        if (!id.equals(SpotifyAuth.webClientId())) {
            SpotifyAuth.WEB.logout();
        }
        SonoraConfig.get().clientId = id;
        SonoraConfig.save();
        SpotifyAuth.WEB.startLogin();
        this.rebuildWidgets();
    }

    @Override
    public void tick() {
        if (SpotifyAuth.WEB.state() != this.lastState) {
            this.rebuildWidgets();
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int cardW = Math.min(380, this.width - 30);
        int left = this.width / 2 - cardW / 2;
        Draw.roundedGradient(g, left, this.cardY, cardW, this.cardH, 10, 0xE01A2A20, 0xE00C0C0E);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.cardY + 10, 0xFFFFFFFF);
        int y = this.cardY + 26;
        int limit = this.cardY + this.cardH - 78;
        for (String key : new String[]{"sonora.client.why", "sonora.client.step1", "sonora.client.step2", "sonora.client.step3", "sonora.client.limit"}) {
            List<FormattedCharSequence> lines = this.font.split(Component.literal(Sonora.text(key, SpotifyAuth.webRedirect())), cardW - 30);
            for (FormattedCharSequence line : lines) {
                if (y + 9 > limit) {
                    break;
                }
                g.drawString(this.font, line, left + 15, y, key.equals("sonora.client.why") ? 0xFFB3B3B3 : key.equals("sonora.client.limit") ? 0xFFFFC060 : 0xFFE0E0E0, false);
                y += 10;
            }
            y += 3;
        }
        String status;
        int color;
        String typed = this.field == null ? "" : this.field.getValue().trim();
        switch (SpotifyAuth.WEB.state()) {
            case LOGGED_IN -> {
                status = Sonora.text("sonora.client.status_on");
                color = 0xFF1ED760;
            }
            case WAITING_FOR_BROWSER -> {
                status = Sonora.text("sonora.login.waiting");
                color = 0xFFB3B3B3;
            }
            case ERROR -> {
                status = Sonora.text("sonora.login.error", SpotifyAuth.WEB.message());
                color = 0xFFFF7070;
            }
            default -> {
                status = !typed.isEmpty() && !typed.matches("[0-9a-fA-F]{32}") ? Sonora.text("sonora.client.bad_id") : Sonora.text("sonora.client.status_off");
                color = !typed.isEmpty() && !typed.matches("[0-9a-fA-F]{32}") ? 0xFFFF7070 : 0xFFFFC060;
            }
        }
        g.drawCenteredString(this.font, Draw.ellipsize(this.font, status, cardW - 20), this.width / 2, this.cardY + this.cardH - 74, color);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
