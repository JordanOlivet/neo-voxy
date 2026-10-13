package me.cortex.voxy.common.world.other;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperBitsTest {

    @Test
    void airPackedStateDetectedAsAir() {
        assertTrue(Mapper.isAir(0L));
        assertTrue(Mapper.isAir(Mapper.airWithLight(15)));
        assertTrue(Mapper.isAir(Mapper.airWithLight(0xFF)));
    }

    @Test
    void nonZeroBlockIdIsNotAir() {
        long state = ((long) 1) << 27;
        assertFalse(Mapper.isAir(state));
    }

    @Test
    void getBlockIdExtractsField() {
        long state = ((long) 0xABCDE) << 27;
        assertEquals(0xABCDE, Mapper.getBlockId(state));
    }

    @Test
    void getBlockIdIgnoresOtherFields() {
        long state = (((long) 0xABCDE) << 27)
                | (((long) 0x1FF) << 47)
                | (((long) 0xFF) << 56);
        assertEquals(0xABCDE, Mapper.getBlockId(state));
    }

    @Test
    void getBiomeIdExtractsField() {
        long state = ((long) 0x1AB) << 47;
        assertEquals(0x1AB, Mapper.getBiomeId(state));
    }

    @Test
    void getLightIdExtractsField() {
        long state = ((long) 0x7E) << 56;
        assertEquals(0x7E, Mapper.getLightId(state));
    }

    @Test
    void withLightReplacesLightBits() {
        long state = (((long) 0xABCDE) << 27) | (((long) 0x1AB) << 47) | (((long) 0x12) << 56);
        long updated = Mapper.withLight(state, 0xF3);
        assertEquals(0xF3, Mapper.getLightId(updated));
        assertEquals(0xABCDE, Mapper.getBlockId(updated));
        assertEquals(0x1AB, Mapper.getBiomeId(updated));
    }

    @Test
    void withLightMasksToByte() {
        long updated = Mapper.withLight(0L, 0x1FF);
        assertEquals(0xFF, Mapper.getLightId(updated));
    }

    @Test
    void airWithLightEncodesOnlyLight() {
        long v = Mapper.airWithLight(0x42);
        assertEquals(0x42, Mapper.getLightId(v));
        assertEquals(0, Mapper.getBlockId(v));
        assertEquals(0, Mapper.getBiomeId(v));
        assertTrue(Mapper.isAir(v));
    }

    @Test
    void composeMappingIdRoundTripsAllFields() {
        long v = Mapper.composeMappingId((byte) 0x7F, 0xABCDE, 0x1AB);
        assertEquals(0x7F, Mapper.getLightId(v));
        assertEquals(0xABCDE, Mapper.getBlockId(v));
        assertEquals(0x1AB, Mapper.getBiomeId(v));
        assertFalse(Mapper.isAir(v));
    }

    @Test
    void composeMappingIdDropsBiomeForAir() {
        // blockId == AIR (0) → biome is intentionally zeroed (air states share biome slot)
        long v = Mapper.composeMappingId((byte) 0x15, 0, 0x1AB);
        assertEquals(0x15, Mapper.getLightId(v));
        assertEquals(0, Mapper.getBlockId(v));
        assertEquals(0, Mapper.getBiomeId(v), "biome must be dropped when block is air");
        assertTrue(Mapper.isAir(v));
    }

    @Test
    void composeMappingIdTreatsLightAsUnsigned() {
        // Byte -1 = 0xFF unsigned; must not sign-extend into block/biome bits.
        long v = Mapper.composeMappingId((byte) -1, 1, 1);
        assertEquals(0xFF, Mapper.getLightId(v));
        assertEquals(1, Mapper.getBlockId(v));
        assertEquals(1, Mapper.getBiomeId(v));
    }
}
