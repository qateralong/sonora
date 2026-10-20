package com.qateralong.sonora.audio;

public interface AudioSource extends AutoCloseable {
    int END = -1;

    int read(float[] interleaved, int frames);

    default boolean seekable() {
        return false;
    }

    default void seek(long millis) {
    }

    default long positionMillis() {
        return -1L;
    }

    default void flush() {
    }

    @Override
    void close();
}
