package me.cortex.voxy.commonImpl;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldIdentifierStaticsTest {

    @Test
    void mixStafford13IsDeterministic() {
        assertEquals(WorldIdentifier.mixStafford13(0L),
                WorldIdentifier.mixStafford13(0L));
        assertEquals(WorldIdentifier.mixStafford13(123L),
                WorldIdentifier.mixStafford13(123L));
        assertEquals(WorldIdentifier.mixStafford13(-1L),
                WorldIdentifier.mixStafford13(-1L));
    }

    @Test
    void mixStafford13DiffersForDifferentInputs() {
        assertNotEquals(WorldIdentifier.mixStafford13(0L),
                WorldIdentifier.mixStafford13(1L));
        assertNotEquals(WorldIdentifier.mixStafford13(123L),
                WorldIdentifier.mixStafford13(124L));
    }

    @Test
    void mixStafford13ProducesGoodAvalancheBehavior() {
        // Sanity check: 1024 distinct inputs must yield 1024 distinct outputs.
        Set<Long> seen = new HashSet<>();
        Random r = new Random(0xC0FFEEL);
        for (int i = 0; i < 1024; i++) {
            long out = WorldIdentifier.mixStafford13(r.nextLong());
            assertTrue(seen.add(out), "collision at iteration " + i);
        }
    }

    @Test
    void mixStafford13SequentialInputsDiverge() {
        // Adjacent inputs must produce wildly different outputs (no clustering).
        long a = WorldIdentifier.mixStafford13(1000L);
        long b = WorldIdentifier.mixStafford13(1001L);
        long delta = a ^ b;
        assertTrue(Long.bitCount(delta) > 16,
                "adjacent inputs should differ in many bits, got " + Long.bitCount(delta));
    }
}
