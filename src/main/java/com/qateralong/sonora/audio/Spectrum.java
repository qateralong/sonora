package com.qateralong.sonora.audio;

public final class Spectrum {
    private static final int SIZE = 2048;
    private final float[] ring = new float[SIZE];
    private int ringPos;
    private final double[] re = new double[SIZE];
    private final double[] im = new double[SIZE];
    private final double[] window = new double[SIZE];
    private final int rate;
    private volatile float[] bars = new float[64];
    private float[] peaks = new float[64];
    private float[] fall = new float[64];
    private float[] memory = new float[64];
    private double sensitivity = 1.0;
    private volatile float rms;

    public Spectrum(int rate) {
        this.rate = rate;
        for (int i = 0; i < SIZE; i++) {
            this.window[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (SIZE - 1)));
        }
    }

    public float[] bars() {
        return this.bars;
    }

    public float rms() {
        return this.rms;
    }

    public void feed(float[] interleaved, int frames) {
        double sum = 0.0;
        for (int i = 0; i < frames; i++) {
            float mono = (interleaved[i * 2] + interleaved[i * 2 + 1]) * 0.5F;
            this.ring[this.ringPos] = mono;
            this.ringPos = (this.ringPos + 1) & (SIZE - 1);
            sum += mono * mono;
        }
        this.rms = frames == 0 ? 0.0F : (float) Math.sqrt(sum / frames);
    }

    public void decay() {
        float[] current = this.bars;
        float[] next = new float[current.length];
        for (int i = 0; i < current.length; i++) {
            next[i] = current[i] * 0.85F;
        }
        this.bars = next;
        this.rms *= 0.85F;
    }

    public void analyze(int count) {
        if (this.peaks.length != count) {
            this.peaks = new float[count];
            this.fall = new float[count];
            this.memory = new float[count];
        }
        for (int i = 0; i < SIZE; i++) {
            this.re[i] = this.ring[(this.ringPos + i) & (SIZE - 1)] * this.window[i];
            this.im[i] = 0.0;
        }
        fft(this.re, this.im);
        double low = 40.0;
        double high = 16000.0;
        float[] raw = new float[count];
        double binHz = (double) this.rate / SIZE;
        for (int b = 0; b < count; b++) {
            double f0 = low * Math.pow(high / low, (double) b / count);
            double f1 = low * Math.pow(high / low, (double) (b + 1) / count);
            int i0 = Math.max(1, (int) Math.floor(f0 / binHz));
            int i1 = Math.max(i0 + 1, (int) Math.ceil(f1 / binHz));
            double peak = 0.0;
            for (int i = i0; i < Math.min(i1, SIZE / 2); i++) {
                double magnitude = Math.sqrt(this.re[i] * this.re[i] + this.im[i] * this.im[i]);
                peak = Math.max(peak, magnitude);
            }
            double weighted = peak * Math.pow((f0 + f1) * 0.5 / 1000.0, 0.45);
            raw[b] = (float) weighted;
        }
        for (int b = 0; b < count; b++) {
            for (int o = 1; o < 4; o++) {
                float spread = (float) Math.pow(0.65, o);
                if (b - o >= 0) {
                    raw[b] = Math.max(raw[b], raw[b - o] * spread);
                }
                if (b + o < count) {
                    raw[b] = Math.max(raw[b], raw[b + o] * spread);
                }
            }
        }
        float max = 0.0F;
        float[] out = new float[count];
        for (int b = 0; b < count; b++) {
            float value = (float) (raw[b] * this.sensitivity / 40.0);
            value = this.memory[b] * 0.55F + value * 0.45F;
            this.memory[b] = value;
            if (value < this.peaks[b]) {
                this.fall[b] += 0.012F;
                value = Math.max(value, this.peaks[b] - this.fall[b] * this.fall[b] * 6.0F);
            } else {
                this.fall[b] = 0.0F;
            }
            this.peaks[b] = value;
            out[b] = value;
            max = Math.max(max, value);
        }
        if (max > 1.0F) {
            this.sensitivity *= 0.97;
        } else if (max < 0.6F && max > 0.01F) {
            this.sensitivity *= 1.01;
        }
        this.sensitivity = Math.max(0.02, Math.min(50.0, this.sensitivity));
        for (int b = 0; b < count; b++) {
            out[b] = Math.min(1.0F, out[b]);
        }
        this.bars = out;
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wr = Math.cos(angle);
            double wi = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double cr = 1.0;
                double ci = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j;
                    int b = a + len / 2;
                    double tr = re[b] * cr - im[b] * ci;
                    double ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    double next = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = next;
                }
            }
        }
    }
}
