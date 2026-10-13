package me.cortex.voxy.common.voxelization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class VoxelizedSectionTest {

    private static final int LVL0_SIZE = 16 * 16 * 16;
    private static final int LVL1_SIZE = 8 * 8 * 8;
    private static final int LVL2_SIZE = 4 * 4 * 4;
    private static final int LVL3_SIZE = 2 * 2 * 2;
    private static final int EXPECTED_TOTAL = LVL0_SIZE + LVL1_SIZE + LVL2_SIZE + LVL3_SIZE + 1;

    @Test
    void createEmptyAllocatesPyramidSized() {
        VoxelizedSection v = VoxelizedSection.createEmpty();
        assertEquals(EXPECTED_TOTAL, v.section.length);
        assertEquals(0, v.lvl0NonAirCount);
    }

    @Test
    void getBaseIndexForLevelMatchesPyramidLayout() {
        assertEquals(0, VoxelizedSection.getBaseIndexForLevel(0));
        assertEquals(LVL0_SIZE, VoxelizedSection.getBaseIndexForLevel(1));
        assertEquals(LVL0_SIZE + LVL1_SIZE, VoxelizedSection.getBaseIndexForLevel(2));
        assertEquals(LVL0_SIZE + LVL1_SIZE + LVL2_SIZE, VoxelizedSection.getBaseIndexForLevel(3));
        assertEquals(LVL0_SIZE + LVL1_SIZE + LVL2_SIZE + LVL3_SIZE,
                VoxelizedSection.getBaseIndexForLevel(4));
    }

    @Test
    void setPositionFluentAndStoresValues() {
        VoxelizedSection v = VoxelizedSection.createEmpty();
        VoxelizedSection ret = v.setPosition(3, -7, 12);
        assertSame(v, ret, "setPosition must return this");
        assertEquals(3, v.x);
        assertEquals(-7, v.y);
        assertEquals(12, v.z);
    }

    @Test
    void getReadsFromCorrectLevelOffset() {
        VoxelizedSection v = VoxelizedSection.createEmpty();
        v.section[0] = 0xAAAA;
        assertEquals(0xAAAA, v.get(0, 0, 0, 0));

        v.section[LVL0_SIZE] = 0xBBBB;
        assertEquals(0xBBBB, v.get(1, 0, 0, 0));

        v.section[LVL0_SIZE + LVL1_SIZE] = 0xCCCC;
        assertEquals(0xCCCC, v.get(2, 0, 0, 0));

        v.section[LVL0_SIZE + LVL1_SIZE + LVL2_SIZE] = 0xDDDD;
        assertEquals(0xDDDD, v.get(3, 0, 0, 0));

        v.section[LVL0_SIZE + LVL1_SIZE + LVL2_SIZE + LVL3_SIZE] = 0xEEEE;
        assertEquals(0xEEEE, v.get(4, 0, 0, 0));
    }

    @Test
    void getLvl0IndexingMatchesYZXFormula() {
        // Index formula at lvl 0: (y << 8) | (z << 4) | x  for size = 4 (16^3 = 4096)
        VoxelizedSection v = VoxelizedSection.createEmpty();
        int idx = (5 << 8) | (3 << 4) | 7;
        v.section[idx] = 0x12345L;
        assertEquals(0x12345L, v.get(0, 7, 5, 3));
    }

    @Test
    void zeroResetsCountAndArray() {
        VoxelizedSection v = VoxelizedSection.createEmpty();
        v.lvl0NonAirCount = 1234;
        for (int i = 0; i < v.section.length; i++) v.section[i] = i + 1;

        VoxelizedSection ret = v.zero();
        assertSame(v, ret);
        assertEquals(0, v.lvl0NonAirCount);
        for (int i = 0; i < v.section.length; i++) {
            assertEquals(0L, v.section[i], "slot " + i + " must be cleared");
        }
    }

    @Test
    void constructorAcceptsExternalArray() {
        long[] backing = new long[EXPECTED_TOTAL];
        backing[42] = 0xDEADL;
        VoxelizedSection v = new VoxelizedSection(backing);
        assertSame(backing, v.section);
        assertEquals(0xDEADL, v.section[42]);
    }

    @Test
    void distinctEmptyInstancesShareNoState() {
        VoxelizedSection a = VoxelizedSection.createEmpty();
        VoxelizedSection b = VoxelizedSection.createEmpty();
        assertNotSame(a.section, b.section);
        a.section[0] = 1L;
        assertEquals(0L, b.section[0]);
    }
}
