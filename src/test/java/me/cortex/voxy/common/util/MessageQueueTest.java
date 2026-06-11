package me.cortex.voxy.common.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageQueueTest {

    @Test
    void pushIncrementsCount() {
        MessageQueue<Integer> q = new MessageQueue<>(x -> {});
        assertEquals(0, q.count());
        q.push(1);
        q.push(2);
        assertEquals(2, q.count());
    }

    @Test
    void consumeDrainsInOrder() {
        List<Integer> seen = new ArrayList<>();
        MessageQueue<Integer> q = new MessageQueue<>(seen::add);
        for (int i = 0; i < 5; i++) q.push(i);
        assertEquals(5, q.consume());
        assertEquals(List.of(0, 1, 2, 3, 4), seen);
        assertEquals(0, q.count());
    }

    @Test
    void consumeRespectsMax() {
        List<Integer> seen = new ArrayList<>();
        MessageQueue<Integer> q = new MessageQueue<>(seen::add);
        for (int i = 0; i < 10; i++) q.push(i);
        assertEquals(3, q.consume(3));
        assertEquals(7, q.count());
        assertEquals(List.of(0, 1, 2), seen);
    }

    @Test
    void consumeOnEmptyReturnsZero() {
        MessageQueue<Integer> q = new MessageQueue<>(x -> {});
        assertEquals(0, q.consume());
        assertEquals(0, q.consume(100));
    }

    @Test
    void clearInvokesCleanerNotConsumer() {
        AtomicInteger consumed = new AtomicInteger();
        AtomicInteger cleaned = new AtomicInteger();
        MessageQueue<String> q = new MessageQueue<>(s -> consumed.incrementAndGet());
        q.push("a");
        q.push("b");
        q.clear(s -> cleaned.incrementAndGet());
        assertEquals(0, consumed.get());
        assertEquals(2, cleaned.get());
    }

    @Test
    void consumeNanoRespectsBudget() {
        MessageQueue<Integer> q = new MessageQueue<>(x -> {});
        for (int i = 0; i < 100; i++) q.push(i);
        int drained = q.consumeNano(1_000_000_000L);
        assertTrue(drained > 0);
    }
}
