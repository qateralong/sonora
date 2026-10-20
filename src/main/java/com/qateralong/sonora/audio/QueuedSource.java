package com.qateralong.sonora.audio;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public abstract class QueuedSource implements AudioSource {
    protected final ArrayBlockingQueue<float[]> queue;
    protected volatile boolean closed;
    protected volatile boolean finished;
    private float[] current;
    private int offset;

    protected QueuedSource(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    protected boolean put(float[] chunk) {
        while (!this.closed) {
            try {
                if (this.queue.offer(chunk, 50, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public int read(float[] out, int frames) {
        int written = 0;
        while (written < frames) {
            if (this.current == null || this.offset >= this.current.length) {
                try {
                    this.current = written == 0 ? this.queue.poll(20, TimeUnit.MILLISECONDS) : this.queue.poll();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return written;
                }
                this.offset = 0;
                if (this.current == null) {
                    if (written == 0 && this.finished && this.queue.isEmpty()) {
                        return END;
                    }
                    return written;
                }
            }
            int take = Math.min((frames - written) * 2, this.current.length - this.offset);
            System.arraycopy(this.current, this.offset, out, written * 2, take);
            this.offset += take;
            written += take / 2;
        }
        return written;
    }

    @Override
    public void flush() {
        this.queue.clear();
        this.current = null;
    }

    @Override
    public void close() {
        this.closed = true;
        this.queue.clear();
    }
}
