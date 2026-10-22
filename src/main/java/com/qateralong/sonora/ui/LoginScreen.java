package com.qateralong.sonora.ui;

import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.local.LocalLibrary;
import com.qateralong.sonora.spotify.Librespot;
import com.qateralong.sonora.spotify.OAuthSession;
import com.qateralong.sonora.spotify.SpotifyAuth;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public class LoginScreen extends SonoraScreen {
    private final Screen parent;
    private OAuthSession.State lastState;
    private int cardY;
    private int cardH = 150;
    private boolean lastOwnClient;

    public LoginScreen(Screen parent) {
        super(Component.literal("Sonora"));
        this.parent = parent;
    }

    @Override
    protected void layout() {
        this.lastState = SpotifyAuth.state();
        this.lastOwnClient = SpotifyAuth.ownClient();
        List<PillButton> buttons = new ArrayList<>();
        switch (SpotifyAuth.state()) {
            case LOGGED_IN -> {
                buttons.add(new PillButton(0, 0, 20, Draw.Icon.PLAY, Component.translatable("sonora.login.open_player"), Draw.GREEN,
                        () -> this.minecraft.setScreen(new PlayerScreen(this))));
                buttons.add(new PillButton(0, 0, 20, Draw.Icon.SETTINGS, Component.translatable(SpotifyAuth.ownClient() ? "sonora.client.button_on" : "sonora.client.button"),
                        SpotifyAuth.ownClient() ? 0x9AA4FF : 0xFFC060, () -> this.minecraft.setScreen(new ClientIdScreen(this))));
                buttons.add(new PillButton(0, 0, 20, Draw.Icon.CLOSE, Component.translatable("sonora.login.logout"), 0xFF6B6B, () -> {
                    Librespot.stop();
                    SpotifyAuth.logout();
                    this.rebuildWidgets();
                }));
            }
            case WAITING_FOR_BROWSER -> {
                buttons.add(new PillButton(0, 0, 20, Draw.Icon.LOGO, Component.translatable("sonora.login.copy_link"), Draw.GREEN,
                        () -> this.minecraft.keyboardHandler.setClipboard(SpotifyAuth.pendingUrl())));
                buttons.add(new PillButton(0, 0, 20, Draw.Icon.CLOSE, Component.translatable("gui.cancel"), 0xFF6B6B, () -> {
                    SpotifyAuth.cancelLogin();
                    this.rebuildWidgets();
                }));
            }
            default -> buttons.add(new PillButton(0, 0, 20, Draw.Icon.LOGO, Component.translatable("sonora.login.button"), Draw.GREEN, () -> {
                SpotifyAuth.startLogin();
                this.rebuildWidgets();
            }));
        }
        buttons.add(new PillButton(0, 0, 20, Draw.Icon.FOLDER, Component.translatable("sonora.login.open_folder"), 0x9AA4FF, () -> {
            try {
                java.nio.file.Files.createDirectories(LocalLibrary.folders().get(0));
            } catch (Exception ignored) {
            }
            Util.getPlatform().openFile(LocalLibrary.folders().get(0).toFile());
        }));
        buttons.add(new PillButton(0, 0, 20, null, Component.translatable("gui.back"), 0xFFFFFF, this::onClose));
        List<List<PillButton>> rows = new ArrayList<>();
        int maxRow = Math.min(420, this.width - 20);
        for (PillButton button : buttons) {
            List<PillButton> row = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            int used = row == null ? 0 : row.stream().mapToInt(b -> b.getWidth() + 6).sum();
            if (row == null || row.size() >= 2 || used + button.getWidth() > maxRow) {
                row = new ArrayList<>();
                rows.add(row);
            }
            row.add(button);
        }
        this.cardH = 150;
        int block = this.cardH + 8 + rows.size() * 24;
        this.cardY = Math.max(6, (this.height - block) / 2);
        int y = this.cardY + this.cardH + 8;
        for (List<PillButton> row : rows) {
            int total = row.stream().mapToInt(b -> b.getWidth()).sum() + (row.size() - 1) * 6;
            int x = this.width / 2 - total / 2;
            for (PillButton button : row) {
                button.setX(x);
                button.setY(y);
                x += button.getWidth() + 6;
                this.addRenderableWidget(button);
            }
            y += 24;
        }
    }

    @Override
    public void tick() {
        if (SpotifyAuth.state() != this.lastState || SpotifyAuth.ownClient() != this.lastOwnClient) {
            this.rebuildWidgets();
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        this.renderCard(g);
    }

    private void renderCard(GuiGraphics g) {
        int cx = this.width / 2;
        int cardW = Math.min(320, this.width - 40);
        int cardH = this.cardH;
        int cardY = this.cardY;
        Draw.rounded(g, cx - cardW / 2 - 10, cardY - 10, cardW + 20, cardH + 20, 20, 0x101ED760);
        Draw.rounded(g, cx - cardW / 2 - 1, cardY - 1, cardW + 2, cardH + 2, 11, 0x24FFFFFF);
        Draw.roundedGradient(g, cx - cardW / 2, cardY, cardW, cardH, 10, 0xF01A2A20, 0xF00C0C0E);
        Draw.icon(g, Draw.Icon.LOGO, cx - 16, cardY + 12, 32, 0xFF1ED760);
        Draw.scaledText(g, this.font, "Sonora", cx - this.font.width("Sonora"), cardY + 50, 2.0F, 0xFFFFFFFF, true);
        String status;
        int color = Draw.SUBTLE;
        switch (SpotifyAuth.state()) {
            case LOGGED_IN -> {
                status = SpotifyAuth.displayName().isBlank() ? Sonora.text("sonora.login.logged_in_anon") : Sonora.text("sonora.login.logged_in", SpotifyAuth.displayName());
                if (!SpotifyAuth.ownClient()) {
                    status += " " + Sonora.text("sonora.client.hint");
                    color = 0xFFC060;
                }
                if (!SpotifyAuth.product().isEmpty() && !"premium".equalsIgnoreCase(SpotifyAuth.product())) {
                    status += " " + Sonora.text("sonora.login.need_premium");
                    color = 0xFFC060;
                }
            }
            case WAITING_FOR_BROWSER -> status = Sonora.text("sonora.login.waiting");
            case ERROR -> {
                status = Sonora.text("sonora.login.error", SpotifyAuth.message());
                color = 0xFF7070;
            }
            default -> status = Sonora.text("sonora.login.intro");
        }
        List<FormattedCharSequence> lines = this.font.split(Component.literal(status), cardW - 30);
        for (int i = 0; i < Math.min(4, lines.size()); i++) {
            g.drawCenteredString(this.font, lines.get(i), cx, cardY + 76 + i * 11, color);
        }
        if (SpotifyAuth.loggedIn() && !Librespot.status().isEmpty()) {
            g.drawCenteredString(this.font, Draw.ellipsize(this.font, "librespot: " + Librespot.status(), cardW - 20), cx, cardY + cardH - 14, 0x707070);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
