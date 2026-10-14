package me.cortex.voxy.client.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingUtilTest {

    private static int unpack10(int packed, int shift) {
        return (packed >> shift) & ((1 << 10) - 1);
    }

    private static int signExtend10(int v) {
        // 10-bit field is masked unsigned in pack(); recover signed value.
        return (v & 0x200) != 0 ? v | ~0x3FF : v;
    }

    @Test
    void halfSphereRadiusZeroProducesSinglePoint() {
        int[] points = RingUtil.generateBoundingHalfSphere(0);
        assertEquals(1, points.length);
        // pack(0,0,0) == 0
        assertEquals(0, points[0]);
    }

    @Test
    void halfCircleRadiusZeroProducesSinglePoint() {
        int[] points = RingUtil.generateBoundingHalfCircle(0);
        assertEquals(1, points.length);
        // pack(0,0) (16-bit pack) == 0
        assertEquals(0, points[0]);
    }

    @Test
    void halfSphereRadiusOneCovers3x3Grid() {
        // For radius=1, every (a,b) in [-1,1]^2 satisfies a^2+b^2 <= 1 except corners
        // (|a|=1 and |b|=1 → sum=2). So expect 9-4 = 5 points.
        int[] points = RingUtil.generateBoundingHalfSphere(1);
        assertEquals(5, points.length);
    }

    @Test
    void halfCircleRadiusOneProducesThreePoints() {
        // a in {-1,0,1}, all satisfy a^2 <= 1. Expect 3 points.
        int[] points = RingUtil.generateBoundingHalfCircle(1);
        assertEquals(3, points.length);
    }

    @Test
    void halfSpherePointsLieWithinSphere() {
        int radius = 8;
        int[] points = RingUtil.generateBoundingHalfSphere(radius);
        for (int packed : points) {
            int a = signExtend10(unpack10(packed, 0));
            int b = signExtend10(unpack10(packed, 10));
            int d = packed >>> 20;
            // d = floor(sqrt(r^2 - a^2 - b^2)) so d^2 <= r^2 - a^2 - b^2
            assertTrue(a * a + b * b + d * d <= radius * radius,
                    "point (" + a + "," + b + "," + d + ") must lie within radius " + radius);
        }
    }

    @Test
    void halfCirclePointsLieWithinCircle() {
        int radius = 16;
        int[] points = RingUtil.generateBoundingHalfCircle(radius);
        int sqr = radius * radius;
        for (int packed : points) {
            int a = (short) (packed & 0xFFFF);
            int d = (short) ((packed >>> 16) & 0xFFFF);
            assertTrue(a * a + d * d <= sqr,
                    "point (" + a + "," + d + ") must lie within radius " + radius);
        }
    }

    @Test
    void halfSphereCountGrowsWithRadius() {
        int[] r2 = RingUtil.generateBoundingHalfSphere(2);
        int[] r5 = RingUtil.generateBoundingHalfSphere(5);
        int[] r10 = RingUtil.generateBoundingHalfSphere(10);
        assertTrue(r2.length < r5.length);
        assertTrue(r5.length < r10.length);
    }

    @Test
    void corner2DAllPointsLieInsideRadius() {
        int radius = 12;
        int[] pts = RingUtil.generatingBoundingCorner2D(radius);
        int sqr = radius * radius;
        for (int packed : pts) {
            int x = packed >>> 16;
            int y = packed & 0xFFFF;
            assertTrue(x * x + y * y <= sqr,
                    "corner (" + x + "," + y + ") must lie within radius " + radius);
        }
    }

    @Test
    void corner2DProducesRadiusPointsForRadius1() {
        // Loop runs i=1..radius=1 → 1 entry.
        int[] pts = RingUtil.generatingBoundingCorner2D(1);
        assertEquals(1, pts.length);
    }
}
