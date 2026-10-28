package com.qateralong.sonora;

import com.qateralong.sonora.audio.AudioEngine;
import com.qateralong.sonora.local.LocalLibrary;
import java.util.List;
import net.minecraft.client.Minecraft;

final class AutoTest {
    private static final boolean ENABLED = System.getProperty("sonora.autotest") != null;
    private static int ticks;

    private AutoTest() {
    }

    static void tick(Minecraft minecraft) {
        if (!ENABLED) {
            return;
        }
        ticks++;
        switch (ticks) {
            case 200 -> {
                Sonora.LOGGER.info("autotest tracks {}", LocalLibrary.tracks().size());
                if (!LocalLibrary.tracks().isEmpty()) {
                    Sonora.controller().playLocal(LocalLibrary.tracks(), 0);
                }
            }
            case 400 -> {
                List<String> devices = AudioEngine.devices();
                Sonora.LOGGER.info("autotest devices {}", devices);
                String target = System.getProperty("sonora.autotest");
                for (String device : devices) {
                    if (!target.isEmpty() && device.contains(target)) {
                        Sonora.LOGGER.info("autotest switch to {}", device);
                        Sonora.controller().engine().setDevice(device);
                    }
                }
            }
            case 600 -> {
                Sonora.LOGGER.info("autotest switch to default");
                Sonora.controller().engine().setDevice("");
            }
            case 800 -> minecraft.stop();
            default -> {
            }
        }
    }
}
