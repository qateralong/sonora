package com.qateralong.sonora.local;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Header;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public record TrackMeta(String title, String artist, String album, long durationMillis, byte[] artwork) {
    public static TrackMeta read(Path path) {
        String file = path.getFileName().toString();
        String base = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
        String title = base;
        String artist = "";
        String album = path.getParent() == null || path.getParent().getFileName().toString().equalsIgnoreCase("music") ? "" : path.getParent().getFileName().toString();
        byte[] artwork = null;
        long duration = 0L;
        String clean = base.replace('_', ' ')
                .replaceAll("[\\(\\[][^)\\]]*\\.[a-z]{2,4}[)\\]]", "")
                .replaceAll("\\s*[-–—]\\s+", " - ")
                .replaceAll("\\s+", " ").trim();
        java.util.List<String> parts = new java.util.ArrayList<>(java.util.List.of(clean.split(" - ")));
        if (parts.size() >= 4 && parts.size() % 2 == 0) {
            int half = parts.size() / 2;
            if (String.join(" - ", parts.subList(0, half)).equalsIgnoreCase(String.join(" - ", parts.subList(half, parts.size())))) {
                parts = new java.util.ArrayList<>(parts.subList(0, half));
            }
        }
        title = clean;
        if (parts.size() >= 2) {
            artist = parts.get(0).trim();
            title = String.join(" - ", parts.subList(1, parts.size())).trim();
        }
        String lower = file.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".mp3")) {
                Id3 id3 = Id3.read(path);
                if (id3 != null) {
                    if (id3.title != null && !id3.title.isBlank()) {
                        title = id3.title;
                    }
                    if (id3.artist != null && !id3.artist.isBlank()) {
                        artist = id3.artist;
                    }
                    if (id3.album != null && !id3.album.isBlank()) {
                        album = id3.album;
                    }
                    artwork = id3.artwork;
                }
                duration = mp3Duration(path);
            } else if (lower.endsWith(".ogg")) {
                duration = oggDuration(path);
            } else {
                try (var stream = javax.sound.sampled.AudioSystem.getAudioInputStream(path.toFile())) {
                    duration = (long) (stream.getFrameLength() * 1000.0 / stream.getFormat().getFrameRate());
                }
            }
        } catch (Exception ignored) {
        }
        return new TrackMeta(title, artist, album, duration, artwork);
    }

    private static long mp3Duration(Path path) throws Exception {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path), 64 * 1024)) {
            Bitstream bitstream = new Bitstream(in);
            double total = 0.0;
            Header header;
            int frames = 0;
            while ((header = bitstream.readFrame()) != null) {
                total += header.ms_per_frame();
                bitstream.closeFrame();
                if (++frames == 200 && header.vbr() == false) {
                    long size = Files.size(path);
                    return (long) (size * 8.0 / header.bitrate() * 1000.0);
                }
            }
            return (long) total;
        }
    }

    private static long oggDuration(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        var data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long handle = STBVorbis.stb_vorbis_open_memory(data, stack.mallocInt(1), null);
            if (handle == 0L) {
                return 0L;
            }
            float seconds = STBVorbis.stb_vorbis_stream_length_in_seconds(handle);
            STBVorbis.stb_vorbis_close(handle);
            return (long) (seconds * 1000.0F);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static final class Id3 {
        String title;
        String artist;
        String album;
        byte[] artwork;

        static Id3 read(Path path) throws Exception {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                byte[] head = new byte[10];
                in.readFully(head);
                if (head[0] != 'I' || head[1] != 'D' || head[2] != '3') {
                    return null;
                }
                int version = head[3];
                int size = synchsafe(head, 6);
                byte[] tag = new byte[size];
                in.readFully(tag);
                Id3 id3 = new Id3();
                int pos = 0;
                if ((head[5] & 0x40) != 0 && version >= 3) {
                    int extended = version == 4 ? synchsafe(tag, 0) : readInt(tag, 0) + 4;
                    pos += extended;
                }
                while (pos + 10 <= tag.length) {
                    String id = new String(tag, pos, version == 2 ? 3 : 4, StandardCharsets.ISO_8859_1);
                    if (id.charAt(0) == 0) {
                        break;
                    }
                    int frameSize;
                    int headerSize;
                    if (version == 2) {
                        frameSize = ((tag[pos + 3] & 0xFF) << 16) | ((tag[pos + 4] & 0xFF) << 8) | (tag[pos + 5] & 0xFF);
                        headerSize = 6;
                    } else {
                        frameSize = version == 4 ? synchsafe(tag, pos + 4) : readInt(tag, pos + 4);
                        headerSize = 10;
                    }
                    int start = pos + headerSize;
                    if (frameSize <= 0 || start + frameSize > tag.length) {
                        break;
                    }
                    switch (id) {
                        case "TIT2", "TT2" -> id3.title = text(tag, start, frameSize);
                        case "TPE1", "TP1" -> id3.artist = text(tag, start, frameSize);
                        case "TALB", "TAL" -> id3.album = text(tag, start, frameSize);
                        case "APIC", "PIC" -> {
                            if (id3.artwork == null) {
                                id3.artwork = picture(tag, start, frameSize, version == 2);
                            }
                        }
                        default -> {
                        }
                    }
                    pos = start + frameSize;
                }
                return id3;
            }
        }

        private static String text(byte[] data, int start, int length) {
            if (length < 1) {
                return "";
            }
            Charset charset = switch (data[start]) {
                case 1 -> StandardCharsets.UTF_16;
                case 2 -> StandardCharsets.UTF_16BE;
                case 3 -> StandardCharsets.UTF_8;
                default -> StandardCharsets.ISO_8859_1;
            };
            String value = new String(data, start + 1, length - 1, charset);
            int zero = value.indexOf('\0');
            return (zero >= 0 ? value.substring(0, zero) : value).trim();
        }

        private static byte[] picture(byte[] data, int start, int length, boolean v2) {
            int encoding = data[start];
            int pos = start + 1;
            if (v2) {
                pos += 3;
            } else {
                while (pos < start + length && data[pos] != 0) {
                    pos++;
                }
                pos++;
            }
            pos++;
            if (encoding == 1 || encoding == 2) {
                while (pos + 1 < start + length && !(data[pos] == 0 && data[pos + 1] == 0)) {
                    pos += 2;
                }
                pos += 2;
            } else {
                while (pos < start + length && data[pos] != 0) {
                    pos++;
                }
                pos++;
            }
            int end = start + length;
            if (pos >= end) {
                return null;
            }
            byte[] image = new byte[end - pos];
            System.arraycopy(data, pos, image, 0, image.length);
            return image;
        }

        private static int synchsafe(byte[] data, int offset) {
            return ((data[offset] & 0x7F) << 21) | ((data[offset + 1] & 0x7F) << 14) | ((data[offset + 2] & 0x7F) << 7) | (data[offset + 3] & 0x7F);
        }

        private static int readInt(byte[] data, int offset) {
            return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
        }
    }
}
