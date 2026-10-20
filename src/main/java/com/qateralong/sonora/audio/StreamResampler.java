package com.qateralong.sonora.audio;

public final class StreamResampler {
    private final double step;
    private double position;
    private float lastLeft;
    private float lastRight;
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
            this.lastLeft = stereo[0];
            this.lastRight = stereo[1];
            this.primed = true;
        }
        int capacity = (int) Math.ceil((frames + 1) / this.step) + 2;
        float[] out = new float[capacity * 2];
        int count = 0;
        while (this.position < frames) {
            int index = (int) Math.floor(this.position);
            double frac = this.position - index;
            float l0 = index - 1 < 0 ? this.lastLeft : stereo[(index - 1) * 2];
            float r0 = index - 1 < 0 ? this.lastRight : stereo[(index - 1) * 2 + 1];
            float l1 = stereo[index * 2];
            float r1 = stereo[index * 2 + 1];
            out[count * 2] = (float) (l0 + (l1 - l0) * frac);
            out[count * 2 + 1] = (float) (r0 + (r1 - r0) * frac);
            count++;
            this.position += this.step;
        }
        this.position -= frames;
        this.lastLeft = stereo[(frames - 1) * 2];
        this.lastRight = stereo[(frames - 1) * 2 + 1];
        return java.util.Arrays.copyOf(out, count * 2);
    }
}
