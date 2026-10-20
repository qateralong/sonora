package com.qateralong.sonora.ui;

import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.local.LocalLibrary;
import com.qateralong.sonora.spotify.Librespot;
import com.qateralong.sonora.spotify.SpotifyAuth;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public class LoginScreen extends Screen {
    private final Screen parent;
    private SpotifyAuth.State lastState;

    public LoginScreen(Screen parent) {
        super(Component.literal("Sonora"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.lastState = SpotifyAuth.state();
        int cx = this.width / 2;
        int y = this.height / 2 + 58;
        switch (SpotifyAuth.state()) {
            case LOGGED_IN -> {
                this.addCentered(y, Draw.Icon.PLAY, Component.translatable("sonora.login.open_player"), Draw.GREEN, () -> this.minecraft.setScreen(new PlayerScreen(this)));
                this.addCentered(y + 26, Draw.Icon.CLOSE, Component.translatable("sonora.login.logout"), 0xFF6B6B, () -> {
                    Librespot.stop();
                    SpotifyAuth.logout();
                    this.rebuildWidgets();
                });
            }
            case WAITING_FOR_BROWSER -> {
                this.addCentered(y, Draw.Icon.LOGO, Component.translatable("sonora.login.copy_link"), Draw.GREEN,
                        () -> this.minecraft.keyboardHandler.setClipboard(SpotifyAuth.pendingUrl()));
                this.addCentered(y + 26, Draw.Icon.CLOSE, Component.translatable("gui.cancel"), 0xFF6B6B, () -> {
                    SpotifyAuth.cancelLogin();
                    this.rebuildWidgets();
                });
            }
            default -> this.addCentered(y, Draw.Icon.LOGO, Component.translatable("sonora.login.button"), Draw.GREEN, () -> {
                SpotifyAuth.startLogin();
                this.rebuildWidgets();
            });
        }
        this.addCentered(y + 52, Draw.Icon.FOLDER, Component.translatable("sonora.login.open_folder"), 0x9AA4FF, () -> {
            try {
                java.nio.file.Files.createDirectories(LocalLibrary.folders().get(0));
            } catch (Exception ignored) {
            }
            Util.getPlatform().openFile(LocalLibrary.folders().get(0).toFile());
        });
        this.addRenderableWidget(new PillButton(cx - 40, this.height - 30, 20, null, Component.translatable("gui.back"), 0xFFFFFF, this::onClose));
    }

    private void addCentered(int y, Draw.Icon icon, Component label, int color, Runnable action) {
        PillButton button = new PillButton(0, y, 20, icon, label, color, action);
        button.setX(this.width / 2 - button.getWidth() / 2);
        this.addRenderableWidget(button);
    }

    @Override
    public void tick() {
        if (SpotifyAuth.state() != this.lastState) {
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
        int cardH = 150;
        int cardY = this.height / 2 - 100;
        Draw.roundedGradient(g, cx - cardW / 2, cardY, cardW, cardH, 10, 0xE01A2A20, 0xE00C0C0E);
        Draw.icon(g, Draw.Icon.LOGO, cx - 16, cardY + 12, 32, 0xFF1ED760);
        Draw.scaledText(g, this.font, "Sonora", cx - this.font.width("Sonora"), cardY + 50, 2.0F, 0xFFFFFFFF, true);
        String status;
        int color = Draw.SUBTLE;
        switch (SpotifyAuth.state()) {
            case LOGGED_IN -> {
                status = Sonora.text("sonora.login.logged_in", SpotifyAuth.displayName());
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
            g.drawCenteredString(this.font, Component.literal("librespot: " + Librespot.status()), cx, cardY + cardH - 14, 0x707070);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
