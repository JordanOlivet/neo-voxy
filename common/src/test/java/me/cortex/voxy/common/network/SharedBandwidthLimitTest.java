package me.cortex.voxy.common.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedBandwidthLimitTest {

    @Test
    void singleSenderGetsFullGlobalLimit() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 1000);
        Object sender = new Object();
        limit.setSenderActive(sender, true);
        assertEquals(1000, limit.getBandwidthShareKBps());
    }

    @Test
    void multipleSendersSplitEqually() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 1200);
        Object a = new Object();
        Object b = new Object();
        Object c = new Object();
        limit.setSenderActive(a, true);
        limit.setSenderActive(b, true);
        limit.setSenderActive(c, true);
        assertEquals(3, limit.getActiveSenderCount());
        assertEquals(400, limit.getBandwidthShareKBps());
    }

    @Test
    void zeroSendersReturnsFullLimit() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 500);
        assertEquals(500, limit.getBandwidthShareKBps());
        assertEquals(0, limit.getActiveSenderCount());
    }

    @Test
    void zeroOrNegativeGlobalMeansUnlimited() {
        SharedBandwidthLimit zero = new SharedBandwidthLimit(() -> 0);
        SharedBandwidthLimit neg = new SharedBandwidthLimit(() -> -1);
        assertEquals(Integer.MAX_VALUE, zero.getBandwidthShareKBps());
        assertEquals(Integer.MAX_VALUE, neg.getBandwidthShareKBps());
    }

    @Test
    void inactiveSendersDoNotShrinkShare() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 1000);
        Object a = new Object();
        Object b = new Object();
        limit.setSenderActive(a, true);
        limit.setSenderActive(b, true);
        assertEquals(500, limit.getBandwidthShareKBps());
        limit.setSenderActive(b, false);
        assertEquals(1000, limit.getBandwidthShareKBps());
        assertEquals(1, limit.getActiveSenderCount());
    }

    @Test
    void removingInactiveSenderIsNoOp() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 1000);
        Object a = new Object();
        limit.setSenderActive(a, false);
        assertEquals(0, limit.getActiveSenderCount());
    }

    @Test
    void bytesPerTickRespectsPerPlayerCap() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 10_000);
        Object a = new Object();
        limit.setSenderActive(a, true);
        int bytes = limit.getBytesPerTick(100);
        assertEquals(100 * 1000 / 20 + 1, bytes);
    }

    @Test
    void bytesPerTickRespectsShareWhenLower() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 200);
        Object a = new Object();
        Object b = new Object();
        limit.setSenderActive(a, true);
        limit.setSenderActive(b, true);
        int bytes = limit.getBytesPerTick(1000);
        assertEquals(100 * 1000 / 20 + 1, bytes);
    }

    @Test
    void bytesPerTickUnlimitedWhenBothUnbounded() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 0);
        Object a = new Object();
        limit.setSenderActive(a, true);
        assertEquals(Integer.MAX_VALUE, limit.getBytesPerTick(Integer.MAX_VALUE));
    }

    @Test
    void bytesPerTickUnlimitedWhenPerPlayerZero() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 1000);
        Object a = new Object();
        limit.setSenderActive(a, true);
        assertEquals(Integer.MAX_VALUE, limit.getBytesPerTick(0));
    }

    @Test
    void effectiveLimitRespectsPerPlayerCap() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 10_000);
        Object a = new Object();
        limit.setSenderActive(a, true);
        assertEquals(100, limit.getEffectiveLimitKBps(100));
    }

    @Test
    void effectiveLimitRespectsShareWhenLower() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 200);
        Object a = new Object();
        Object b = new Object();
        limit.setSenderActive(a, true);
        limit.setSenderActive(b, true);
        assertEquals(100, limit.getEffectiveLimitKBps(1000));
    }

    @Test
    void effectiveLimitAppliesPerPlayerCapEvenWhenGlobalUnlimited() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 0);
        Object a = new Object();
        limit.setSenderActive(a, true);
        assertEquals(1000, limit.getEffectiveLimitKBps(1000));
    }

    @Test
    void effectiveLimitUnlimitedWhenBothUnbounded() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 0);
        Object a = new Object();
        limit.setSenderActive(a, true);
        assertEquals(Integer.MAX_VALUE, limit.getEffectiveLimitKBps(0));
    }

    @Test
    void effectiveLimitUnlimitedWhenPerPlayerZero() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit(() -> 1000);
        Object a = new Object();
        limit.setSenderActive(a, true);
        assertEquals(Integer.MAX_VALUE, limit.getEffectiveLimitKBps(0));
    }

    @Test
    void defaultConstructorUsesConstantLimit() {
        SharedBandwidthLimit limit = new SharedBandwidthLimit();
        Object a = new Object();
        limit.setSenderActive(a, true);
        assertEquals(SharedBandwidthLimit.DEFAULT_GLOBAL_LIMIT_KBPS, limit.getBandwidthShareKBps());
    }
}
