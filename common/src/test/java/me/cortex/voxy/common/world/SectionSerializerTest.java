package me.cortex.voxy.common.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionSerializerTest {

    private static WorldSection emptySection(int lvl, int x, int y, int z) {
        return WorldSection._createRawUntrackedUnsafeSection(lvl, x, y, z);
    }

    @Test
    void roundTripEmptySection() {
        WorldSection s = emptySection(2, 10, -5, 7);
        byte[] bytes = SectionSerializer.serialize(s);
        assertNotNull(bytes);

        SectionSerializer.SectionData data = SectionSerializer.deserialize(bytes);
        assertNotNull(data);
        assertEquals(2, data.level);
        assertEquals(10, data.x);
        assertEquals(-5, data.y);
        assertEquals(7, data.z);
        assertEquals((byte) 0, data.nonEmptyChildren);
        assertFalse(data.hasData());
        assertNull(data.voxelData);
    }

    @Test
    void roundTripSectionWithData() {
        WorldSection s = emptySection(0, 1, 2, 3);
        long[] raw = s._unsafeGetRawDataArray();
        raw[0] = 0x00000000_DEADBEEFL;
        raw[100] = 0x01234567_89ABCDEFL;
        raw[raw.length - 1] = 0x11111111_22222222L;
        s.addNonEmptyBlockCount(3);

        byte[] bytes = SectionSerializer.serialize(s);
        SectionSerializer.SectionData data = SectionSerializer.deserialize(bytes);

        assertNotNull(data);
        assertTrue(data.hasData());
        assertEquals(WorldSection.SECTION_VOLUME, data.voxelData.length);
        assertArrayEquals(raw, data.voxelData);
    }

    @Test
    void nonEmptyChildrenFlagIsPreserved() {
        WorldSection s = emptySection(3, 0, 0, 0);
        s._unsafeSetNonEmptyChildren((byte) 0b10101010);

        byte[] bytes = SectionSerializer.serialize(s);
        SectionSerializer.SectionData data = SectionSerializer.deserialize(bytes);
        assertEquals((byte) 0b10101010, data.nonEmptyChildren);
    }

    @Test
    void keyMatchesWorldEngineEncoding() {
        WorldSection s = emptySection(4, 42, 7, -11);
        byte[] bytes = SectionSerializer.serialize(s);
        SectionSerializer.SectionData data = SectionSerializer.deserialize(bytes);
        assertEquals(WorldEngine.getWorldSectionId(4, 42, 7, -11), data.getKey());
    }

    @Test
    void compressionTriggersForRepetitivePayload() {
        WorldSection s = emptySection(0, 0, 0, 0);
        long[] raw = s._unsafeGetRawDataArray();
        long repeated = 0x1111_2222_3333_4444L;
        for (int i = 0; i < raw.length; i++) raw[i] = repeated;
        s.addNonEmptyBlockCount(1);

        byte[] bytes = SectionSerializer.serialize(s);
        int uncompressedSize = WorldSection.SECTION_VOLUME * 8 + 4;
        assertTrue(bytes.length < uncompressedSize, "gzip should shrink repeated data");

        SectionSerializer.SectionData data = SectionSerializer.deserialize(bytes);
        assertTrue(data.hasData());
        assertEquals(repeated, data.voxelData[0]);
        assertEquals(repeated, data.voxelData[raw.length - 1]);
    }

    @Test
    void deserializeTruncatedReturnsNull() {
        WorldSection s = emptySection(1, 0, 0, 0);
        byte[] bytes = SectionSerializer.serialize(s);
        byte[] truncated = new byte[bytes.length - 10];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);
        assertNull(SectionSerializer.deserialize(truncated));
    }
}
