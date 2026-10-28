package com.qateralong.sonora;

import com.mojang.blaze3d.platform.InputConstants;
import com.qateralong.sonora.local.LocalLibrary;
import com.qateralong.sonora.player.MusicController;
import com.qateralong.sonora.spotify.Librespot;
import com.qateralong.sonora.spotify.SpotifyApi;
import com.qateralong.sonora.spotify.SpotifyAuth;
import com.qateralong.sonora.ui.MiniPlayer;
import com.qateralong.sonora.ui.PlayerScreen;
import com.qateralong.sonora.ui.ScreenButtons;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Sonora implements ClientModInitializer {
    public static final String MOD_ID = "sonora";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String CATEGORY = "key.categories.sonora";
    public static final KeyMapping OPEN = new KeyMapping("key.sonora.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY);
    public static final KeyMapping PLAY_PAUSE = new KeyMapping("key.sonora.play_pause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);
    public static final KeyMapping NEXT = new KeyMapping("key.sonora.next", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, CATEGORY);
    public static final KeyMapping PREVIOUS = new KeyMapping("key.sonora.previous", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY);
    public static final KeyMapping MINI = new KeyMapping("key.sonora.mini", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);
    private static final MusicController CONTROLLER = new MusicController();

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("sonora");
    }

    public static MusicController controller() {
        return CONTROLLER;
    }

    public static String text(String key, Object... args) {
        return I18n.exists(key) ? I18n.get(key, args) : key;
    }

    @Override
    public void onInitializeClient() {
        SonoraConfig.load();
        SpotifyAuth.load();
        CONTROLLER.init();
        LocalLibrary.rescan();
        KeyBindingHelper.registerKeyBinding(OPEN);
        KeyBindingHelper.registerKeyBinding(PLAY_PAUSE);
        KeyBindingHelper.registerKeyBinding(NEXT);
        KeyBindingHelper.registerKeyBinding(PREVIOUS);
        KeyBindingHelper.registerKeyBinding(MINI);
        HudRenderCallback.EVENT.register(MiniPlayer::render);
        ScreenButtons.register();
        ClientTickEvents.END_CLIENT_TICK.register(Sonora::tick);
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (SpotifyAuth.loggedIn()) {
                Librespot.ensureRunning();
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            Librespot.stop();
            SonoraConfig.save();
        });
    }

    public static void onWebLogin() {
        SpotifyApi.clearBlock();
        CONTROLLER.spotify().pollSoon();
    }

    public static void onLogin() {
        Minecraft.getInstance().execute(() -> {
            Librespot.ensureRunning();
            CONTROLLER.spotify().forgetDevice();
            CONTROLLER.spotify().pollSoon();
        });
    }

    private static void tick(Minecraft minecraft) {
        CONTROLLER.tick();
        AutoTest.tick(minecraft);
        CONTROLLER.spotify().setFastPolling(minecraft.screen instanceof PlayerScreen);
        while (OPEN.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new PlayerScreen(null));
            }
        }
        while (PLAY_PAUSE.consumeClick()) {
            CONTROLLER.togglePlay();
        }
        while (NEXT.consumeClick()) {
            CONTROLLER.next();
        }
        while (PREVIOUS.consumeClick()) {
            CONTROLLER.previous();
        }
        while (MINI.consumeClick()) {
            SonoraConfig.get().miniPlayer = !SonoraConfig.get().miniPlayer;
            SonoraConfig.save();
        }
    }
}
