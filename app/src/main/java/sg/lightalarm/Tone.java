package sg.lightalarm;

import android.media.*;
import android.os.*;

final class Tone implements AutoCloseable {
    static final String[] NAMES = {"Silent", "Soft bells", "Pulse", "Bright chime"};
    static final String[] PATTERNS = {"Off", "Steady", "Double tap", "Slow pulse"};
    private static final int RATE = 22_050;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioTrack track;

    void play(int which, int fade, long elapsed) {
        close();
        if (which <= 0) return;
        short[] samples = new short[RATE * 2];
        double[] pitches = switch (which) {
            case 2 -> new double[]{520, 520, 520};
            case 3 -> new double[]{600, 750, 900};
            default -> new double[]{392, 523.25};
        };
        for (int i = 0; i < samples.length; i++) {
            double t = (double)i / RATE;
            int note = (int)(t / .30);
            if (note >= pitches.length) continue;
            double beat = t - note * .30;
            double envelope = Math.min(1, beat * 90) * Math.max(0, 1 - beat / .27);
            double wave = Math.sin(2 * Math.PI * pitches[note] * beat)
                    + .19 * Math.sin(4 * Math.PI * pitches[note] * beat);
            samples[i] = (short)(wave * envelope * 15_000);
        }
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.length * 2).build();
        if (track.write(samples, 0, samples.length) != samples.length
                || track.getState() != AudioTrack.STATE_INITIALIZED
                || track.setLoopPoints(0, samples.length, -1) != AudioTrack.SUCCESS) {
            close();
            return;
        }
        long started = SystemClock.elapsedRealtime() - Math.max(0, elapsed);
        ramp(started, fade * 1000L);
        track.play();
    }

    private void ramp(long started, long duration) {
        if (track == null) return;
        float fraction = duration == 0 ? 1 : Math.min(1f, (SystemClock.elapsedRealtime() - started) / (float)duration);
        track.setVolume(.12f + .88f * fraction);
        if (fraction < 1) handler.postDelayed(() -> ramp(started, duration), 180);
    }

    @Override public void close() {
        handler.removeCallbacksAndMessages(null);
        if (track != null) {
            track.release();
            track = null;
        }
    }

    static VibrationEffect pattern(int number, boolean repeat) {
        long[] intervals = switch (number) {
            case 2 -> new long[]{0, 160, 100, 160, 650};
            case 3 -> new long[]{0, 240, 1000};
            default -> new long[]{0, 450, 450};
        };
        return VibrationEffect.createWaveform(intervals, repeat ? 0 : -1);
    }
}
