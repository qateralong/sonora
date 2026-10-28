package com.qateralong.sonora.audio;

import com.qateralong.sonora.Sonora;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTDisconnect;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.ALUtil;
import org.lwjgl.system.MemoryUtil;

public final class AudioEngine {
    public static final int RATE = 44100;
    private static final int FRAMES = 1024;
    private static final float KNEE = 0.85F;
    private static final int BUFFERS = 6;
    private static final String PREFIX = "OpenAL Soft on ";

    private final Equalizer equalizer = new Equalizer(RATE);
    private final Spectrum spectrum = new Spectrum(RATE);
    private final Object lock = new Object();
    private volatile AudioSource source;
    private volatile Runnable onEnd;
    private volatile boolean paused;
    private volatile float volume = 0.6F;
    private volatile int barCount = 64;
    private float currentGain = -1.0F;
    private Thread thread;
    private long alDevice;
    private long alContext;
    private int alSource;
    private final ArrayDeque<Integer> freeBuffers = new ArrayDeque<>();
    private int[] alBuffers;
    private ShortBuffer pcm;
    private long lastCheck;
    private boolean canDetectDisconnect;
    private volatile String error;
    private volatile String device = "";
    private volatile float fade = 1.0F;
    private volatile boolean reopen;
    private final Random dither = new Random();
    private volatile boolean flushLine;

    public Equalizer equalizer() {
        return this.equalizer;
    }

    public Spectrum spectrum() {
        return this.spectrum;
    }

