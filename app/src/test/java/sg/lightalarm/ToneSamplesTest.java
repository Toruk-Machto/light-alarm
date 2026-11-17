package sg.lightalarm;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public final class ToneSamplesTest {
    @Test public void allSoundsAreDistinctBoundedAndLoopCleanly() {
        Set<Integer> hashes = new HashSet<>();
        for (int tone = 1; tone < ToneSamples.NAMES.length; tone++) {
            short[] pcm = ToneSamples.render(tone);
            assertEquals(ToneSamples.RATE * 6, pcm.length);
            assertEquals(0, pcm[0]); assertEquals(0, pcm[pcm.length - 1]);
            long energy = 0; long sum = 0;
            for (short sample : pcm) {
                assertTrue(Math.abs((int)sample) <= 31129);
                energy += (long)sample * sample; sum += sample;
            }
            assertTrue("audible " + tone, Math.sqrt(energy / (double)pcm.length) > 500);
            assertTrue("no DC offset " + tone, Math.abs(sum / (double)pcm.length) < 100);
            assertTrue("unique " + tone, hashes.add(Arrays.hashCode(pcm)));
        }
    }
    @Test public void silentReallyIsSilent() {
        for (short sample : ToneSamples.render(0)) assertEquals(0, sample);
    }
}
