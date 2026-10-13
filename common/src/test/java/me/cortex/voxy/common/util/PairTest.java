package me.cortex.voxy.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PairTest {

    @Test
    void storesBothValues() {
        Pair<String, Integer> p = new Pair<>("hello", 42);
        assertEquals("hello", p.left());
        assertEquals(42, p.right());
    }

    @Test
    void allowsNulls() {
        Pair<String, String> p = new Pair<>(null, null);
        assertNull(p.left());
        assertNull(p.right());
    }

    @Test
    void recordEqualityByValue() {
        Pair<String, Integer> a = new Pair<>("k", 1);
        Pair<String, Integer> b = new Pair<>("k", 1);
        Pair<String, Integer> c = new Pair<>("k", 2);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
