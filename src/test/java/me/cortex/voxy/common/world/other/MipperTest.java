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
        // The result must still be air (block component = 0).
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
    void allAirSkyLightFlooredAtMostOnceWhenAllZero() {
        // Sky=0 across the board → ceil(0/8) = 0.
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
    void allAirBlockLightAveragingIsBuggyAndOverflows() {
        // Documents a real bug: the all-air branch in Mipper.mip computes
        //   blockLight = sum(light & 0xF0) / 8
        //   ...
        //   return withLight(I111, (blockLight << 4) | skyLight);
        // But (light & 0xF0) keeps the nibble at its high position (0..240, step 16),
        // so after /8 the block value is already at high-nibble position (e.g. 0x70),
        // and the extra "<< 4" shifts it past the byte (0x700). withLight then masks
        // with 0xFF, so the entire block-light component is silently zeroed.
        //
        // This test pins the current (broken) behavior — change it if/when the bug is fixed.
        long out = Mipper.mip(
                air(8, 0), air(8, 0), air(8, 0), air(8, 0),
                air(8, 0), air(8, 0), air(8, 0), air(8, 0),
                null);
        int blockNibble = (Mapper.getLightId(out) >> 4) & 0xF;
        assertEquals(0, blockNibble,
                "current implementation drops block-light average; remove this assertion when fixed");
    }
}
