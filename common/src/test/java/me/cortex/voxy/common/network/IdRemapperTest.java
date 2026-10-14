package me.cortex.voxy.common.network;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IdRemapperTest {

    private static Int2IntOpenHashMap blockMap(IdRemapper r) throws Exception {
        Field f = IdRemapper.class.getDeclaredField("serverToClientBlock");
        f.setAccessible(true);
        return (Int2IntOpenHashMap) f.get(r);
    }

    private static Int2IntOpenHashMap biomeMap(IdRemapper r) throws Exception {
        Field f = IdRemapper.class.getDeclaredField("serverToClientBiome");
        f.setAccessible(true);
        return (Int2IntOpenHashMap) f.get(r);
    }

    @Test
    void freshRemapperIsNotReady() {
        IdRemapper r = new IdRemapper();
        assertFalse(r.isReady());
    }

    @Test
    void remapWithoutDataMapsEverythingToDefaultZero() {
        IdRemapper r = new IdRemapper();
        // Server voxel: block=42, biome=7, light=0xCD
        long serverVoxel = Mapper.composeMappingId((byte) 0xCD, 42, 7);
        long clientVoxel = r.remapVoxelId(serverVoxel);
        // Default-return = 0 → client block=0, client biome=0 → composeMappingId routes to air branch.
        assertEquals(0, Mapper.getBlockId(clientVoxel));
        assertEquals(0xCD, Mapper.getLightId(clientVoxel));
    }

    @Test
    void remapPreservesLightAndAppliesMappingTables() throws Exception {
        IdRemapper r = new IdRemapper();
        blockMap(r).put(42, 100);
        biomeMap(r).put(7, 17);

        long serverVoxel = Mapper.composeMappingId((byte) 0x80, 42, 7);
        long clientVoxel = r.remapVoxelId(serverVoxel);

        assertEquals(100, Mapper.getBlockId(clientVoxel));
        assertEquals(17, Mapper.getBiomeId(clientVoxel));
        assertEquals(0x80, Mapper.getLightId(clientVoxel));
    }

    @Test
    void remapAirVoxelDropsBiome() throws Exception {
        IdRemapper r = new IdRemapper();
        biomeMap(r).put(7, 17);
        // Server side: block=AIR (0), biome=7. Air should ignore biome on the client side.
        long serverVoxel = Mapper.composeMappingId((byte) 0x12, 0, 7);
        long clientVoxel = r.remapVoxelId(serverVoxel);

        assertEquals(0, Mapper.getBlockId(clientVoxel));
        assertEquals(0, Mapper.getBiomeId(clientVoxel), "air must drop biome");
        assertEquals(0x12, Mapper.getLightId(clientVoxel));
    }

    @Test
    void resetClearsAllStateAndIsReady() throws Exception {
        IdRemapper r = new IdRemapper();
        blockMap(r).put(1, 2);
        biomeMap(r).put(3, 4);
        // Force isReady=true via reflection (simulating buildFromServerData success).
        Field ready = IdRemapper.class.getDeclaredField("isReady");
        ready.setAccessible(true);
        ready.setBoolean(r, true);
        assertEquals(true, r.isReady());

        r.reset();
        assertFalse(r.isReady());
        assertEquals(0, blockMap(r).size());
        assertEquals(0, biomeMap(r).size());
        // After reset, default-return is still 0.
        assertEquals(0, blockMap(r).get(1));
    }

    @Test
    void unknownServerIdsMapToZero() throws Exception {
        IdRemapper r = new IdRemapper();
        blockMap(r).put(1, 100);
        // Server voxel uses block id 999 (unmapped). Should fall back to 0.
        long serverVoxel = Mapper.composeMappingId((byte) 0, 999, 0);
        long clientVoxel = r.remapVoxelId(serverVoxel);
        assertEquals(0, Mapper.getBlockId(clientVoxel));
    }
}
