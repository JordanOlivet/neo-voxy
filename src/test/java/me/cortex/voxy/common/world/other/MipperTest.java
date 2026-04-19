package me.cortex.voxy.common.world.other;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MipperTest {

    /** Encode an air voxel with packed sky/block light: high nibble = block, low nibble = sky. */
    private static long air(int blockLight, int skyLight) {
        return Mapper.airWithLight(((blockLight & 0xF) << 4) | (skyLight & 0xF));
    }

    @Test
    void allAirReturnsAirShape() {
        long out = Mipper.mip(
                air(0, 0), air(0, 0), air(0, 0), air(0, 0),
                air(0, 0), air(0, 0), air(0, 0), air(0, 0),
                null);
        assertEquals(0, Mapper.getBlockId(out));
    }

    @Test
    void allAirCeilsSkyLight() {
        // Sky-light values total 1; ceil(1/8) = 1 (NOT floor → 0).
        long out = Mipper.mip(
                air(0, 0), air(0, 0), air(0, 0), air(0, 0),
                air(0, 0), air(0, 0), air(0, 0), air(0, 1),
                null);
        int actualSkyLight = Mapper.getLightId(out) & 0xF;
        assertEquals(1, actualSkyLight, "sky light should ceil up to 1, not floor to 0");
    }

    @Test
    void allAirSkyLightZeroWhenAllZero() {
        long out = Mipper.mip(
                air(0, 0), air(0, 0), air(0, 0), air(0, 0),
                air(0, 0), air(0, 0), air(0, 0), air(0, 0),
                null);
        assertEquals(0, Mapper.getLightId(out) & 0xF);
    }

    @Test
    void allAirSkyLightAveragesEvenly() {
        // All sky=8 → ceil(64/8) = 8.
        long out = Mipper.mip(
                air(0, 8), air(0, 8), air(0, 8), air(0, 8),
                air(0, 8), air(0, 8), air(0, 8), air(0, 8),
                null);
        assertEquals(8, Mapper.getLightId(out) & 0xF);
    }

    @Test
    void allAirPreservesUniformBlockLight() {
        // All eight voxels with block-light = 8: average must come back as 8.
        long v = air(8, 0);
        long out = Mipper.mip(v, v, v, v, v, v, v, v, null);
        int blockNibble = (Mapper.getLightId(out) >> 4) & 0xF;
        assertEquals(8, blockNibble, "uniform block light must round-trip");
    }

    @Test
    void allAirAveragesBlockLightArithmetically() {
        // Block-light values 0,2,4,6,8,10,12,14 → arithmetic mean 7.
        long out = Mipper.mip(
                air(0, 0), air(2, 0), air(4, 0), air(6, 0),
                air(8, 0), air(10, 0), air(12, 0), air(14, 0),
                null);
        int blockNibble = (Mapper.getLightId(out) >> 4) & 0xF;
        assertEquals(7, blockNibble, "block light should be arithmetic mean (0+2+...+14)/8 = 7");
    }

    @Test
    void allAirCombinesBlockAndSkyLightIndependently() {
        // Different block-light and sky-light values must both survive in their nibbles.
        long out = Mipper.mip(
                air(15, 1), air(15, 1), air(15, 1), air(15, 1),
                air(15, 1), air(15, 1), air(15, 1), air(15, 1),
                null);
        int light = Mapper.getLightId(out);
        assertEquals(15, (light >> 4) & 0xF, "block nibble");
        assertEquals(1, light & 0xF, "sky nibble");
    }
}
