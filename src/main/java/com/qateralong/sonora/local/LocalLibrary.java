package com.qateralong.sonora.local;

import com.qateralong.sonora.Sonora;
import com.qateralong.sonora.SonoraConfig;
import com.qateralong.sonora.audio.FileSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class LocalLibrary {
    public record LocalTrack(Path path, TrackMeta meta) {
        public String key() {
            return "local:" + this.path.toAbsolutePath();
        }
    }

    private static final Map<Path, TrackMeta> CACHE = new ConcurrentHashMap<>();
    private static volatile List<LocalTrack> tracks = List.of();
    private static volatile boolean scanning;

    private LocalLibrary() {
    }

    public static List<Path> folders() {
        List<Path> folders = new ArrayList<>();
        folders.add(Sonora.dir().resolve("music"));
        for (String extra : SonoraConfig.get().musicFolders) {
            if (!extra.isBlank()) {
                folders.add(Path.of(extra));
            }
        }
        return folders;
    }

    public static List<LocalTrack> tracks() {
        return tracks;
    }

    public static boolean scanning() {
        return scanning;
    }

    public static void rescan() {
        if (scanning) {
            return;
        }
        scanning = true;
        Thread thread = new Thread(() -> {
            try {
                List<LocalTrack> found = new ArrayList<>();
                for (Path folder : folders()) {
                    try {
                        Files.createDirectories(folder);
                    } catch (Exception ignored) {
                    }
                    if (!Files.isDirectory(folder)) {
                        continue;
                    }
                    try (Stream<Path> walk = Files.walk(folder, 6)) {
                        walk.filter(Files::isRegularFile).filter(FileSource::supported).forEach(path ->
                                found.add(new LocalTrack(path, CACHE.computeIfAbsent(path, TrackMeta::read))));
                    } catch (Exception e) {
                        Sonora.LOGGER.warn("Failed to scan {}", folder, e);
                    }
                }
                found.sort(Comparator.comparing((LocalTrack track) -> track.meta().artist().toLowerCase()).thenComparing(track -> track.meta().title().toLowerCase()));
                tracks = List.copyOf(found);
            } finally {
                scanning = false;
            }
        }, "Sonora library scan");
        thread.setDaemon(true);
        thread.start();
    }

}
