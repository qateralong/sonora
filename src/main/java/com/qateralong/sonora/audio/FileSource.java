package com.qateralong.sonora.audio;

import com.qateralong.sonora.Sonora;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public class FileSource extends QueuedSource {
    private final Path path;
    private final Thread thread;
    private volatile long seekRequest = -1L;
    private volatile long baseMillis;
    private volatile long consumedFrames;

    public FileSource(Path path, long startMillis) {
        super(24);
        this.path = path;
        this.seekRequest = Math.max(0L, startMillis);
        this.thread = new Thread(this::decodeLoop, "Sonora decoder");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public static boolean supported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp3") || name.endsWith(".ogg") || name.endsWith(".wav") || name.endsWith(".aiff") || name.endsWith(".aif");
    }

    @Override
    public int read(float[] interleaved, int frames) {
        int read = super.read(interleaved, frames);
        if (read > 0) {
            this.consumedFrames += read;
        }
        return read;
    }

    @Override
    public boolean seekable() {
        return true;
    }

    @Override
    public void seek(long millis) {
        this.seekRequest = Math.max(0L, millis);
        this.flush();
    }

    @Override
    public long positionMillis() {
        return this.baseMillis + this.consumedFrames * 1000L / AudioEngine.RATE;
    }

    private void decodeLoop() {
        while (!this.closed) {
            long start = this.seekRequest;
            this.seekRequest = -1L;
            if (start < 0) {
                start = 0;
            }
            this.flush();
            this.baseMillis = start;
            this.consumedFrames = 0;
            boolean completed;
            try {
                String name = this.path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".mp3")) {
                    completed = this.decodeMp3(start);
                } else if (name.endsWith(".ogg")) {
                    completed = this.decodeOgg(start);
                } else {
                    completed = this.decodeSampled(start);
                }
            } catch (Exception e) {
                Sonora.LOGGER.error("Failed to decode {}", this.path, e);
                completed = true;
            }
            if (completed && this.seekRequest < 0) {
                this.finished = true;
                while (!this.closed && this.seekRequest < 0) {
                    sleep(30);
                }
                this.finished = false;
            }
        }
    }

    private boolean interrupted() {
        return this.closed || this.seekRequest >= 0;
    }

    private boolean emit(float[] stereo, StreamResampler resampler) {
        float[] out = resampler.process(stereo);
        while (!this.interrupted()) {
            if (this.queue.offer(out)) {
                return true;
            }
            sleep(5);
        }
        return false;
    }

    private boolean decodeMp3(long startMillis) throws Exception {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(this.path), 64 * 1024)) {
            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();
            double skipped = 0.0;
            StreamResampler resampler = null;
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                if (this.interrupted()) {
                    return false;
                }
                if (skipped + header.ms_per_frame() < startMillis) {
                    skipped += header.ms_per_frame();
                    bitstream.closeFrame();
                    continue;
                }
                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                bitstream.closeFrame();
                int channels = output.getChannelCount();
                short[] samples = output.getBuffer();
                int length = output.getBufferLength();
                if (resampler == null) {
                    resampler = new StreamResampler(output.getSampleFrequency(), AudioEngine.RATE);
                }
                int frames = length / channels;
                float[] stereo = new float[frames * 2];
                for (int i = 0; i < frames; i++) {
                    float left = samples[i * channels] / 32768.0F;
                    float right = channels > 1 ? samples[i * channels + 1] / 32768.0F : left;
                    stereo[i * 2] = left;
                    stereo[i * 2 + 1] = right;
                }
                if (!this.emit(stereo, resampler)) {
                    return false;
                }
            }
            bitstream.close();
        }
        return true;
    }

    private boolean decodeOgg(long startMillis) throws Exception {
        byte[] bytes = Files.readAllBytes(this.path);
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();
        long handle = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            handle = STBVorbis.stb_vorbis_open_memory(data, error, null);
            if (handle == 0L) {
                throw new IllegalStateException("stb_vorbis error " + error.get(0));
            }
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(handle, info);
            int channels = info.channels();
            int rate = info.sample_rate();
            STBVorbis.stb_vorbis_seek(handle, (int) (startMillis * rate / 1000L));
            StreamResampler resampler = new StreamResampler(rate, AudioEngine.RATE);
            FloatBuffer buffer = MemoryUtil.memAllocFloat(4096 * channels);
            try {
                while (!this.interrupted()) {
                    buffer.clear();
                    int frames = STBVorbis.stb_vorbis_get_samples_float_interleaved(handle, channels, buffer);
                    if (frames <= 0) {
                        return true;
                    }
                    float[] stereo = new float[frames * 2];
                    for (int i = 0; i < frames; i++) {
                        float left = buffer.get(i * channels);
                        float right = channels > 1 ? buffer.get(i * channels + 1) : left;
                        stereo[i * 2] = left;
                        stereo[i * 2 + 1] = right;
                    }
                    if (!this.emit(stereo, resampler)) {
                        return false;
                    }
                }
            } finally {
                MemoryUtil.memFree(buffer);
            }
            return false;
        } finally {
            if (handle != 0L) {
                STBVorbis.stb_vorbis_close(handle);
            }
            MemoryUtil.memFree(data);
        }
    }

    private boolean decodeSampled(long startMillis) throws Exception {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(this.path.toFile())) {
            AudioFormat base = raw.getFormat();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.getSampleRate(), 16, base.getChannels(),
                    base.getChannels() * 2, base.getSampleRate(), false);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(target, raw)) {
                int channels = target.getChannels();
                long skipBytes = (long) (startMillis / 1000.0 * target.getSampleRate()) * channels * 2;
                in.skipNBytes(Math.min(skipBytes, Long.MAX_VALUE));
                StreamResampler resampler = new StreamResampler((int) target.getSampleRate(), AudioEngine.RATE);
                byte[] buffer = new byte[4096 * channels * 2];
                int read;
                while (!this.interrupted() && (read = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                    int frames = read / (channels * 2);
                    float[] stereo = new float[frames * 2];
                    for (int i = 0; i < frames; i++) {
                        int index = i * channels * 2;
                        float left = (short) ((buffer[index + 1] << 8) | (buffer[index] & 0xFF)) / 32768.0F;
                        float right = left;
                        if (channels > 1) {
                            right = (short) ((buffer[index + 3] << 8) | (buffer[index + 2] & 0xFF)) / 32768.0F;
                        }
                        stereo[i * 2] = left;
                        stereo[i * 2 + 1] = right;
                    }
                    if (!this.emit(stereo, resampler)) {
                        return false;
                    }
                }
                return !this.interrupted();
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
