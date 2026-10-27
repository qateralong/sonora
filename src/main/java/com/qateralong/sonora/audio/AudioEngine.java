package com.qateralong.sonora.audio;

import com.qateralong.sonora.Sonora;
import javax.sound.sampled.AudioFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

public final class AudioEngine {
    public static final int RATE = 44100;
    private static final int FRAMES = 1024;
    private static final float KNEE = 0.85F;

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
    private SourceDataLine line;
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
        AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(mixerInfo).isLineSupported(info) && !names.contains(mixerInfo.getName())) {
                    names.add(mixerInfo.getName());
                }
            } catch (Exception ignored) {
            }
        }
        return names;
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

    private void openLine() throws Exception {
        AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
        SourceDataLine opened = null;
        if (!this.device.isEmpty()) {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (mixerInfo.getName().equals(this.device)) {
                    try {
                        opened = (SourceDataLine) AudioSystem.getMixer(mixerInfo).getLine(new DataLine.Info(SourceDataLine.class, format));
                    } catch (Exception e) {
                        Sonora.LOGGER.warn("Output device '{}' unavailable, using default", this.device);
                    }
                    break;
                }
            }
        }
        if (opened == null) {
            opened = AudioSystem.getSourceDataLine(format);
        }
        opened.open(format, RATE / 4 * 4);
        opened.start();
        this.line = opened;
    }

    private void loop() {
        float[] buffer = new float[FRAMES * 2];
        byte[] bytes = new byte[FRAMES * 4];
        long lastAnalysis = 0L;
        while (true) {
            try {
                if (this.reopen && this.line != null) {
                    this.line.stop();
                    this.line.flush();
                    this.line.close();
                    this.line = null;
                    this.equalizer.reset();
                }
                this.reopen = false;
                if (this.line == null) {
                    this.openLine();
                    this.error = null;
                }
                if (this.flushLine) {
                    this.flushLine = false;
                    this.line.flush();
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
                this.line.write(bytes, 0, frames * 4);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                this.error = e.getMessage();
                Sonora.LOGGER.error("Audio output failed", e);
                if (this.line != null) {
                    this.line.close();
                    this.line = null;
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
