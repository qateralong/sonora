package com.qateralong.sonora.audio;

public final class StreamResampler {
    private static final int HISTORY = 3;

    private final double step;
    private double position;
    private float[] history = new float[HISTORY * 2];
    private boolean primed;

    public StreamResampler(int from, int to) {
        this.step = (double) from / to;
    }

    public boolean identity() {
        return this.step == 1.0;
    }

    public float[] process(float[] stereo) {
        if (this.identity()) {
            return stereo;
        }
        int frames = stereo.length / 2;
        if (frames == 0) {
            return stereo;
        }
        if (!this.primed) {
            for (int i = 0; i < HISTORY; i++) {
                this.history[i * 2] = stereo[0];
                this.history[i * 2 + 1] = stereo[1];
            }
            this.primed = true;
        }
        float[] work = new float[(HISTORY + frames) * 2];
        System.arraycopy(this.history, 0, work, 0, HISTORY * 2);
        System.arraycopy(stereo, 0, work, HISTORY * 2, stereo.length);
        int capacity = (int) Math.ceil((frames + 1) / this.step) + 2;
        float[] out = new float[capacity * 2];
        int count = 0;
        while (this.position < frames - 2) {
            int base = (int) Math.floor(this.position);
            float t = (float) (this.position - base);
            int index = base + HISTORY;
            for (int c = 0; c < 2; c++) {
                float p0 = work[(index - 1) * 2 + c];
                float p1 = work[index * 2 + c];
                float p2 = work[(index + 1) * 2 + c];
                float p3 = work[(index + 2) * 2 + c];
                out[count * 2 + c] = 0.5F * ((2.0F * p1) + (-p0 + p2) * t + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * t * t
                        + (-p0 + 3.0F * p1 - 3.0F * p2 + p3) * t * t * t);
            }
            count++;
            this.position += this.step;
        }
        this.position -= frames;
        System.arraycopy(work, (work.length / 2 - HISTORY) * 2, this.history, 0, HISTORY * 2);
        return java.util.Arrays.copyOf(out, count * 2);
    }
}
