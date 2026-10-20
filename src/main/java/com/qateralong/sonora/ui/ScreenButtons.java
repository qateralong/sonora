package com.qateralong.sonora.ui;

import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.spotify.SpotifyAuth;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public final class ScreenButtons {
    private ScreenButtons() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen) {
                Component label = SpotifyAuth.loggedIn()
                        ? Component.literal(SpotifyAuth.displayName().isEmpty() ? "Spotify" : SpotifyAuth.displayName())
                        : Component.translatable("sonora.button.login");
                Screens.getButtons(screen).add(new PillButton(6, 6, 20, Draw.Icon.LOGO, label, Draw.GREEN,
                        () -> client.setScreen(new LoginScreen(screen))));
            } else if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
                Screens.getButtons(screen).add(new PillButton(6, 6, 20, Draw.Icon.NOTE, Component.translatable("sonora.button.music"), Draw.GREEN,
                        () -> client.setScreen(new PlayerScreen(screen))));
            }
        });
        Sonora.LOGGER.debug("Sonora screen buttons registered");
    }
}
