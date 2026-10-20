package com.qateralong.sonora.audio;

public final class Equalizer {
    public static final float[] FREQUENCIES = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    public static final String[] LABELS = {"31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};
    private static final float Q = 1.1F;

    private final int rate;
    private final double[][] coefficients = new double[FREQUENCIES.length][5];
    private final double[][][] state = new double[2][FREQUENCIES.length][4];
    private final float[] gains = new float[FREQUENCIES.length];
    private volatile boolean dirty = true;
    private volatile boolean enabled = true;
    private volatile float preamp;

    public Equalizer(int rate) {
        this.rate = rate;
    }

    public void set(float[] gains, float preamp, boolean enabled) {
        System.arraycopy(gains, 0, this.gains, 0, Math.min(gains.length, this.gains.length));
        this.preamp = preamp;
        this.enabled = enabled;
        this.dirty = true;
    }

    private void update() {
        for (int band = 0; band < FREQUENCIES.length; band++) {
            double a = Math.pow(10.0, this.gains[band] / 40.0);
            double w = 2.0 * Math.PI * FREQUENCIES[band] / this.rate;
            if (w >= Math.PI) {
                w = Math.PI * 0.99;
            }
            double alpha = Math.sin(w) / (2.0 * Q);
            double cos = Math.cos(w);
            double a0 = 1.0 + alpha / a;
            double[] c = this.coefficients[band];
            c[0] = (1.0 + alpha * a) / a0;
            c[1] = -2.0 * cos / a0;
            c[2] = (1.0 - alpha * a) / a0;
            c[3] = -2.0 * cos / a0;
            c[4] = (1.0 - alpha / a) / a0;
        }
        this.dirty = false;
    }

    public void process(float[] interleaved, int frames) {
        if (this.dirty) {
            this.update();
        }
        if (!this.enabled) {
            return;
        }
        float pre = (float) Math.pow(10.0, this.preamp / 20.0);
        for (int ch = 0; ch < 2; ch++) {
            for (int band = 0; band < FREQUENCIES.length; band++) {
                if (this.gains[band] == 0.0F) {
                    continue;
                }
                double[] c = this.coefficients[band];
                double[] s = this.state[ch][band];
                for (int i = 0; i < frames; i++) {
                    int index = i * 2 + ch;
                    double x = interleaved[index];
                    double y = c[0] * x + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3];
                    s[1] = s[0];
                    s[0] = x;
                    s[3] = s[2];
                    s[2] = y;
                    interleaved[index] = (float) y;
                }
            }
            if (pre != 1.0F) {
                for (int i = 0; i < frames; i++) {
                    interleaved[i * 2 + ch] *= pre;
                }
            }
        }
    }

    public void reset() {
        for (double[][] channel : this.state) {
            for (double[] band : channel) {
                java.util.Arrays.fill(band, 0.0);
            }
        }
    }

    public static float[] preset(String name) {
        return switch (name) {
            case "bass" -> new float[]{7, 6, 5, 3, 1, 0, 0, 0, 0, 0};
            case "rock" -> new float[]{5, 4, 3, 1, -1, -1, 1, 3, 4, 5};
            case "pop" -> new float[]{-1, 1, 3, 4, 4, 2, 0, -1, -1, -1};
            case "jazz" -> new float[]{3, 2, 1, 2, -1, -1, 0, 1, 2, 3};
            case "classical" -> new float[]{4, 3, 2, 1, -1, -1, 0, 2, 3, 4};
            case "electronic" -> new float[]{5, 4, 1, 0, -2, 2, 1, 1, 4, 5};
            case "vocal" -> new float[]{-2, -2, -1, 1, 3, 4, 3, 1, 0, -1};
            case "treble" -> new float[]{0, 0, 0, 0, 0, 1, 3, 5, 6, 7};
            case "loudness" -> new float[]{6, 4, 0, 0, -2, 0, -1, -3, 3, 4};
            default -> new float[10];
        };
    }

    public static final String[] PRESETS = {"flat", "bass", "rock", "pop", "jazz", "classical", "electronic", "vocal", "treble", "loudness"};
}
