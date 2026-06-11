package me.cortex.voxy.common.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxyPacketPayloadTest {

    @Test
    void rateUpdateRoundTrip() {
        VoxyPacketPayload p = VoxyPacketPayload.rateUpdate(123_456);
        assertEquals(VoxyPacketPayload.MSG_RATE_UPDATE, p.messageType());
        assertEquals(123_456, p.parseRate());
    }

    @Test
    void rateUpdatePreservesNegativeValues() {
        // Signed int wire format: should survive wrap-around.
        VoxyPacketPayload p = VoxyPacketPayload.rateUpdate(-1);
        assertEquals(-1, p.parseRate());
    }

    @Test
    void parseRateRejectsWrongMessageType() {
        VoxyPacketPayload wrong = new VoxyPacketPayload(VoxyPacketPayload.MSG_LOD_SECTION, new byte[] { 0, 0, 0, 42 });
        assertEquals(0, wrong.parseRate());
    }

    @Test
    void parseRateRejectsShortPayload() {
        VoxyPacketPayload truncated = new VoxyPacketPayload(VoxyPacketPayload.MSG_RATE_UPDATE, new byte[] { 1, 2 });
        assertEquals(0, truncated.parseRate());
    }

    @Test
    void requestSectionsEmptyArrayRoundTrip() {
        VoxyPacketPayload p = VoxyPacketPayload.requestSections(new long[0]);
        assertEquals(VoxyPacketPayload.MSG_REQUEST_SECTIONS, p.messageType());
        assertArrayEquals(new long[0], p.parseRequestedKeys());
    }

    @Test
    void requestSectionsNullArrayRoundTrip() {
        VoxyPacketPayload p = VoxyPacketPayload.requestSections(null);
        assertArrayEquals(new long[0], p.parseRequestedKeys());
    }

    @Test
    void requestSectionsSingleKeyRoundTrip() {
        long[] keys = { 0x123456789ABCDEFL };
        VoxyPacketPayload p = VoxyPacketPayload.requestSections(keys);
        assertArrayEquals(keys, p.parseRequestedKeys());
    }

    @Test
    void requestSectionsSortsOnEncode() {
        // Decoder reconstructs in sorted order regardless of input ordering.
        long[] in = { 100L, 50L, 75L, 25L };
        long[] expected = in.clone();
        Arrays.sort(expected);
        VoxyPacketPayload p = VoxyPacketPayload.requestSections(in);
        assertArrayEquals(expected, p.parseRequestedKeys());
    }

    @Test
    void requestSectionsDeltaEncodesDenseKeys() {
        long[] keys = new long[100];
        for (int i = 0; i < keys.length; i++) keys[i] = 1_000_000L + i;
        VoxyPacketPayload p = VoxyPacketPayload.requestSections(keys);
        assertArrayEquals(keys, p.parseRequestedKeys());

        // Each delta is 1 → VarLong = 1 byte. Total ≈ 4 (count) + 8 (first) + 99 (deltas) = 111.
        // Allow slight slack but far below 100*8 = 800 of a naive encoding.
        assertTrue(p.data().length < 200,
                "delta encoding should be compact for dense keys, got " + p.data().length);
    }

    @Test
    void requestSectionsHandlesNegativeAndLargeDeltas() {
        long[] keys = { -1_000_000_000L, 0L, 1_000_000_000L, Long.MAX_VALUE / 2 };
        VoxyPacketPayload p = VoxyPacketPayload.requestSections(keys);
        long[] expected = keys.clone();
        Arrays.sort(expected);
        assertArrayEquals(expected, p.parseRequestedKeys());
    }

    @Test
    void parseRequestedKeysRejectsWrongMessageType() {
        VoxyPacketPayload wrong = VoxyPacketPayload.cacheQuery(new long[] { 1, 2, 3 });
        assertArrayEquals(new long[0], wrong.parseRequestedKeys());
    }

    @Test
    void parseRequestedKeysRejectsBatchSizeOverLimit() {
        // Craft a payload advertising count = 2001 (above the 2000 limit).
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(baos);
        try {
            out.writeInt(2001);
            out.writeLong(0L);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        VoxyPacketPayload p = new VoxyPacketPayload(VoxyPacketPayload.MSG_REQUEST_SECTIONS, baos.toByteArray());
        assertArrayEquals(new long[0], p.parseRequestedKeys());
    }

    @Test
    void cacheQueryRoundTrip() {
        long[] keys = { 42L, 100L, -7L, 0L };
        VoxyPacketPayload p = VoxyPacketPayload.cacheQuery(keys);
        assertEquals(VoxyPacketPayload.MSG_CACHE_QUERY, p.messageType());
        long[] expected = keys.clone();
        Arrays.sort(expected);
        assertArrayEquals(expected, p.parseCacheQueryKeys());
    }

    @Test
    void cacheQueryEmptyArrayRoundTrip() {
        VoxyPacketPayload p = VoxyPacketPayload.cacheQuery(new long[0]);
        assertArrayEquals(new long[0], p.parseCacheQueryKeys());
    }

    @Test
    void parseCacheQueryKeysRejectsWrongMessageType() {
        VoxyPacketPayload wrong = VoxyPacketPayload.requestSections(new long[] { 1 });
        assertArrayEquals(new long[0], wrong.parseCacheQueryKeys());
    }

    @Test
    void cacheResponseRoundTripPreservesMembership() {
        BloomFilter bf = BloomFilter.forExpectedElements(128);
        long[] inserted = { 1L, 17L, 999L, -42L };
        for (long k : inserted) bf.add(k);

        VoxyPacketPayload p = VoxyPacketPayload.cacheResponse(bf);
        assertEquals(VoxyPacketPayload.MSG_CACHE_RESPONSE, p.messageType());

        BloomFilter restored = p.parseCacheResponseBloomFilter();
        for (long k : inserted) {
            assertTrue(restored.mightContain(k), "key " + k + " should survive round-trip");
        }
    }

    @Test
    void sectionAndChunkHelpersSetCorrectType() {
        byte[] payload = { 1, 2, 3 };
        assertEquals(VoxyPacketPayload.MSG_LOD_SECTION, VoxyPacketPayload.section(payload).messageType());
        assertEquals(VoxyPacketPayload.MSG_LOD_CHUNK, VoxyPacketPayload.chunk(payload).messageType());
        assertEquals(VoxyPacketPayload.MSG_MAPPER_SYNC, VoxyPacketPayload.mapperSync(payload).messageType());
        assertEquals(VoxyPacketPayload.MSG_SYNC_REQUEST, VoxyPacketPayload.syncRequest().messageType());
    }
}
