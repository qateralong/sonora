package com.qateralong.sonora.spotify;

import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.SonoraConfig;
import com.qateralong.sonora.audio.PcmStreamSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Librespot {
    private static Process process;
    private static PcmStreamSource source;
    private static volatile String status = "";
    private static volatile long startedAt;
    private static Thread watchdog;
    private static volatile boolean wanted;
    private static volatile int failures;

    private Librespot() {
    }

    public static String status() {
        return status;
    }

    public static synchronized PcmStreamSource source() {
        return source;
    }

    public static boolean running() {
        Process current = process;
        return current != null && current.isAlive();
    }

    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) {
            return "windows-x64";
        }
        if (os.contains("mac")) {
            return arm ? "macos-arm64" : "macos-x64";
        }
        return arm ? "linux-arm64" : "linux-x64";
    }

    public static Path binary() throws Exception {
        String custom = SonoraConfig.get().librespotPath;
        if (custom != null && !custom.isBlank()) {
            return Path.of(custom);
        }
        String platform = platform();
        String exe = platform.startsWith("windows") ? "librespot.exe" : "librespot";
        Path target = Sonora.dir().resolve("bin").resolve(platform).resolve(exe);
        try (InputStream in = Librespot.class.getResourceAsStream("/natives/" + platform + "/" + exe)) {
            if (in == null) {
                if (Files.exists(target)) {
                    return target;
                }
                throw new IllegalStateException("no bundled librespot for " + platform);
            }
            byte[] bytes = in.readAllBytes();
            if (!Files.exists(target) || Files.size(target) != bytes.length) {
                Files.createDirectories(target.getParent());
                Path temp = target.resolveSibling(exe + ".tmp");
                Files.write(temp, bytes);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                target.toFile().setExecutable(true);
            }
        }
        return target;
    }

    public static synchronized void ensureRunning() {
        wanted = true;
        if (running()) {
            return;
        }
        start();
        if (watchdog == null) {
            watchdog = new Thread(Librespot::watch, "Sonora librespot watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }
    }

    private static void watch() {
        while (true) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
            if (wanted && !running() && SpotifyAuth.loggedIn()) {
                long backoff = Math.min(60_000L, 2000L * (1L << Math.min(5, failures)));
                if (System.currentTimeMillis() - startedAt > backoff) {
                    synchronized (Librespot.class) {
                        start();
                    }
                }
            }
        }
    }

    private static void start() {
        stopProcess();
        if (!SpotifyAuth.loggedIn()) {
            status = "not logged in";
            return;
        }
        startedAt = System.currentTimeMillis();
        try {
            Path bin = binary();
            Path cache = Sonora.dir().resolve("librespot-cache");
            Files.createDirectories(cache);
            SonoraConfig config = SonoraConfig.get();
            List<String> command = new ArrayList<>(List.of(bin.toString(),
                    "--name", config.deviceName, "--device-type", "computer",
                    "--backend", "pipe", "--format", "S16", "--bitrate", String.valueOf(config.bitrate),
                    "--cache", cache.toString(),
                    "--disable-audio-cache", "--disable-discovery", "--initial-volume", "100", "--volume-ctrl", "fixed"));
            if (config.normalize) {
                command.add("--enable-volume-normalisation");
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("LIBRESPOT_ACCESS_TOKEN", SpotifyAuth.accessToken());
            builder.redirectError(ProcessBuilder.Redirect.appendTo(Sonora.dir().resolve("librespot.log").toFile()));
            process = builder.start();
            source = new PcmStreamSource(process.getInputStream());
            status = "running";
            failures = 0;
            Sonora.LOGGER.info("Started librespot ({})", bin);
            Sonora.controller().onLibrespotStarted(source);
            Process started = process;
            started.onExit().thenRun(() -> {
                if (System.currentTimeMillis() - startedAt < 10_000L) {
                    failures++;
                }
                status = "exited with code " + started.exitValue();
                Sonora.LOGGER.warn("librespot {}", status);
            });
        } catch (Exception e) {
            failures++;
            status = e.getMessage();
            Sonora.LOGGER.error("Failed to start librespot", e);
        }
    }

    private static void stopProcess() {
        if (source != null) {
            source.close();
            source = null;
        }
        if (process != null) {
            process.destroy();
            process = null;
        }
    }

    public static synchronized void stop() {
        wanted = false;
        stopProcess();
        status = "stopped";
    }

    public static synchronized void restart() {
        stopProcess();
        if (wanted) {
            start();
        }
    }
}
