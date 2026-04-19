package me.cortex.voxy.client.core.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanMesher2DTest {

    private static final class Quad {
        final int x, z, length, width;
        final long data;
        Quad(int x, int z, int length, int width, long data) {
            this.x = x; this.z = z; this.length = length; this.width = width; this.data = data;
        }
    }

    private static final class CapturingMesher extends ScanMesher2D {
        final List<Quad> quads = new ArrayList<>();
        @Override
        protected void emitQuad(int x, int z, int length, int width, long data) {
            quads.add(new Quad(x, z, length, width, data));
        }
    }

    /** Re-render the captured quads into a 32×32 buffer to verify round-trip. */
    private static long[] reconstruct(List<Quad> quads) {
        long[] out = new long[32 * 32];
        for (Quad q : quads) {
            int x0 = q.x - q.length + 1;
            int z0 = q.z - q.width + 1;
            for (int X = x0; X < x0 + q.length; X++) {
                for (int Z = z0; Z < z0 + q.width; Z++) {
                    int idx = Z * 32 + X;
                    assertEquals(0L, out[idx], "overlapping quad at idx " + idx);
                    out[idx] = q.data;
                }
            }
        }
        return out;
    }

    private static long[] feed(long[] data) {
        CapturingMesher m = new CapturingMesher();
        for (long v : data) m.putNext(v);
        m.finish();
        return reconstruct(m.quads);
    }

    @Test
    void allZerosEmitsNoQuads() {
        CapturingMesher m = new CapturingMesher();
        for (int i = 0; i < 32 * 32; i++) m.putNext(0);
        m.finish();
        assertEquals(0, m.quads.size());
    }

    @Test
    void singleNonZeroProducesOneQuad() {
        long[] data = new long[32 * 32];
        data[0] = 5L;
        long[] out = feed(data);
        for (int i = 0; i < data.length; i++) {
            assertEquals(data[i], out[i], "mismatch at " + i);
        }
    }

    @Test
    void contiguousRowMergesIntoSingleQuad() {
        // 4 cells in row 0 with the same value should merge into a single 4×1 quad.
        long[] data = new long[32 * 32];
        for (int x = 0; x < 4; x++) data[x] = 7L;
        CapturingMesher m = new CapturingMesher();
        for (long v : data) m.putNext(v);
        m.finish();

        // Find the quad with data 7.
        long total = 0;
        for (Quad q : m.quads) {
            if (q.data == 7L) total += (long) q.length * q.width;
        }
        assertEquals(4L, total, "quads with data=7 should cover exactly 4 cells");
    }

    @Test
    void rectangularBlockMergesAcrossRows() {
        // 4×3 block of value=9 in top-left.
        long[] data = new long[32 * 32];
        for (int z = 0; z < 3; z++) {
            for (int x = 0; x < 4; x++) {
                data[z * 32 + x] = 9L;
            }
        }
        long[] out = feed(data);
        for (int i = 0; i < data.length; i++) {
            assertEquals(data[i], out[i], "mismatch at " + i);
        }
    }

    @Test
    void distinctValuesProduceDistinctQuads() {
        long[] data = new long[32 * 32];
        for (int x = 0; x < 4; x++) data[x] = 1L;
        for (int x = 4; x < 8; x++) data[x] = 2L;
        long[] out = feed(data);
        for (int i = 0; i < data.length; i++) {
            assertEquals(data[i], out[i], "mismatch at " + i);
        }
    }

    @Test
    void filledGridReconstructsExactly() {
        long[] data = new long[32 * 32];
        for (int z = 0; z < 20; z++) {
            for (int x = 0; x < 20; x++) {
                data[z * 32 + x] = 1L;
            }
        }
        long[] out = feed(data);
        for (int i = 0; i < data.length; i++) {
            assertEquals(data[i], out[i], "mismatch at idx " + i);
        }
    }

    @Test
    void randomInputRoundTrips() {
        // Stress: random sparse fill, multiple values, verify reconstruction matches input.
        Random r = new Random(42);
        for (int trial = 0; trial < 20; trial++) {
            long[] data = new long[32 * 32];
            for (int i = 0; i < data.length; i++) {
                data[i] = r.nextFloat() < 0.7f ? r.nextInt(4) + 1 : 0;
            }
            long[] out = feed(data);
            for (int i = 0; i < data.length; i++) {
                assertEquals(data[i], out[i], "trial " + trial + " idx " + i);
            }
        }
    }

    @Test
    void resetClearsState() {
        CapturingMesher m = new CapturingMesher();
        m.putNext(1);
        m.putNext(1);
        m.reset();
        // After reset, finishing without input must emit nothing.
        m.finish();
        assertEquals(0, m.quads.size(), "reset+finish should emit no quads");
    }

    @Test
    void quadDimensionsRespectMaxSize() {
        // Fill 17 cells in a single row with the same value. ScanMesher2D's MAX_SIZE is 16,
        // so the run must split into at least two quads.
        long[] data = new long[32 * 32];
        for (int x = 0; x < 17; x++) data[x] = 3L;
        CapturingMesher m = new CapturingMesher();
        for (long v : data) m.putNext(v);
        m.finish();

        int quadsForValue3 = 0;
        for (Quad q : m.quads) {
            if (q.data == 3L) {
                quadsForValue3++;
                assertTrue(q.length <= 16, "length must respect MAX_SIZE=16");
                assertTrue(q.width <= 16, "width must respect MAX_SIZE=16");
            }
        }
        assertNotEquals(1, quadsForValue3, "17 cells must not collapse into one quad of width 17");
    }
}