    public String error() {
        return this.error;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0F, Math.min(1.0F, volume));
    }

    public float volume() {
        return this.volume;
    }

    public void setBarCount(int count) {
        this.barCount = Math.max(8, Math.min(160, count));
    }

    public void setFade(float fade) {
        this.fade = Math.max(0.0F, Math.min(1.0F, fade));
    }

    public static List<String> devices() {
        List<String> names = new ArrayList<>();
        try {
            List<String> all = ALUtil.getStringList(0L, ALC11.ALC_ALL_DEVICES_SPECIFIER);
            if (all != null) {
                for (String name : all) {
                    if (!names.contains(name)) {
                        names.add(name);
                    }
                }
            }
        } catch (Exception e) {
            Sonora.LOGGER.warn("Failed to list output devices", e);
        }
        return names;
    }

    public static String displayName(String name) {
        return name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name;
    }

    public void setDevice(String name) {
        String next = name == null ? "" : name;
        if (!next.equals(this.device)) {
            this.device = next;
            this.reopen = true;
        }
    }

    public String device() {
        return this.device;
    }

    public void flushOutput() {
        this.flushLine = true;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean paused() {
        return this.paused;
    }

    public AudioSource source() {
        return this.source;
    }

    public void play(AudioSource next, Runnable onEnd) {
        synchronized (this.lock) {
            AudioSource previous = this.source;
            this.source = next;
            this.onEnd = onEnd;
            this.paused = false;
            this.equalizer.reset();
            if (previous != null && previous != next) {
                previous.close();
            }
        }
        this.ensureThread();
    }

    public void stop() {
        synchronized (this.lock) {
            if (this.source != null) {
                this.source.close();
            }
            this.source = null;
            this.onEnd = null;
        }
    }

    public void detach(AudioSource expected) {
        synchronized (this.lock) {
            if (this.source == expected) {
                this.source = null;
                this.onEnd = null;
            }
        }
    }

    private synchronized void ensureThread() {
        if (this.thread != null) {
            return;
        }
        this.thread = new Thread(this::loop, "Sonora audio");
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.MAX_PRIORITY - 1);
        this.thread.start();
    }

    private void openLine() {
        long device = 0L;
        if (!this.device.isEmpty()) {
            device = ALC10.alcOpenDevice(this.device);
            if (device == 0L) {
                Sonora.LOGGER.warn("Output device '{}' unavailable, using default", this.device);
            }
        }
        if (device == 0L) {
            device = ALC10.alcOpenDevice((ByteBuffer) null);
        }
        if (device == 0L) {
            throw new IllegalStateException("no audio output device");
        }
        ALCCapabilities caps = ALC.createCapabilities(device);
        if (!caps.ALC_EXT_thread_local_context) {
            ALC10.alcCloseDevice(device);
            throw new IllegalStateException("OpenAL without ALC_EXT_thread_local_context");
        }
        long context = ALC10.alcCreateContext(device, (IntBuffer) null);
        if (context == 0L) {
            ALC10.alcCloseDevice(device);
            throw new IllegalStateException("failed to create audio context");
        }
        EXTThreadLocalContext.alcSetThreadContext(context);
        AL.createCapabilities(caps);
        this.alDevice = device;
        this.alContext = context;
        this.canDetectDisconnect = caps.ALC_EXT_disconnect;
        this.alSource = AL10.alGenSources();
        AL10.alSourcei(this.alSource, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(this.alSource, AL10.AL_POSITION, 0.0F, 0.0F, 0.0F);
        AL10.alSourcef(this.alSource, AL10.AL_ROLLOFF_FACTOR, 0.0F);
        this.alBuffers = new int[BUFFERS];
        AL10.alGenBuffers(this.alBuffers);
        this.freeBuffers.clear();
        for (int buffer : this.alBuffers) {
            this.freeBuffers.add(buffer);
        }
        if (this.pcm == null) {
            this.pcm = MemoryUtil.memAllocShort(FRAMES * 2);
        }
        Sonora.LOGGER.info("Audio output: {}", ALC10.alcGetString(device, ALC11.ALC_ALL_DEVICES_SPECIFIER));
    }

    private boolean opened() {
        return this.alContext != 0L;
    }

    private void closeLine() {
        if (this.alContext == 0L) {
            return;
        }
        try {
            AL10.alSourceStop(this.alSource);
            AL10.alSourcei(this.alSource, AL10.AL_BUFFER, 0);
            AL10.alDeleteSources(this.alSource);
            AL10.alDeleteBuffers(this.alBuffers);
        } catch (Exception ignored) {
        }
        EXTThreadLocalContext.alcSetThreadContext(0L);
        AL.setCurrentThread(null);
        ALC10.alcDestroyContext(this.alContext);
        ALC10.alcCloseDevice(this.alDevice);
        this.alContext = 0L;
        this.alDevice = 0L;
        this.freeBuffers.clear();
    }

    private void reclaim() {
        int processed = AL10.alGetSourcei(this.alSource, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            this.freeBuffers.add(AL10.alSourceUnqueueBuffers(this.alSource));
        }
    }

    private void flushAl() {
        AL10.alSourceStop(this.alSource);
        reclaim();
    }

    private boolean disconnected() {
        long now = System.nanoTime();
        if (now - this.lastCheck < 500_000_000L) {
            return false;
        }
        this.lastCheck = now;
        return this.canDetectDisconnect && ALC10.alcGetInteger(this.alDevice, EXTDisconnect.ALC_CONNECTED) == 0;
    }

    private void writeAl(byte[] bytes, int length) throws InterruptedException {
        while (true) {
            reclaim();
            if (!this.freeBuffers.isEmpty()) {
                break;
            }
            if (this.reopen || this.flushLine) {
                return;
            }
            Thread.sleep(2);
        }
        this.pcm.clear();
        for (int i = 0; i + 1 < length; i += 2) {
            this.pcm.put((short) ((bytes[i + 1] << 8) | (bytes[i] & 0xFF)));
        }
        this.pcm.flip();
        int buffer = this.freeBuffers.poll();
        AL10.alBufferData(buffer, AL10.AL_FORMAT_STEREO16, this.pcm, RATE);
        AL10.alSourceQueueBuffers(this.alSource, buffer);
        if (AL10.alGetSourcei(this.alSource, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING
                && (this.freeBuffers.size() <= BUFFERS - 2)) {
            AL10.alSourcePlay(this.alSource);
        }
    }

    private void loop() {
        float[] buffer = new float[FRAMES * 2];
        byte[] bytes = new byte[FRAMES * 4];
        long lastAnalysis = 0L;
        while (true) {
            try {
                if ((this.reopen || opened() && disconnected()) && opened()) {
                    closeLine();
                    this.equalizer.reset();
                }
                this.reopen = false;
                if (!opened()) {
                    this.openLine();
                    this.error = null;
                }
                if (this.flushLine) {
                    this.flushLine = false;
                    flushAl();
                }
                AudioSource current = this.source;
                long now = System.nanoTime();
                if (current == null || this.paused) {
                    if (now - lastAnalysis > 33_000_000L) {
                        this.spectrum.decay();
                        lastAnalysis = now;
                    }
                    Thread.sleep(15);
                    continue;
                }
                int frames = current.read(buffer, FRAMES);
                if (frames == AudioSource.END) {
                    Runnable callback;
                    synchronized (this.lock) {
                        if (this.source != current) {
                            continue;
                        }
                        this.source = null;
                        callback = this.onEnd;
                        this.onEnd = null;
                    }
                    current.close();
                    if (callback != null) {
                        callback.run();
                    }
                    continue;
                }
                if (frames == 0) {
                    if (now - lastAnalysis > 33_000_000L) {
                        this.spectrum.decay();
                        lastAnalysis = now;
                    }
                    continue;
                }
                this.equalizer.process(buffer, frames);
                this.spectrum.feed(buffer, frames);
                float target = this.volume * this.volume * this.fade * this.fade;
                if (this.currentGain < 0.0F) {
                    this.currentGain = target;
                }
                for (int i = 0; i < frames; i++) {
                    this.currentGain += (target - this.currentGain) * 0.002F;
                    for (int c = 0; c < 2; c++) {
                        int index = i * 2 + c;
                        float sample = soften(buffer[index] * this.currentGain);
                        buffer[index] = sample;
                        float noise = (this.dither.nextFloat() - this.dither.nextFloat()) / 32768.0F;
                        int value = Math.round((sample + noise) * 32767.0F);
                        value = Math.max(-32768, Math.min(32767, value));
                        bytes[index * 2] = (byte) value;
                        bytes[index * 2 + 1] = (byte) (value >> 8);
                    }
                }
                if (now - lastAnalysis > 16_000_000L) {
                    this.spectrum.analyze(this.barCount);
                    lastAnalysis = now;
                }
                writeAl(bytes, frames * 4);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                this.error = e.getMessage();
                Sonora.LOGGER.error("Audio output failed", e);
                try {
                    closeLine();
                } catch (Exception ignored) {
                    this.alContext = 0L;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    private static float soften(float x) {
        float magnitude = Math.abs(x);
        if (magnitude <= KNEE) {
            return x;
        }
        float shaped = KNEE + (1.0F - KNEE) * (float) Math.tanh((magnitude - KNEE) / (1.0F - KNEE));
        return x < 0.0F ? -shaped : shaped;
    }
}
