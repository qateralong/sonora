package com.qateralong.sonora;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SonoraConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SonoraConfig instance = new SonoraConfig();

    public String clientId = "";
    public int oauthPort = 0;
    public int webOauthPort = 43827;
    public String deviceName = "Minecraft";
    public String librespotPath = "";
    public int bitrate = 320;
    public boolean normalize = false;
    public float volume = 0.6F;
    public boolean eqEnabled = true;
    public float[] eqGains = new float[10];
    public float eqPreamp = 0.0F;
    public String eqPreset = "flat";
    public boolean miniPlayer = true;
    public String miniCorner = "top_right";
    public float miniScale = 0.75F;
    public boolean miniVisualizer = true;
    public String visualizerStyle = "bars";
    public int visualizerBars = 48;
    public boolean accentFromArtwork = true;
    public boolean showLyrics = true;
    public int uiScale = 0;
    public List<String> musicFolders = new ArrayList<>();
    public String lastSource = "spotify";

    public static SonoraConfig get() {
        return instance;
    }

    private static Path file() {
        return Sonora.dir().resolve("config.json");
    }

    public static void load() {
        try {
            if (Files.exists(file())) {
                try (Reader reader = Files.newBufferedReader(file())) {
                    SonoraConfig loaded = GSON.fromJson(reader, SonoraConfig.class);
                    if (loaded != null) {
                        instance = loaded;
                    }
                }
            }
        } catch (Exception e) {
            Sonora.LOGGER.error("Failed to read Sonora config", e);
        }
        if (instance.eqGains == null || instance.eqGains.length != 10) {
            instance.eqGains = new float[10];
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            try (Writer writer = Files.newBufferedWriter(file())) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            Sonora.LOGGER.error("Failed to write Sonora config", e);
        }
    }
}
