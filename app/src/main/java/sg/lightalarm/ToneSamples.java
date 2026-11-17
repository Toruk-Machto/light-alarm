package sg.lightalarm;

import java.util.Random;

/** Small procedural sound bank: no recordings, downloads or audio asset dependencies. */
final class ToneSamples {
    static final int RATE = 22_050;
    static final String[] NAMES = {"Silent", "Soft bells", "Pulse", "Bright chime", "Gentle rain",
            "Ocean waves", "Birdsong", "Wind chimes", "Warm bell", "Marimba", "Classic bell", "Urgent pulse", "Rising beacon"};

    static short[] render(int which) {
        short[] samples = new short[RATE * 6];
        Random random = new Random(8321L + which);
        double filtered = 0;
        for (int i = 0; i < samples.length; i++) {
            double t = i / (double) RATE, wave = 0;
            double noise = random.nextDouble() * 2 - 1;
            filtered = filtered * .94 + noise * .06;
            if (which >= 1 && which <= 3) {
                double[] pitches = which == 2 ? new double[]{520, 520, 520}
                        : which == 3 ? new double[]{600, 750, 900} : new double[]{392, 523.25};
                double cycle = t % 2;
                int note = (int)(cycle / .30);
                if (note < pitches.length) {
                    double beat = cycle - note * .30;
                    wave = (sin(pitches[note], beat) + .19 * sin(2 * pitches[note], beat))
                            * Math.min(1, beat * 90) * Math.max(0, 1 - beat / .27) * .46;
                }
            } else if (which == 4) {
                wave = (.22 * noise + 1.5 * filtered) * (.65 + .15 * Math.sin(2 * Math.PI * t / 3));
            } else if (which == 5) {
                wave = (2.5 * filtered + .12 * noise) * (.25 + .65 * Math.pow(Math.sin(Math.PI * t / 6), 2));
            } else if (which == 6) {
                double beat = t % .75;
                if (beat < .23) wave = .33 * Math.sin(2 * Math.PI * (1350 * beat + 2400 * beat * beat)) * Math.pow(Math.sin(Math.PI * beat / .23), 2);
            } else if (which >= 7 && which <= 10) {
                double space = which == 7 ? .75 : which == 8 ? 2 : which == 9 ? .375 : .5;
                double beat = t % space;
                double[] pitches = {523.25, 659.25, 783.99, 1046.5};
                double freq = which == 8 ? 330 : which == 10 ? 780 : pitches[(int)(t / space) % 4];
                wave = (sin(freq, beat) + .35 * sin(freq * (which == 10 ? 2.76 : 2), beat))
                        * Math.min(1, beat * 150) * Math.exp(-beat * (which == 8 ? 3 : 8)) * .52;
            } else if (which == 11) {
                double beat = t % .3;
                if (beat < .20) wave = (sin(880, beat) + .3 * sin(1760, beat)) * .6
                        * Math.min(1, beat * 180) * Math.min(1, (.20 - beat) * 180);
            } else if (which == 12) {
                double beat = t % 1.5;
                wave = Math.sin(2 * Math.PI * (400 * beat + 240 * beat * beat)) * .7
                        * Math.min(1, beat * 40) * Math.min(1, (1.5 - beat) * 40);
            }
            // A tiny boundary fade makes the static loop click-free, including noise-based tones.
            double edge = Math.min(1, Math.min(t, (samples.length - 1 - i) / (double) RATE) / .015);
            samples[i] = (short)(Math.max(-.95, Math.min(.95, wave)) * edge * 32767);
        }
        return samples;
    }
    private static double sin(double hz, double seconds) { return Math.sin(2 * Math.PI * hz * seconds); }
}
