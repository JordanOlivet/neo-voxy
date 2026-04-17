package me.cortex.voxy.common.world;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldEngineKeyTest {

    private static void assertRoundTrip(int lvl, int x, int y, int z) {
        long key = WorldEngine.getWorldSectionId(lvl, x, y, z);
        assertEquals(lvl, WorldEngine.getLevel(key), "lvl");
        assertEquals(x, WorldEngine.getX(key), "x");
        assertEquals(y, WorldEngine.getY(key), "y");
        assertEquals(z, WorldEngine.getZ(key), "z");
    }

    @Test
    void originRoundTrip() {
        assertRoundTrip(0, 0, 0, 0);
    }

    @Test
    void positiveCoordinatesRoundTrip() {
        assertRoundTrip(2, 1234, 50, -5678);
        assertRoundTrip(4, 100, 120, 100);
    }

    @Test
    void negativeCoordinatesRoundTripSignExtended() {
        assertRoundTrip(0, -1, -1, -1);
        assertRoundTrip(1, -1000, -100, -2000);
    }

    @Test
    void maxLevelRoundTrip() {
        assertRoundTrip(WorldEngine.MAX_LOD_LAYER, 0, 0, 0);
    }

    @Test
    void xzRangeBoundariesRoundTrip() {
        int maxXZ = (1 << 23) - 1;
        int minXZ = -(1 << 23);
        assertRoundTrip(0, maxXZ, 0, maxXZ);
        assertRoundTrip(0, minXZ, 0, minXZ);
    }

    @Test
    void yRangeBoundariesRoundTrip() {
        assertRoundTrip(0, 0, 127, 0);
        assertRoundTrip(0, 0, -128, 0);
    }

    @Test
    void randomCoordinatesRoundTrip() {
        Random r = new Random(0xBEEFL);
        for (int i = 0; i < 1000; i++) {
            int lvl = r.nextInt(WorldEngine.MAX_LOD_LAYER + 1);
            int x = r.nextInt(1 << 24) - (1 << 23);
            int z = r.nextInt(1 << 24) - (1 << 23);
            int y = r.nextInt(256) - 128;
            assertRoundTrip(lvl, x, y, z);
        }
    }

    @Test
    void differentCoordinatesProduceDifferentKeys() {
        long a = WorldEngine.getWorldSectionId(0, 1, 0, 0);
        long b = WorldEngine.getWorldSectionId(0, 0, 1, 0);
        long c = WorldEngine.getWorldSectionId(0, 0, 0, 1);
        long d = WorldEngine.getWorldSectionId(1, 0, 0, 0);
        assertEquals(4, java.util.Set.of(a, b, c, d).size());
    }
}
