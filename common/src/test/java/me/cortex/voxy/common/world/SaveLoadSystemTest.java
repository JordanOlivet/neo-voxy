package me.cortex.voxy.common.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveLoadSystemTest {

    private static final int VOLUME = 32 * 32 * 32;

    @Test
    void lin2zRoundTrip() {
        for (int i = 0; i < VOLUME; i++) {
            int z = SaveLoadSystem.lin2z(i);
            int back = SaveLoadSystem.z2lin(z);
            assertEquals(i, back, "round-trip mismatch for index " + i);
        }
    }

    @Test
    void z2linInverseOfLin2z() {
        for (int i = 0; i < VOLUME; i++) {
            int back = SaveLoadSystem.z2lin(SaveLoadSystem.lin2z(i));
            assertEquals(i, back);
        }
    }

    @Test
    void lin2zProducesUniqueOutputs() {
        boolean[] seen = new boolean[VOLUME];
        for (int i = 0; i < VOLUME; i++) {
            int z = SaveLoadSystem.lin2z(i);
            assertTrue(z >= 0 && z < VOLUME, "Morton index out of range: " + z);
            assertTrue(!seen[z], "duplicate Morton index for linear " + i);
            seen[z] = true;
        }
    }

    @Test
    void lin2zDecomposesYZX() {
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    int linear = (y << 10) | (z << 5) | x;
                    int morton = SaveLoadSystem.lin2z(linear);
                    int xBits = Integer.compress(morton, 0b1001001001001);
                    int yBits = Integer.compress(morton, 0b10010010010010);
                    int zBits = Integer.compress(morton, 0b100100100100100);
                    assertEquals(x, xBits, "x mismatch");
                    assertEquals(y, yBits, "y mismatch");
                    assertEquals(z, zBits, "z mismatch");
                }
            }
        }
    }
}
