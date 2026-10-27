package com.qateralong.sonora.audio;

import java.io.IOException;
import java.io.InputStream;

public class PcmStreamSource extends QueuedSource {
    private final InputStream input;
    private volatile boolean discarding;

    public PcmStreamSource(InputStream input) {
        super(32);
        this.input = input;
        Thread thread = new Thread(this::pump, "Sonora stream reader");
        thread.setDaemon(true);
        thread.start();
    }

    private void pump() {
        byte[] buffer = new byte[4096];
        try {
            while (!this.closed) {
                int read = this.input.readNBytes(buffer, 0, buffer.length);
                int usable = read - (read % 4);
                if (usable <= 0) {
                    break;
                }
                float[] chunk = new float[usable / 2];
                for (int i = 0; i < chunk.length; i++) {
                    chunk[i] = (short) ((buffer[i * 2 + 1] << 8) | (buffer[i * 2] & 0xFF)) / 32768.0F;
                }
                if (this.discarding) {
                    continue;
                }
                if (!this.put(chunk)) {
                    break;
                }
            }
        } catch (IOException ignored) {
        }
        this.finished = true;
    }

    public void setDiscard(boolean discarding) {
        this.discarding = discarding;
    }

    @Override
    public void close() {
        super.close();
        try {
            this.input.close();
        } catch (IOException ignored) {
        }
    }
}
