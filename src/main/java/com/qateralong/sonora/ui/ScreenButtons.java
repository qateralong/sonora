package com.qateralong.sonora.ui;

import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.SonoraConfig;
import com.qateralong.sonora.local.LocalLibrary;
import com.qateralong.sonora.spotify.SpotifyAuth;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public final class ScreenButtons {
    private static final java.util.Set<net.minecraft.client.gui.screens.Screen> HOOKED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private ScreenButtons() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if ((screen instanceof TitleScreen || screen instanceof PauseScreen) && HOOKED.add(screen)) {
                ScreenEvents.afterRender(screen).register((s, g, mouseX, mouseY, delta) -> MiniPlayer.renderOnScreen(g, s));
            }
            if (!SonoraConfig.get().menuButtons) {
                return;
            }
            if (screen instanceof TitleScreen) {
                Component label = SpotifyAuth.loggedIn() || !LocalLibrary.tracks().isEmpty()
                        ? Component.translatable("sonora.button.music")
                        : Component.translatable("sonora.button.login");
                Screens.getButtons(screen).add(new PillButton(6, 6, 20, Draw.Icon.NOTE, label, Draw.GREEN, () -> client.setScreen(new PlayerScreen(screen))));
            } else if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
                Screens.getButtons(screen).add(new PillButton(6, 6, 20, Draw.Icon.NOTE, Component.translatable("sonora.button.music"), Draw.GREEN,
                        () -> client.setScreen(new PlayerScreen(screen))));
            }
        });
        Sonora.LOGGER.debug("Sonora screen buttons registered");
    }
}
