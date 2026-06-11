package me.cortex.voxy.common.network;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomFilterTest {

    @Test
    void addedKeyIsReportedAsPresent() {
        BloomFilter f = new BloomFilter(1024);
        f.add(12345L);
        assertTrue(f.mightContain(12345L));
    }

    @Test
    void emptyFilterReportsAbsence() {
        BloomFilter f = new BloomFilter(1024);
        assertFalse(f.mightContain(42L));
    }

    @Test
    void noFalseNegativesUnderBulkLoad() {
        BloomFilter f = BloomFilter.forExpectedElements(500);
        Random rng = new Random(0xBEEF);
        Set<Long> added = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            long k = rng.nextLong();
            added.add(k);
            f.add(k);
        }
        for (long k : added) {
            assertTrue(f.mightContain(k), "added key missing: " + k);
        }
    }

    @Test
    void falsePositiveRateReasonableForSizedFilter() {
        BloomFilter f = BloomFilter.forExpectedElements(1000);
        Random rng = new Random(1);
        Set<Long> added = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long k = rng.nextLong();
            added.add(k);
            f.add(k);
        }
        int tested = 0;
        int falsePositives = 0;
        while (tested < 10_000) {
            long probe = rng.nextLong();
            if (added.contains(probe)) continue;
            tested++;
            if (f.mightContain(probe)) falsePositives++;
        }
        double rate = falsePositives / (double) tested;
        assertTrue(rate < 0.05, "FP rate too high: " + rate);
    }

    @Test
    void addAllCollectionAndArrayEquivalent() {
        List<Long> list = new ArrayList<>();
        long[] arr = new long[10];
        for (int i = 0; i < 10; i++) {
            list.add((long) (i * 7919));
            arr[i] = i * 7919;
        }
        BloomFilter a = new BloomFilter(512);
        BloomFilter b = new BloomFilter(512);
        a.addAll(list);
        b.addAll(arr);
        for (long k : arr) {
            assertTrue(a.mightContain(k));
            assertTrue(b.mightContain(k));
        }
    }

    @Test
    void serializationRoundTripPreservesMembership() {
        BloomFilter orig = new BloomFilter(512);
        for (long k : new long[] { 1, 2, 3, 1L << 40, -999L }) orig.add(k);
        byte[] bytes = orig.toBytes();
        assertEquals(orig.getSerializedSize(), bytes.length);

        BloomFilter restored = BloomFilter.fromBytes(bytes);
        for (long k : new long[] { 1, 2, 3, 1L << 40, -999L }) {
            assertTrue(restored.mightContain(k));
        }
    }

    @Test
    void fromBytesNullOrTooShortReturnsUsableFilter() {
        BloomFilter f1 = BloomFilter.fromBytes(null);
        BloomFilter f2 = BloomFilter.fromBytes(new byte[3]);
        assertFalse(f1.mightContain(1));
        assertFalse(f2.mightContain(1));
        f1.add(1);
        assertTrue(f1.mightContain(1));
    }

    @Test
    void mergeUnionsMembership() {
        BloomFilter a = new BloomFilter(512);
        BloomFilter b = new BloomFilter(512);
        a.add(10L);
        b.add(20L);
        a.merge(b);
        assertTrue(a.mightContain(10L));
        assertTrue(a.mightContain(20L));
    }

    @Test
    void mergeNullIsNoOp() {
        BloomFilter a = new BloomFilter(128);
        a.add(1L);
        a.merge(null);
        assertTrue(a.mightContain(1L));
    }

    @Test
    void forExpectedElementsRespectsMinimumSize() {
        BloomFilter tiny = BloomFilter.forExpectedElements(0);
        assertTrue(tiny.getSerializedSize() >= 4 + 8);
    }
}
