package me.cortex.voxy.client.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpandingObjectAllocationListTest {

    @Test
    void putAssignsSequentialIdsAndStoresValue() {
        ExpandingObjectAllocationList<String> list = new ExpandingObjectAllocationList<>(String[]::new);
        int a = list.put("a");
        int b = list.put("b");
        int c = list.put("c");
        assertEquals(0, a);
        assertEquals(1, b);
        assertEquals(2, c);
        assertSame("a", list.get(a));
        assertSame("b", list.get(b));
        assertSame("c", list.get(c));
        assertEquals(3, list.count());
    }

    @Test
    void releaseFreesSlotAndReusesId() {
        ExpandingObjectAllocationList<String> list = new ExpandingObjectAllocationList<>(String[]::new);
        int a = list.put("a");
        int b = list.put("b");
        list.release(a);
        assertEquals(1, list.count());
        int reused = list.put("a2");
        assertEquals(a, reused, "released slot should be reused");
        assertSame("a2", list.get(reused));
        assertSame("b", list.get(b));
    }

    @Test
    void getOnUnallocatedThrows() {
        ExpandingObjectAllocationList<String> list = new ExpandingObjectAllocationList<>(String[]::new);
        list.put("only");
        assertThrows(IllegalArgumentException.class, () -> list.get(1));
    }

    @Test
    void doubleReleaseThrows() {
        ExpandingObjectAllocationList<String> list = new ExpandingObjectAllocationList<>(String[]::new);
        int id = list.put("x");
        list.release(id);
        assertThrows(IllegalArgumentException.class, () -> list.release(id));
    }

    @Test
    void getAfterReleaseThrows() {
        ExpandingObjectAllocationList<String> list = new ExpandingObjectAllocationList<>(String[]::new);
        int id = list.put("x");
        list.release(id);
        assertThrows(IllegalArgumentException.class, () -> list.get(id));
    }

    @Test
    void growsBeyondInitialCapacity() {
        // Initial capacity is 16 — push past it.
        ExpandingObjectAllocationList<Integer> list = new ExpandingObjectAllocationList<>(Integer[]::new);
        for (int i = 0; i < 100; i++) {
            int id = list.put(i);
            assertEquals(i, id);
        }
        assertEquals(100, list.count());
        for (int i = 0; i < 100; i++) {
            assertEquals(Integer.valueOf(i), list.get(i));
        }
    }

    @Test
    void countTracksAllocatedSlots() {
        ExpandingObjectAllocationList<Object> list = new ExpandingObjectAllocationList<>(Object[]::new);
        assertEquals(0, list.count());
        int a = list.put(new Object());
        int b = list.put(new Object());
        int c = list.put(new Object());
        assertEquals(3, list.count());
        list.release(b);
        assertEquals(2, list.count());
        list.release(a);
        list.release(c);
        assertEquals(0, list.count());
    }

    @Test
    void releaseClearsObjectReference() throws Exception {
        // Reach into the package-private internals via reflection: confirm release nulls
        // the slot so the GC can reclaim the value.
        ExpandingObjectAllocationList<Object> list = new ExpandingObjectAllocationList<>(Object[]::new);
        Object value = new Object();
        int id = list.put(value);
        list.release(id);

        var f = ExpandingObjectAllocationList.class.getDeclaredField("objects");
        f.setAccessible(true);
        Object[] arr = (Object[]) f.get(list);
        assertNull(arr[id], "slot must be nulled out on release");
    }

    @Test
    void manyAllocReleaseCyclesStayConsistent() {
        ExpandingObjectAllocationList<Integer> list = new ExpandingObjectAllocationList<>(Integer[]::new);
        // Fill to 50, release every other slot, refill — exercises the bitset's allocation logic.
        for (int i = 0; i < 50; i++) list.put(i);
        for (int i = 0; i < 50; i += 2) list.release(i);
        assertEquals(25, list.count());
        for (int i = 0; i < 25; i++) {
            int id = list.put(1000 + i);
            assertTrue(id < 50, "should reuse freed lower ids first, got " + id);
        }
        assertEquals(50, list.count());
    }
}
